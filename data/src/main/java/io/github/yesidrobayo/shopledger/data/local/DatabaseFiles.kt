package io.github.yesidrobayo.shopledger.data.local

import android.content.Context
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Dónde vive la base del negocio, y la marca de que ya existió. Es el único lugar que nombra su
 * archivo: el respaldo, la restauración y la exportación lo piden aquí, y así nunca tocan otra base
 * por error.
 *
 * **La marca.** Room crea una base vacía si no encuentra el archivo. Eso está bien la primera vez,
 * y es el peor fallo posible después: si el archivo de un negocio desaparece, por ejemplo tras una
 * restauración cortada, Room lo reemplazaría por uno vacío sin decir nada, y la copia siguiente
 * subiría ese vacío. Por eso, en cuanto la base abre por primera vez, aquí se deja [initializedMarker]
 * en `noBackupFilesDir`, una carpeta que el sistema nunca respalda ni transfiere. Si la marca
 * existe y el archivo falta, [state] lo dice, y la compuerta del arranque lleva a la persona al
 * rescate sin abrir Room.
 *
 * Ninguna función lee el disco al crear la clase. [state] y [markInitialized] sí lo tocan, y solo
 * se llaman fuera del hilo principal.
 *
 * @param database ruta absoluta del archivo de la base.
 * @param noBackupDirectory carpeta de la marca; en la app, `noBackupFilesDir`.
 */
internal class DatabaseFiles(val database: File, val noBackupDirectory: File) {
    init {
        require(database.isAbsolute) { "La ruta de la base debe ser absoluta" }
    }

    /** La marca de que la base ya existió. Vacía: solo cuenta que exista. */
    val initializedMarker: File = File(noBackupDirectory, INITIALIZED_MARKER_NAME)

    /** La carpeta de la copia previa a migrar. Nunca se sube. */
    val preMigrationDirectory: File = File(noBackupDirectory, PRE_MIGRATION_DIRECTORY_NAME)

    /** La copia previa a migrar: la última, entera y comprobada. */
    val preMigrationCandidate: File = File(preMigrationDirectory, PRE_MIGRATION_CANDIDATE_NAME)

    /** El temporal de la copia previa, mientras se escribe. Nunca tiene el nombre final. */
    val preMigrationTemporary: File = File(preMigrationDirectory, PRE_MIGRATION_TEMPORARY_NAME)

    /** Qué hay en disco antes de abrir la base. Lee el disco. */
    fun state(): DatabaseFileState = when {
        database.exists() -> DatabaseFileState.PRESENT
        initializedMarker.exists() -> DatabaseFileState.MISSING
        else -> DatabaseFileState.NEVER_CREATED
    }

    /**
     * Deja la marca, si todavía no está, y la hace durable: el archivo se sincroniza con el disco, y
     * después la carpeta, porque el nombre nuevo vive en ella y sin eso un corte de energía podría
     * borrarlo. Se llama cada vez que la base abre: así también se deja si el proceso murió entre
     * crear la base y escribir la marca.
     */
    fun markInitialized() {
        if (initializedMarker.isFile) return
        val directory =
            requireNotNull(initializedMarker.parentFile) { "La marca siempre tiene carpeta" }
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            throw IOException("No se pudo crear la carpeta de la marca")
        }
        FileOutputStream(initializedMarker).use { it.fd.sync() }
        syncDirectory(directory)
    }

    companion object {
        /** El nombre del archivo de la base, en la carpeta de bases de la app. */
        const val DATABASE_NAME = "shopledger.db"

        /** El nombre de la marca, en `noBackupFilesDir`. */
        const val INITIALIZED_MARKER_NAME = "db_initialized"

        /** La carpeta de la copia previa a migrar, en `noBackupFilesDir`. */
        const val PRE_MIGRATION_DIRECTORY_NAME = "pre-migration"
        const val PRE_MIGRATION_CANDIDATE_NAME = "candidate.db"
        const val PRE_MIGRATION_TEMPORARY_NAME = "candidate.tmp"

        /**
         * La ruta absoluta de la base en [context]. Toca el disco: Android crea la carpeta de bases
         * si falta, así que solo se pide fuera del hilo principal.
         */
        fun databaseFile(context: Context): File =
            context.getDatabasePath(DATABASE_NAME).absoluteFile

        /** Los archivos de la base de la app. Toca el disco, como [databaseFile]. */
        fun of(context: Context): DatabaseFiles =
            DatabaseFiles(databaseFile(context), context.noBackupFilesDir)
    }
}

/** Qué hay en disco antes de abrir la base. */
internal enum class DatabaseFileState {
    /** El archivo está. Todavía no se sabe si abre: eso lo dice su `user_version`. */
    PRESENT,

    /** Instalación nueva: la base nunca existió, y crearla es lo correcto. */
    NEVER_CREATED,

    /**
     * La base existió y ya no está. Abrir Room crearía una vacía encima del negocio: toca el
     * rescate, sin abrir nada.
     */
    MISSING,
}

/**
 * Sincroniza [directory] con el disco: un archivo nuevo o un renombrado viven en la carpeta, no en
 * el archivo, y no sobreviven a un corte de energía hasta sincronizarla.
 */
internal fun syncDirectory(directory: File) {
    val descriptor = Os.open(directory.path, OsConstants.O_RDONLY, 0)
    try {
        Os.fsync(descriptor)
    } finally {
        Os.close(descriptor)
    }
}
