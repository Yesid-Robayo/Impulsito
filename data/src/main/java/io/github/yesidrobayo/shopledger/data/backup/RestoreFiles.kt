package io.github.yesidrobayo.shopledger.data.backup

import io.github.yesidrobayo.shopledger.data.local.DatabaseFiles
import java.io.File

/**
 * Las rutas de la base activa y de la restauración. Todas son fijas: el nombre de una copia nunca
 * se usa como ruta.
 *
 * La carpeta de bases y `noBackupFilesDir` están en el mismo sistema de archivos del almacenamiento
 * interno, así que mover un archivo entre ellas es un renombrado atómico. `noBackupFilesDir` nunca
 * se respalda ni se transfiere a otro teléfono.
 *
 * @param databaseFiles la base del negocio y la marca de que ya existió.
 */
internal class RestoreFiles(val databaseFiles: DatabaseFiles) {
    /** La base activa y los archivos que SQLite crea a su lado. */
    val database: File = databaseFiles.database
    val databaseWal: File = sideFile(database, WAL_SUFFIX)
    val databaseShm: File = sideFile(database, SHM_SUFFIX)
    val databaseRollbackJournal: File = sideFile(database, ROLLBACK_JOURNAL_SUFFIX)

    /** La marca de que la base ya existió. Sin ella y sin base, la app arranca vacía. */
    val initializedMarker: File = databaseFiles.initializedMarker

    val restoreDirectory: File = File(databaseFiles.noBackupDirectory, "restore")

    /** El cerrojo de la preparación: mientras exista, nada escribe ni sube. */
    val lock: File = File(restoreDirectory, "in_progress")

    /** El temporal del cerrojo, mientras se escribe. */
    val lockTemporary: File = File(restoreDirectory, "in_progress.tmp")

    val journal: File = File(restoreDirectory, "journal.json")
    val journalTemporary: File = File(restoreDirectory, "journal.tmp")

    /** Un diario que no se entiende, apartado para soporte sin tocar ninguna base. */
    val journalUnreadable: File = File(restoreDirectory, "journal.bad")

    /** La copia tal como llegó, antes de descomprimirla. */
    val incoming: File = File(restoreDirectory, "incoming.db.gz")

    val candidateDirectory: File = File(restoreDirectory, "candidate")
    val candidate: File = File(candidateDirectory, "candidate.db")

    /** El temporal de la candidata, mientras se copia. Nunca tiene el nombre final. */
    val candidateTemporary: File = File(candidateDirectory, "candidate.db.tmp")

    /** Donde espera la base vieja mientras se verifica la nueva. */
    val previousDirectory: File = File(restoreDirectory, "previous")
    val previous: File = File(previousDirectory, DatabaseFiles.DATABASE_NAME)
    val previousWal: File = sideFile(previous, WAL_SUFFIX)
    val previousShm: File = sideFile(previous, SHM_SUFFIX)

    /**
     * Las bases originales que se apartaron porque no hubo copia de seguridad, una carpeta por
     * restauración. Se conservan 30 días.
     */
    val damagedDirectory: File = File(restoreDirectory, "damaged-original")

    /** Las copias locales de seguridad: la última y, si guardaba cambios sin subir, la anterior. */
    val safetyCopies: List<SafetyCopyFiles> =
        listOf(
            SafetyCopyFiles(restoreDirectory, "safety-copy"),
            SafetyCopyFiles(restoreDirectory, "safety-copy-1"),
        )

    /** El temporal de la copia de seguridad, mientras se escribe. Nunca tiene un nombre final. */
    val safetyCopyTemporary: File = File(restoreDirectory, "safety-copy.tmp")

    /**
     * El origen de un deshacer: la copia de seguridad que se está restaurando, apartada con otro
     * nombre hasta que el reemplazo quede verificado.
     */
    val undoSource: SafetyCopyFiles = SafetyCopyFiles(restoreDirectory, "undo-source")

    val recoveryDirectory: File = File(databaseFiles.noBackupDirectory, "recovery")
    val sealedKey: File = File(recoveryDirectory, "rk.sealed")
    val pendingKey: File = File(recoveryDirectory, "rk.pending")

    companion object {
        const val WAL_SUFFIX = "-wal"
        const val SHM_SUFFIX = "-shm"
        const val ROLLBACK_JOURNAL_SUFFIX = "-journal"

        /** Un archivo que SQLite crea al lado de [database], con su nombre y un sufijo. */
        fun sideFile(database: File, suffix: String): File = File(database.path + suffix)
    }
}

/**
 * Una copia local de seguridad: la base, y a su lado lo que guardaba al hacerse.
 *
 * @property database la base, completa y comprobada.
 * @property facts `change_seq`, `verified_seq` e instante de la copia.
 */
internal class SafetyCopyFiles(directory: File, name: String) {
    val database: File = File(directory, "$name.db")
    val facts: File = File(directory, "$name.json")
    val factsTemporary: File = File(directory, "$name.json.tmp")
}
