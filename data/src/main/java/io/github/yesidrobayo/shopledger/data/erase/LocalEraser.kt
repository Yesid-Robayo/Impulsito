package io.github.yesidrobayo.shopledger.data.erase

import io.github.yesidrobayo.shopledger.data.backup.DurableFileOps
import io.github.yesidrobayo.shopledger.data.local.DatabaseFiles
import io.github.yesidrobayo.shopledger.domain.port.EventLog
import io.github.yesidrobayo.shopledger.domain.port.LogEvent
import io.github.yesidrobayo.shopledger.domain.port.info
import io.github.yesidrobayo.shopledger.domain.port.warn
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

/**
 * Borra todo lo de este teléfono, en el orden que deja cualquier corte en un estado que se retoma:
 *
 * 1. Suelta el acceso al destino: la credencial sellada con sus claves de Keystore, y los permisos
 *    de las ranuras. La contraseña sigue valiendo en el servicio: se anula allí.
 * 2. La base, con su `-wal`, su `-shm` y su `-journal`.
 * 3. Lo que hay en las carpetas de la app, como `media/` y los temporales de `cache/`.
 * 4. Todo `noBackupFilesDir`: `link.json`, `slots.json`, `recovery/`, `crash/`, las copias previas
 *    y de seguridad, `device_id` y lo demás, salvo el registro del borrado y `db_initialized`.
 * 5. El registro del borrado.
 * 6. `db_initialized`, de último: un corte justo antes lleva a la pantalla de rescate, nunca a una
 *    base nueva sobre datos a medio borrar.
 *
 * Cada paso se salta si ya no hay nada: repetirlo tras una muerte del proceso sigue donde quedó.
 * La base debe estar cerrada. Nunca sigue un enlace simbólico fuera de las carpetas de la app.
 *
 * @param files los archivos de la base y `noBackupFilesDir`. Pedirlos toca el disco.
 * @param appFolders las carpetas propias de la app cuyo contenido se borra, como `filesDir` y
 *   `cacheDir`. Las carpetas quedan, vacías.
 * @param releaseAccess suelta la credencial y los permisos. Una falla ahí no detiene el borrado:
 *   el sobre de la credencial se borra igual con `noBackupFilesDir`.
 */
internal class LocalEraser(
    private val files: () -> DatabaseFiles,
    private val appFolders: () -> List<File>,
    private val ops: DurableFileOps,
    private val releaseAccess: suspend () -> Unit,
    private val ioContext: CoroutineContext,
    private val log: EventLog,
) {
    /** `true` si no quedó nada; `false` si algo no se pudo borrar, y el registro sigue. */
    suspend fun erase(): Boolean {
        try {
            releaseAccess()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            log.warn(EraseEvent.ACCESS_NOT_RELEASED, failure)
        }
        return withContext(ioContext) {
            try {
                val files = files()
                for (suffix in DATABASE_SUFFIXES) ops.delete(File(files.database.path + suffix))
                for (folder in appFolders()) clearContents(folder, keep = emptySet())
                val noBackup = files.noBackupDirectory
                clearContents(noBackup, keep = KEPT_UNTIL_THE_END)
                ops.delete(File(noBackup, EraseJournalStore.TEMPORARY_NAME))
                ops.delete(File(noBackup, EraseJournalStore.FILE_NAME))
                ops.delete(files.initializedMarker)
                log.info(EraseEvent.LOCAL_ERASED)
                true
            } catch (failure: IOException) {
                log.warn(EraseEvent.LOCAL_NOT_ERASED, failure)
                false
            } catch (failure: SecurityException) {
                log.warn(EraseEvent.LOCAL_NOT_ERASED, failure)
                false
            }
        }
    }

    /** Borra lo que hay dentro de [folder], salvo los nombres de [keep], y deja la carpeta. */
    private fun clearContents(folder: File, keep: Set<String>) {
        val children = folder.listFiles() ?: return
        for (child in children.sortedBy { it.name }) {
            if (child.name !in keep) deleteTree(child)
        }
    }

    /** Borra [file] y, si es una carpeta de verdad, lo que tiene dentro. Un enlace, solo él. */
    private fun deleteTree(file: File) {
        if (file.isDirectory && !Files.isSymbolicLink(file.toPath())) {
            file.listFiles()?.sortedBy { it.name }?.forEach(::deleteTree)
        }
        ops.delete(file)
    }

    private companion object {
        val DATABASE_SUFFIXES = listOf("", "-wal", "-shm", "-journal")

        /** Lo que se borra al final, en su orden: el registro y la marca de la base. */
        val KEPT_UNTIL_THE_END =
            setOf(
                EraseJournalStore.FILE_NAME,
                EraseJournalStore.TEMPORARY_NAME,
                DatabaseFiles.INITIALIZED_MARKER_NAME,
            )
    }
}

/** Lo que registra `Borrar mis datos`: estados y clases de falla, nunca rutas ni nombres. */
internal enum class EraseEvent(override val text: String) : LogEvent {
    REMOTE_FINISHED("Borrado de las copias del destino terminado"),
    REMOTE_FAILED("No se pudo borrar en el destino; este teléfono no se tocó"),
    ACCESS_NOT_RELEASED("No se pudo soltar el acceso al destino; se borra con lo local"),
    LOCAL_ERASED("Este teléfono quedó borrado"),
    LOCAL_NOT_ERASED("No se pudo terminar el borrado local; se retoma"),
    RESUMED_AT_START("Borrado local retomado al abrir"),
}
