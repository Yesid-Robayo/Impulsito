package io.github.yesidrobayo.shopledger.data.backup

import io.github.yesidrobayo.shopledger.data.destination.newSha256
import io.github.yesidrobayo.shopledger.data.destination.toLowerHex
import io.github.yesidrobayo.shopledger.domain.port.Clock
import java.io.File
import java.io.FileInputStream

/** En qué quedó el reemplazo de la base al arrancar. */
internal sealed interface StartupRestoreResult<out D> {
    /** No había diario. Se limpiaron los restos de una preparación que murió. */
    data object NoRestore : StartupRestoreResult<Nothing>

    /**
     * El candidato está en su sitio y falta verificarlo: calcular su suma y abrirlo. Va fuera del
     * hilo principal, con la app en primer plano.
     */
    data object VerificationPending : StartupRestoreResult<Nothing>

    /**
     * La base nueva quedó verificada en su sitio. [database] es la base abierta con que se
     * verificó, si fue en este proceso. [encryptionChanged] dice si el código de la copia cifrada
     * pasó a ser el vigente, para que quede en la auditoría.
     */
    data class Restored<D>(val database: D?, val encryptionChanged: Boolean) :
        StartupRestoreResult<D>

    /** Volvió la base vieja, o no había ninguna que devolver y la carpeta quedó sin base. */
    data class RolledBack(val databasePresent: Boolean) : StartupRestoreResult<Nothing>

    /**
     * Hay un diario que no se entiende, apartado como `journal.bad` sin tocar ninguna base. Toca el
     * rescate, en cada arranque, hasta que una restauración termine: sin el diario no se sabe si la
     * base de la carpeta de bases es la buena.
     */
    data object UnreadableJournal : StartupRestoreResult<Nothing>
}

/** Abre la base restaurada como lo hace la app, y lee sus dos contadores. */
internal fun interface RestoredDatabaseVerifier<D> {
    suspend fun open(database: File): RestoredDatabaseCheck<D>
}

/** Lo que dio abrir la base restaurada. */
internal sealed interface RestoredDatabaseCheck<out D> {
    /**
     * Abrió, con estos contadores. [database] sigue abierta, como la usará la app; [close] la
     * cierra si los contadores no coinciden.
     */
    data class Opened<D>(
        val changeSeq: Long,
        val recordCount: Long,
        val database: D,
        val close: () -> Unit,
    ) : RestoredDatabaseCheck<D>

    /** No abrió. Nada de la base pasa al resultado. */
    data object Failed : RestoredDatabaseCheck<Nothing>
}

/**
 * La segunda fase del reemplazo de la base, al arrancar, antes de que exista ninguna conexión.
 *
 * Cada paso deja el diario en un estado que garantiza algo en disco, y cada paso se puede repetir:
 * si el proceso muere en cualquier punto, el arranque siguiente retoma desde el diario y termina, o
 * devuelve la base vieja. La base vieja o la nueva siempre quedan completas, y nunca se abre una
 * base que no existe, porque Room la crearía vacía.
 *
 * [runFileSteps] solo renombra y borra: cuesta milisegundos y corre síncrono al crear el proceso.
 * [verifyAndFinish] calcula la suma y abre la base, y por eso va fuera del hilo principal.
 *
 * Al deshacer una restauración, la copia de seguridad que se restaura espera como `undo-source` y
 * no se pierde hasta que el reemplazo queda verificado: si se revierte, o la preparación murió,
 * vuelve a su nombre.
 *
 * Un diario ilegible se aparta como `journal.bad`, y mientras exista, cada arranque va al rescate:
 * podría haber un candidato sin verificar en la carpeta de bases y la base vieja en `previous/`.
 * Solo una restauración que termina lo retira.
 *
 * @param clock da el nombre de la carpeta donde se aparta la base original sin copia de seguridad.
 */
internal class StartupRestore(
    private val files: RestoreFiles,
    private val ops: DurableFileOps,
    clock: Clock,
    private val journalStore: RestoreJournalStore = RestoreJournalStore(files, ops),
) {
    private val damaged = DamagedOriginals(files, ops, clock)

    private var encryptionChanged = false

    /** Avanza el reemplazo hasta donde no haga falta abrir la base. */
    fun runFileSteps(): StartupRestoreResult<Nothing> {
        val journal = when (val read = journalStore.read()) {
            RestoreJournalRead.Absent -> {
                cleanUpWithoutJournal()
                return if (files.journalUnreadable.exists()) {
                    StartupRestoreResult.UnreadableJournal
                } else {
                    StartupRestoreResult.NoRestore
                }
            }

            RestoreJournalRead.Unreadable -> {
                // Sin un diario que se entienda no hay cómo saber qué base es cuál: no se toca
                // ninguna carpeta de bases.
                journalStore.setAsideUnreadable()
                return StartupRestoreResult.UnreadableJournal
            }

            is RestoreJournalRead.Present -> read.journal
        }
        return advance(journal)
    }

    /** Termina el reemplazo, abriendo la base nueva con [verifier] si hace falta verificarla. */
    suspend fun <D> verifyAndFinish(
        verifier: RestoredDatabaseVerifier<D>,
    ): StartupRestoreResult<D> {
        val first = runFileSteps()
        if (first != StartupRestoreResult.VerificationPending) return first
        val swapped = (journalStore.read() as? RestoreJournalRead.Present)?.journal
            ?: return runFileSteps()
        var opened: D? = null
        val next = verify(swapped, verifier) { opened = it }
        return when (val result = advance(next)) {
            is StartupRestoreResult.Restored ->
                StartupRestoreResult.Restored(opened, result.encryptionChanged)

            StartupRestoreResult.NoRestore -> result

            StartupRestoreResult.VerificationPending -> result

            is StartupRestoreResult.RolledBack -> result

            StartupRestoreResult.UnreadableJournal -> result
        }
    }

    /** Sigue el diario hasta un estado final, o hasta que haga falta abrir la base. */
    private fun advance(start: RestoreJournal): StartupRestoreResult<Nothing> {
        var journal = start
        while (true) {
            journal = when (journal.state) {
                RestoreJournalState.PREPARED -> moveOldAside(journal)

                RestoreJournalState.OLD_MOVED -> moveCandidateIn(journal)

                RestoreJournalState.SWAPPED -> return StartupRestoreResult.VerificationPending

                RestoreJournalState.ROLLING_BACK -> rollBack(journal)

                RestoreJournalState.ROLLED_BACK -> {
                    finishRollBack()
                    // Lo que volvió es lo que había antes de esta restauración: con un diario
                    // ilegible anterior, tampoco se sabe si es la base buena.
                    return if (files.journalUnreadable.exists()) {
                        StartupRestoreResult.UnreadableJournal
                    } else {
                        StartupRestoreResult.RolledBack(files.database.exists())
                    }
                }

                RestoreJournalState.DONE -> {
                    finishRestore(journal)
                    return StartupRestoreResult.Restored(null, encryptionChanged)
                }
            }
        }
    }

    private fun transition(journal: RestoreJournal, state: RestoreJournalState): RestoreJournal =
        journal.copy(state = state).also(journalStore::write)

    /** Aparta la base vieja con su `-wal` y su `-shm`, si el candidato está listo. */
    private fun moveOldAside(journal: RestoreJournal): RestoreJournal {
        if (!files.candidate.exists()) return transition(journal, RestoreJournalState.ROLLING_BACK)
        // El principal sale primero. El -wal viejo puede traer datos confirmados: viaja con él.
        ops.move(files.database, files.previous)
        ops.move(files.databaseWal, files.previousWal)
        ops.move(files.databaseShm, files.previousShm)
        return transition(journal, RestoreJournalState.OLD_MOVED)
    }

    /** Pone el candidato en su sitio. Si ya no está en su carpeta pero sí la base, ya se movió. */
    private fun moveCandidateIn(journal: RestoreJournal): RestoreJournal = when {
        files.candidate.exists() -> {
            ops.move(files.candidate, files.database)
            transition(journal, RestoreJournalState.SWAPPED)
        }

        files.database.exists() -> transition(journal, RestoreJournalState.SWAPPED)

        else -> transition(journal, RestoreJournalState.ROLLING_BACK)
    }

    /**
     * Comprueba la base nueva: antes de abrirla, que el archivo exista y su suma; después, los dos
     * contadores. Si algo falla, cierra la base y escribe la reversión antes de borrar nada.
     */
    private suspend fun <D> verify(
        journal: RestoreJournal,
        verifier: RestoredDatabaseVerifier<D>,
        keep: (D) -> Unit,
    ): RestoreJournal {
        if (!files.database.exists()) return transition(journal, RestoreJournalState.ROLLING_BACK)
        var current = journal
        if (!current.shaVerified) {
            if (sha256Of(files.database) != current.expectedSha256) {
                return transition(current, RestoreJournalState.ROLLING_BACK)
            }
            current = current.copy(shaVerified = true).also(journalStore::write)
        }
        return when (val check = verifier.open(files.database)) {
            RestoredDatabaseCheck.Failed -> transition(current, RestoreJournalState.ROLLING_BACK)

            is RestoredDatabaseCheck.Opened -> {
                val matches =
                    check.changeSeq == current.expectedChangeSeq &&
                        check.recordCount == current.expectedRecordCount
                if (matches) {
                    keep(check.database)
                    transition(current, RestoreJournalState.DONE)
                } else {
                    // Se cierra antes de escribir la reversión: la reversión borra sus archivos.
                    check.close()
                    transition(current, RestoreJournalState.ROLLING_BACK)
                }
            }
        }
    }

    /**
     * Devuelve la base vieja. El orden es la clave: su principal sale primero y vuelve de último, así
     * que su presencia en cada carpeta dice hasta dónde se llegó, sin más estados.
     */
    private fun rollBack(journal: RestoreJournal): RestoreJournal {
        if (files.previous.exists()) {
            // Con la vieja aún en previous/, lo que hay en la carpeta de bases es el candidato, y
            // ningún -wal viejo volvió todavía: borrar es seguro. Su principal va de último.
            if (files.database.exists()) deleteActiveDatabaseFiles()
            ops.move(files.previousWal, files.databaseWal)
            ops.move(files.previousShm, files.databaseShm)
            ops.move(files.previous, files.database)
        } else if (!journal.hadActiveDb) {
            // No había nada que devolver: lo que haya en la carpeta de bases es el candidato.
            deleteActiveDatabaseFiles()
        }
        // Un código que no llegó a usarse no reemplaza al vigente.
        ops.delete(files.pendingKey)
        return transition(journal, RestoreJournalState.ROLLED_BACK)
    }

    private fun deleteActiveDatabaseFiles() {
        ops.delete(files.databaseWal)
        ops.delete(files.databaseShm)
        ops.delete(files.databaseRollbackJournal)
        ops.delete(files.database)
    }

    private fun finishRollBack() {
        ops.delete(files.candidate)
        ops.delete(files.pendingKey)
        returnUndoSource(files, ops)
        ops.delete(files.lock)
        journalStore.delete()
    }

    /** Limpia lo que queda del reemplazo verificado. El diario se borra de último. */
    private fun finishRestore(journal: RestoreJournal) {
        if (journal.safetyCopyOk) {
            ops.delete(files.previous)
            ops.delete(files.previousWal)
            ops.delete(files.previousShm)
        } else {
            // Sin copia de seguridad, la base original se aparta 30 días en vez de borrarse, en su
            // propia carpeta: nunca pisa la de una restauración anterior.
            damaged.setAsidePrevious()
        }
        ops.delete(files.candidate)
        ops.createMarker(files.initializedMarker)
        // El código de una copia cifrada solo pasa a vigente si la base nueva quedó en su sitio.
        if (ops.move(files.pendingKey, files.sealedKey, replace = true)) encryptionChanged = true
        // El origen de un deshacer ya no hace falta: el reemplazo quedó verificado.
        ops.delete(files.undoSource.database)
        for (copy in files.safetyCopies) {
            if (!copy.database.exists()) ops.delete(copy.facts)
        }
        // La base nueva quedó verificada: un diario ilegible anterior ya no dice nada de ella. Su
        // copia para soporte quedó junto a la base que se apartó al empezar.
        ops.delete(files.journalUnreadable)
        ops.delete(files.lock)
        journalStore.delete()
    }

    /** Sin diario, la preparación murió antes de terminar: la base activa no se tocó. */
    private fun cleanUpWithoutJournal() {
        // Un temporal del diario sin renombrar no dice nada.
        ops.delete(files.journalTemporary)
        ops.delete(files.candidateTemporary)
        ops.delete(files.candidate)
        ops.delete(files.pendingKey)
        ops.delete(files.incoming)
        // La copia de seguridad a medio escribir nunca tuvo nombre final.
        ops.delete(files.safetyCopyTemporary)
        ops.delete(
            RestoreFiles.sideFile(files.safetyCopyTemporary, RestoreFiles.ROLLBACK_JOURNAL_SUFFIX),
        )
        returnUndoSource(files, ops)
        ops.delete(files.lockTemporary)
        ops.delete(files.lock)
    }
}

/** La suma SHA-256 de [file], en hexadecimal en minúscula, leída en flujo. */
internal fun sha256Of(file: File): String {
    val digest = newSha256()
    FileInputStream(file).use { input ->
        val buffer = ByteArray(BUFFER_BYTES)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().toLowerHex()
}

private const val BUFFER_BYTES = 64 * 1024
