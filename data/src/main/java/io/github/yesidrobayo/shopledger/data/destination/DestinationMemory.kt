package io.github.yesidrobayo.shopledger.data.destination

import io.github.yesidrobayo.shopledger.data.backup.BackupStaging
import io.github.yesidrobayo.shopledger.data.backup.DamagedGenerationStore
import io.github.yesidrobayo.shopledger.data.backup.FullVerifyGenerationStore
import io.github.yesidrobayo.shopledger.data.backup.LastProbeStore
import io.github.yesidrobayo.shopledger.data.backup.RejectedGenerationStore
import io.github.yesidrobayo.shopledger.data.crypto.DurableFile
import java.io.File
import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlin.time.Instant
import kotlinx.coroutines.withContext

/**
 * Lo que el teléfono recuerda de un destino fuera de la base, en `noBackupFilesDir`: la última
 * sonda, las generaciones rechazada, dañada y leída entera, y la copia que se estaba subiendo.
 *
 * Al cambiar de destino se olvida todo: una generación del destino anterior no dice nada del nuevo,
 * y una copia armada para el anterior no debe subir al nuevo.
 *
 * @param directory la carpeta de esos archivos. Se pide en [ioContext], porque pedirla toca el disco.
 */
internal class DestinationMemory(
    private val directory: () -> File,
    private val ioContext: CoroutineContext,
) {
    /** Borra lo recordado del destino anterior. Devuelve `false` si algo quedó. */
    suspend fun forget(now: Instant): Boolean = withContext(ioContext) {
        val dir = directory()
        val removed =
            FILES.all { name ->
                try {
                    DurableFile.delete(File(dir, name))
                } catch (_: IOException) {
                    false
                }
            }
        // La copia a medio subir era para el destino anterior: ninguna se conserva.
        BackupStaging(File(dir, BackupStaging.DIRECTORY_NAME), ioContext).cleanUp(null, now)
        removed
    }

    companion object {
        /** Los archivos que se olvidan, todos dentro de [directory]. */
        val FILES =
            listOf(
                LastProbeStore.FILE_NAME,
                RejectedGenerationStore.FILE_NAME,
                DamagedGenerationStore.FILE_NAME,
                FullVerifyGenerationStore.FILE_NAME,
            )
    }
}
