package io.github.yesidrobayo.shopledger.data.backup

import io.github.yesidrobayo.shopledger.data.destination.newSha256
import io.github.yesidrobayo.shopledger.data.destination.toLowerHex
import io.github.yesidrobayo.shopledger.data.local.DatabaseFiles
import java.io.File
import kotlinx.coroutines.runBlocking

/** Cómo responde la verificación de la base nueva. */
internal enum class VerifyBehavior { OK, SHA_BAD, OPEN_FAILS, COUNTERS_BAD }

/** Un punto de partida del reemplazo de la base, al terminar la preparación. */
internal data class SwapScenario(
    val hadActive: Boolean,
    val oldWal: Boolean,
    val safetyOk: Boolean,
    val verify: VerifyBehavior,
    val candidatePresent: Boolean,
    val encrypted: Boolean,
    val undo: Boolean,
) {
    /** Si el reemplazo debe terminar con la base nueva en su sitio. */
    val shouldRestore: Boolean get() = candidatePresent && verify == VerifyBehavior.OK

    companion object {
        fun all(): List<SwapScenario> = buildList {
            for (had in listOf(true, false)) {
                for (wal in listOf(true, false)) {
                    if (!had && wal) continue
                    for (safe in listOf(true, false)) {
                        for (verify in VerifyBehavior.entries) {
                            for (candidate in listOf(true, false)) {
                                for (encrypted in listOf(false, true)) {
                                    for (undo in listOf(false, true)) {
                                        add(
                                            SwapScenario(
                                                had,
                                                wal,
                                                safe,
                                                verify,
                                                candidate,
                                                encrypted,
                                                undo,
                                            ),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** La muerte del proceso: hereda de `Error` para que ningún `catch` de excepciones la atrape. */
internal class SimulatedDeath(operation: String) : Error(operation)

/**
 * Corre la segunda fase del reemplazo sobre archivos reales en [workDirectory], con un plan de
 * muertes: `plan[k]` es la operación del arranque `k + 1` tras la que muere el proceso. Cada
 * arranque es un proceso nuevo: objetos nuevos sobre los mismos archivos.
 *
 * Las bases son archivos con una marca de texto en vez de SQLite: así se ve en cada punto qué base
 * está dónde. La verificación falsa hace lo que hace la app al abrir la base: le cambia el
 * principal y crea su `-wal` y su `-shm`. Con SQLite real se prueba en el dispositivo.
 */
internal class SwapHarness(private val workDirectory: File) {
    /** Lo que quedó al final, y la cantidad de operaciones de cada arranque. */
    data class Run(val violations: List<String>, val operationsPerBoot: List<Int>)

    fun run(scenario: SwapScenario, plan: List<Int>): Run {
        workDirectory.deleteRecursively()
        val files =
            RestoreFiles(
                DatabaseFiles(
                    File(workDirectory, "databases/shopledger.db").absoluteFile,
                    File(workDirectory, "no_backup").absoluteFile,
                ),
            )
        prepare(scenario, files)
        val operations = mutableListOf<Int>()
        var boot = 0
        var finished = false
        while (!finished) {
            check(boot <= plan.size + MAX_CLEAN_BOOTS) { "El reemplazo no termina" }
            val deathAt = plan.getOrNull(boot)
            var count = 0
            val listener = OperationListener { operation ->
                count++
                if (deathAt != null && count == deathAt) throw SimulatedDeath(operation)
            }
            val ops = DurableFileOps(directorySync = { check(it.isDirectory) }, listener = listener)
            try {
                val restore = StartupRestore(files, ops)
                val result = runBlocking { restore.verifyAndFinish(FakeOpen(scenario, files)) }
                finished = result !is StartupRestoreResult.VerificationPending && boot >= plan.size
            } catch (_: SimulatedDeath) {
                // El proceso murió: el siguiente arranque retoma desde el disco.
            }
            operations += count
            boot++
        }
        return Run(evaluate(scenario, files), operations)
    }

    private fun prepare(scenario: SwapScenario, files: RestoreFiles) {
        if (scenario.hadActive) {
            write(files.database, OLD)
            if (scenario.oldWal) write(files.databaseWal, OLD_WAL)
        }
        if (scenario.candidatePresent) write(files.candidate, NEW)
        write(files.sealedKey, OLD_KEY)
        if (scenario.encrypted) write(files.pendingKey, NEW_KEY)
        if (scenario.undo) {
            write(files.undoSource.database, UNDO_SOURCE)
            write(files.safetyCopies[0].facts, "{}")
        }
        if (scenario.safetyOk && scenario.hadActive && !scenario.undo) {
            write(files.safetyCopies[0].database, SAFETY)
            write(files.safetyCopies[0].facts, "{}")
        }
        write(files.lock, "")
        val sha = if (scenario.verify == VerifyBehavior.SHA_BAD) OTHER_SHA else sha(NEW)
        RestoreJournalStore(files, DurableFileOps(directorySync = {})).write(
            RestoreJournal(
                state = RestoreJournalState.PREPARED,
                expectedSha256 = sha,
                expectedChangeSeq = CHANGE_SEQ,
                expectedRecordCount = RECORDS,
                hadActiveDb = scenario.hadActive,
                safetyCopyOk = scenario.safetyOk,
            ),
        )
    }

    private fun evaluate(scenario: SwapScenario, files: RestoreFiles): List<String> = buildList {
        fun expect(what: String, condition: Boolean) {
            if (!condition) add(what)
        }
        val main = read(files.database)
        expect("queda el diario", !files.journal.exists())
        expect("queda el cerrojo", !files.lock.exists())
        expect("queda la candidata", !files.candidate.exists())
        expect("queda previous/", !files.previous.exists() && !files.previousWal.exists())
        expect("queda la clave pendiente", !files.pendingKey.exists())
        if (scenario.shouldRestore) {
            expect("no quedó la base nueva: $main", main?.startsWith(NEW) == true)
            val oldKept = read(files.damaged)
            if (scenario.hadActive && !scenario.safetyOk) {
                expect("la base original no se apartó", oldKept == OLD)
                if (scenario.oldWal) {
                    expect(
                        "su -wal no se apartó",
                        read(files.damagedWal) == OLD_WAL,
                    )
                }
            } else {
                expect("se apartó una base sin motivo", oldKept == null)
            }
            expect("quedó un -wal viejo junto a la nueva", read(files.databaseWal) != OLD_WAL)
            val key = if (scenario.encrypted) NEW_KEY else OLD_KEY
            expect("la clave vigente no es la esperada", read(files.sealedKey) == key)
            expect("quedó el origen del deshacer", !files.undoSource.database.exists())
            expect("la marca de base creada falta", files.initializedMarker.exists())
        } else {
            if (scenario.hadActive) {
                expect("no volvió la base vieja: $main", main == OLD)
                if (scenario.oldWal) {
                    expect(
                        "se perdió el -wal viejo",
                        read(files.databaseWal) == OLD_WAL,
                    )
                }
                expect("quedó un -wal ajeno", scenario.oldWal || !files.databaseWal.exists())
            } else {
                expect("quedó una base sin haber ninguna: $main", main == null)
                expect("quedó un -wal sin base", !files.databaseWal.exists())
            }
            expect("la clave vigente cambió", read(files.sealedKey) == OLD_KEY)
            if (scenario.undo) {
                expect(
                    "el origen del deshacer no volvió a su nombre",
                    read(files.safetyCopies[0].database) == UNDO_SOURCE,
                )
            }
        }
        if (scenario.safetyOk && scenario.hadActive && !scenario.undo) {
            expect(
                "se perdió la copia de seguridad",
                read(files.safetyCopies[0].database) == SAFETY,
            )
        }
    }

    /** Abre la base como la app: el principal cambia y nacen su `-wal` y su `-shm`. */
    private class FakeOpen(private val scenario: SwapScenario, private val files: RestoreFiles) :
        RestoredDatabaseVerifier<String> {
        override suspend fun open(database: File): RestoredDatabaseCheck<String> {
            check(database.exists()) { "Se abrió una base que no existe: se crearía vacía" }
            if (scenario.verify == VerifyBehavior.OPEN_FAILS) return RestoredDatabaseCheck.Failed
            database.appendText(OPENED)
            write(files.databaseWal, NEW_WAL)
            write(files.databaseShm, "")
            val records = if (scenario.verify ==
                VerifyBehavior.COUNTERS_BAD
            ) {
                RECORDS + 1
            } else {
                RECORDS
            }
            return RestoredDatabaseCheck.Opened(CHANGE_SEQ, records, database.readText()) {}
        }
    }

    companion object {
        private const val MAX_CLEAN_BOOTS = 3
        const val OLD = "base vieja"
        const val OLD_WAL = "wal de la base vieja"
        const val NEW = "base nueva"
        const val NEW_WAL = "wal de la base nueva"
        const val OPENED = " abierta"
        const val SAFETY = "copia de seguridad"
        const val UNDO_SOURCE = "origen del deshacer"
        const val OLD_KEY = "código vigente"
        const val NEW_KEY = "código de la copia"
        const val CHANGE_SEQ = 120L
        const val RECORDS = 4_850L
        val OTHER_SHA = "0".repeat(64)

        fun write(file: File, text: String) {
            requireNotNull(file.parentFile).mkdirs()
            file.writeText(text)
        }

        fun read(file: File): String? = if (file.exists()) file.readText() else null

        fun sha(text: String): String = newSha256().digest(text.toByteArray()).toLowerHex()
    }
}
