package io.github.yesidrobayo.shopledger.data.rescue

import io.github.yesidrobayo.shopledger.data.backup.DamagedOriginals
import io.github.yesidrobayo.shopledger.data.backup.DurableFileOps
import io.github.yesidrobayo.shopledger.data.backup.RestoreFiles
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationKind
import io.github.yesidrobayo.shopledger.domain.port.BackupDestinationSource
import io.github.yesidrobayo.shopledger.domain.port.BackupRestorePort
import io.github.yesidrobayo.shopledger.domain.port.Clock
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.withContext

/**
 * Lo que la pantalla de rescate usa sin abrir la base.
 *
 * - **El destino guardado.** Sale de `link.json`, que no lleva secretos, con la credencial sellada
 *   o las ranuras de `slots.json`: la lista y restaurar funcionan sin volver a conectar.
 * - **Restaurar.** La misma preparación de la pantalla del respaldo, que ya funciona sin la base.
 * - **La base dañada.** La que una restauración apartó sin copia de seguridad. Se puede empaquetar
 *   para compartirla con quien ayude a rescatarla, solo por decisión de la persona.
 *
 * Nada de esto abre la base, ni lee `app_settings` ni `backup_state`.
 *
 * @property destination el destino de `link.json` y sus copias.
 * @property restore restaurar una copia de ese destino o un archivo elegido.
 */
class RescueData internal constructor(
    val destination: BackupDestinationSource,
    val restore: BackupRestorePort,
    private val savedKind: suspend () -> BackupDestinationKind?,
    private val restoreFiles: () -> RestoreFiles,
    private val clock: Clock,
    private val ioContext: CoroutineContext,
) {
    /** El tipo del destino de `link.json`, o nulo si no hay ninguno guardado. Lee el disco. */
    suspend fun savedDestinationKind(): BackupDestinationKind? = savedKind()

    /** Si hay una base dañada apartada por una restauración. Lee el disco. */
    suspend fun hasDamagedOriginal(): Boolean = withContext(ioContext) { newestDamaged() != null }

    /**
     * Empaqueta en [target] la base dañada más reciente, con sus archivos de al lado, para
     * compartirla. Nunca la mueve ni la cambia: solo la lee. Escribe un temporal y lo renombra, así
     * que [target] nunca queda a medias. Devuelve `false` si no hay ninguna o no se pudo escribir,
     * y entonces no deja nada.
     *
     * El nombre de cada archivo dentro del paquete es el que le dio la app al apartarlo.
     */
    suspend fun exportDamagedOriginal(target: File): Boolean = withContext(ioContext) {
        val folder = newestDamaged() ?: return@withContext false
        val temporary = File(target.path + TEMPORARY_SUFFIX)
        try {
            temporary.delete()
            val parts = folder.listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name }
            ZipOutputStream(FileOutputStream(temporary)).use { zip ->
                for (file in parts) {
                    zip.putNextEntry(ZipEntry(file.name))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            if (!temporary.renameTo(target)) throw IOException("No se pudo renombrar el paquete")
            true
        } catch (_: IOException) {
            target.delete()
            false
        } finally {
            temporary.delete()
        }
    }

    private fun newestDamaged(): File? =
        DamagedOriginals(restoreFiles(), DurableFileOps(), clock).folders().lastOrNull()

    private companion object {
        const val TEMPORARY_SUFFIX = ".tmp"
    }
}
