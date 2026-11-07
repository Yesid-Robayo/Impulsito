package io.github.yesidrobayo.shopledger.data.backup

import androidx.room3.useReaderConnection
import androidx.sqlite.execSQL
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.data.RescueCause
import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.data.local.DatabaseFiles
import io.github.yesidrobayo.shopledger.data.local.DatabaseGate
import io.github.yesidrobayo.shopledger.data.local.GateCaller
import io.github.yesidrobayo.shopledger.data.local.GateOutcome
import io.github.yesidrobayo.shopledger.data.local.PreMigrationResult
import io.github.yesidrobayo.shopledger.data.local.deleteDatabaseFiles
import io.github.yesidrobayo.shopledger.data.local.inspectWithoutRoom
import io.github.yesidrobayo.shopledger.data.local.longValue
import io.github.yesidrobayo.shopledger.domain.port.EventLog
import io.github.yesidrobayo.shopledger.domain.port.LogLevel
import io.github.yesidrobayo.shopledger.domain.port.LogLine
import io.github.yesidrobayo.shopledger.domain.port.SystemClock
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * El reemplazo de la base con SQLite y Room de verdad, sobre bases del generador: la muerte del
 * proceso tras cada operación, también dos veces seguidas, siempre deja la base vieja o la nueva,
 * completas, y abiertas por Room con sus filas. Nunca queda una base vacía. Todos los datos son
 * inventados.
 */
@RunWith(AndroidJUnit4::class)
class StartupRestoreDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.noBackupFilesDir, "startup-restore-test")
    private val files =
        RestoreFiles(
            DatabaseFiles(
                File(root, "databases/shopledger.db").absoluteFile,
                File(root, "no_backup"),
            ),
        )
    private val opened = mutableListOf<AppDatabase>()

    @Before
    fun clean() {
        root.deleteRecursively()
        CandidateFixtures.clean()
    }

    @After
    fun cleanUp() {
        closeAll()
        clean()
    }

    @Test
    fun aDeathAfterEachOperationLeavesTheOldOrTheNewDatabaseWhole() {
        for (restores in listOf(true, false)) {
            val clean = runCase(restores, deaths = emptyList())
            assertTrue("El reemplazo no hizo nada", clean.isNotEmpty())
            for (first in 1..clean.first()) {
                val counts = runCase(restores, listOf(first))
                // Y otra muerte en cada operación del arranque que recupera.
                val recovery = counts.getOrElse(1) { 0 }
                val seconds = if (restores) 1..recovery else 1..minOf(recovery, 2)
                for (second in seconds) runCase(restores, listOf(first, second))
            }
        }
    }

    @Test
    fun theGateFinishesARestoreOnlyForTheApp() = runBlocking {
        prepare(restores = true)
        val gate = gate()
        // Los pasos de archivo corren para un trabajo, que espera a la app para verificar.
        assertEquals(GateOutcome.RestorePending, gate.pass(GateCaller.BACKGROUND_WORK))
        assertTrue(files.journal.exists())

        val outcome = gate.pass(GateCaller.APP_IN_FOREGROUND)

        assertTrue("$outcome", outcome is GateOutcome.Opened)
        val database = (outcome as GateOutcome.Opened).database.also(opened::add)
        assertEquals(
            newRecords,
            database.useReaderConnection {
                it.longValue(RestoredDatabaseCounters.RECORD_COUNT_SQL)
            },
        )
        assertFalse(files.journal.exists())
        assertFalse(files.lock.exists())
        assertTrue(files.initializedMarker.exists())
    }

    @Test
    fun anUnreadableJournalIsSetAsideAndLeadsToRescue() = runBlocking {
        prepare(restores = true)
        files.journal.writeText("{\"state\":")

        assertEquals(
            GateOutcome.Rescue(RescueCause.RestoreJournalUnreadable),
            gate().pass(GateCaller.APP_IN_FOREGROUND),
        )
        assertTrue(files.journalUnreadable.exists())
        assertEquals(oldRecords, recordsIn(files.database))
        // Cada arranque siguiente vuelve al rescate, también para un trabajo: sin el diario no se
        // sabe qué base es la buena, y nada se abre en silencio.
        for (caller in GateCaller.entries) {
            assertEquals(
                GateOutcome.Rescue(RescueCause.RestoreJournalUnreadable),
                gate().pass(caller),
            )
        }
        assertEquals(oldRecords, recordsIn(files.database))
    }

    @Test
    fun aRollBackWithoutAnyDatabaseNeverLetsRoomCreateAnEmptyOne() = runBlocking {
        prepare(restores = false, hadActive = false)

        assertEquals(
            GateOutcome.Rescue(RescueCause.RestoreLeftNoDatabase),
            gate().pass(GateCaller.APP_IN_FOREGROUND),
        )
        assertFalse(files.database.exists())
    }

    private var oldRecords = 0L
    private var newRecords = 0L

    /** Corre un reemplazo con [deaths] y comprueba el final. Devuelve operaciones por arranque. */
    private fun runCase(restores: Boolean, deaths: List<Int>): List<Int> {
        closeAll()
        prepare(restores)
        val counts = mutableListOf<Int>()
        var boot = 0
        while (true) {
            check(boot <= deaths.size + 3) { "El reemplazo no termina" }
            var count = 0
            val deathAt = deaths.getOrNull(boot)
            val ops = DurableFileOps(listener = {
                count++
                if (deathAt != null && count == deathAt) throw Death()
            })
            val result =
                try {
                    runBlocking {
                        StartupRestore(
                            files,
                            ops,
                            SystemClock,
                        ).verifyAndFinish(RoomRestoredDatabaseVerifier(::open))
                    }
                } catch (_: Death) {
                    // El proceso murió: ninguna conexión sobrevive.
                    null
                }
            counts += count
            boot++
            if (result == null) {
                closeAll()
                continue
            }
            if (boot > deaths.size) {
                val case = "restaura=$restores, muertes=$deaths"
                when (result) {
                    is StartupRestoreResult.Restored -> assertTrue(case, restores)
                    is StartupRestoreResult.RolledBack -> assertTrue(case, result.databasePresent)
                    StartupRestoreResult.NoRestore -> Unit
                    else -> error("$case: $result")
                }
                closeAll()
                assertEquals(
                    case,
                    if (restores) newRecords else oldRecords,
                    recordsIn(files.database),
                )
                assertFalse(case, files.journal.exists())
                assertFalse(case, files.previous.exists())
                assertFalse(case, files.candidate.exists())
                return counts
            }
        }
    }

    /**
     * La base vieja, abierta y cerrada por Room como la deja la app, y una candidata preparada como
     * la deja la primera fase: sin diario de WAL, con su
     * suma y sus contadores en el diario. La candidata tiene una fila menos en el libro de dinero:
     * así sus contadores no coinciden con los de la base vieja.
     */
    private fun prepare(restores: Boolean, hadActive: Boolean = true) {
        root.deleteRecursively()
        val seed = CandidateFixtures.seed()
        if (hadActive) {
            requireNotNull(files.database.parentFile).mkdirs()
            runBlocking { SnapshotCreator(seed, Dispatchers.IO).create(files.database) }
            runBlocking { open(files.database).close() }
            oldRecords = recordsIn(files.database)
        }
        val candidate = CandidateFixtures.copyOf("startup-restore")
        corrupt(candidate) {
            withoutGuards("money_movements") {
                execSQL(
                    "DELETE FROM money_movements WHERE id = (SELECT MAX(id) FROM money_movements)",
                )
            }
        }
        newRecords = recordsIn(candidate)
        requireNotNull(files.candidate.parentFile).mkdirs()
        check(candidate.renameTo(files.candidate))
        val changeSeq = inspectWithoutRoom(files.candidate) {
            it.longValue(RestoredDatabaseCounters.CHANGE_SEQ_SQL)
        }
        val expected = if (restores) newRecords else newRecords + 1
        DurableFileOps().createMarker(files.lock)
        RestoreJournalStore(files, DurableFileOps()).write(
            RestoreJournal(
                state = RestoreJournalState.PREPARED,
                expectedSha256 = sha256Of(files.candidate),
                expectedChangeSeq = changeSeq,
                expectedRecordCount = expected,
                hadActiveDb = hadActive,
                safetyCopyOk = true,
            ),
        )
    }

    private suspend fun open(file: File): AppDatabase =
        AppDatabase.open(context, file, Dispatchers.IO).also { opened += it }

    private fun gate() = DatabaseGate(
        files = files.databaseFiles,
        schemaVersion = AppDatabase.SCHEMA_VERSION,
        ioContext = Dispatchers.IO,
        log = NO_LOG,
        preMigrationCopy = { PreMigrationResult.Failed },
        pendingRestore =
            StartupRestoreGate(
                StartupRestore(files, DurableFileOps(), SystemClock),
                NO_LOG,
            ) { open ->
                RoomRestoredDatabaseVerifier(open)
            },
    ) { file -> open(file) }

    private fun recordsIn(file: File): Long =
        inspectWithoutRoom(file) { it.longValue(RestoredDatabaseCounters.RECORD_COUNT_SQL) }

    private fun closeAll() {
        opened.forEach { it.close() }
        opened.clear()
    }

    /** La muerte del proceso: hereda de `Error` para que ningún `catch` de excepciones la atrape. */
    private class Death : Error()

    private companion object {
        val NO_LOG =
            object : EventLog {
                override fun write(level: LogLevel, line: LogLine) = Unit
            }
    }
}
