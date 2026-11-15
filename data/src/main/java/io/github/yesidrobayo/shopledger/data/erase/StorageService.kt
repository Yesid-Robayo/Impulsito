package io.github.yesidrobayo.shopledger.data.erase

import io.github.yesidrobayo.shopledger.data.local.DatabaseFiles
import io.github.yesidrobayo.shopledger.data.local.backup.BackupStateDao
import io.github.yesidrobayo.shopledger.domain.model.erase.EraseSummary
import io.github.yesidrobayo.shopledger.domain.model.erase.ErasePhase
import io.github.yesidrobayo.shopledger.domain.model.erase.StorageUsage
import io.github.yesidrobayo.shopledger.domain.port.StoragePort
import java.io.File
import java.nio.file.Files
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.withContext

/**
 * Lo que mide `Almacenamiento y borrado`, siempre en [ioContext]. Solo lee tamaños y conteos:
 * nunca un nombre de archivo ni un dato del negocio sale de aquí. Lo que no se deja medir queda en
 * `null`, y la fila lo dice. Nunca sigue un enlace simbólico.
 *
 * @param state el estado del respaldo de la base abierta, o `null` si no se lee.
 * @param photosPending las fotos sin respaldar en el servicio WebDAV, o `null` si no aplica.
 * @param providerLabel el nombre de la nube del teléfono, con ranuras.
 */
internal class StorageService(
    private val files: () -> DatabaseFiles,
    private val mediaDirectory: () -> File,
    private val appFolders: () -> List<File>,
    private val state: suspend () -> BackupStateDao?,
    private val photosPending: suspend () -> Int?,
    private val providerLabel: suspend () -> String?,
    private val pending: suspend () -> ErasePhase?,
    private val restoreInProgress: suspend () -> Boolean,
    private val ioContext: CoroutineContext,
) : StoragePort {
    override suspend fun usage(): StorageUsage = withContext(ioContext) {
        val database = files()
        val noBackup = database.noBackupDirectory
        val media = mediaDirectory()
        val safety = safetyCopies(noBackup)
        StorageUsage(
            businessBytes =
                measured {
                    DATABASE_SUFFIXES.sumOf { File(database.database.path + it).length() }
                },
            photoBytes = measured { sizeOf(media, emptySet()) },
            safetyCopyBytes = measured { safety.sumOf { sizeOf(it, emptySet()) } },
            otherBytes =
                measured {
                    val skipped = safety.toSet() + media
                    sizeOf(noBackup, skipped) + appFolders().sumOf { sizeOf(it, skipped) }
                },
            freeBytes = measured { noBackup.usableSpace },
        )
    }

    override suspend fun summary(): EraseSummary? {
        val dao = state() ?: return null
        val row = dao.get() ?: return null
        val photos = withContext(ioContext) { measured { countOf(mediaDirectory()) } ?: 0L }
        return EraseSummary(
            records = dao.recordCount(),
            products = dao.catalogCount(),
            photos = photos.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            unsyncedChanges = (row.changeSeq - row.verifiedSeq).coerceAtLeast(0),
            photosPending = photosPending(),
            destination = eraseDestinationOf(row),
            providerLabel = providerLabel(),
            backupFailed = row.failureCode != null,
            restoreInProgress = restoreInProgress(),
            pending = pending(),
        )
    }

    private companion object {
        val DATABASE_SUFFIXES = listOf("", "-wal", "-shm")

        /** Las copias de seguridad: la de restaurar, la previa a migrar y la base dañada. */
        fun safetyCopies(noBackup: File): List<File> {
            val restore = File(noBackup, "restore")
            val copies =
                restore.listFiles()?.filter { it.name.startsWith("safety-copy") }.orEmpty()
            return copies + File(noBackup, "pre-migration") + File(restore, "damaged-original")
        }

        /** Lo que pesa [file], sin lo de [skipped] y sin seguir enlaces. */
        fun sizeOf(file: File, skipped: Set<File>): Long = when {
            file in skipped || Files.isSymbolicLink(file.toPath()) -> 0L

            file.isDirectory -> file.listFiles().orEmpty().sumOf { sizeOf(it, skipped) }

            else -> file.length()
        }

        /** Cuántos archivos hay en [folder] y sus carpetas. */
        fun countOf(folder: File): Long =
            folder.listFiles().orEmpty().sumOf { if (it.isDirectory) countOf(it) else 1L }

        /** [block], o `null` si el sistema no deja leer. */
        inline fun measured(block: () -> Long): Long? = try {
            block()
        } catch (_: SecurityException) {
            null
        }
    }
}
