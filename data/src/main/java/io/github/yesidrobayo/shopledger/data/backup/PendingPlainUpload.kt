package io.github.yesidrobayo.shopledger.data.backup

import io.github.yesidrobayo.shopledger.data.crypto.DurableFile
import io.github.yesidrobayo.shopledger.data.local.backup.BackupStateDao
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.withContext

/**
 * Descarta la subida pendiente de una copia en claro, al activar el cifrado: con el cifrado activo,
 * nada en claro debe salir del teléfono, tampoco una copia que ya estaba en camino.
 *
 * Corre bajo el cerrojo del respaldo, así que ninguna ejecución la está subiendo. En orden:
 *
 * 1. Marca la generación como rechazada, antes de borrar nada: si quedó entera o a medias en el
 *    destino, la recuperación la borra allí y nunca la adopta.
 * 2. Borra el `.db.gz` y sus metadatos de la carpeta de preparación.
 * 3. Olvida la intención de subida.
 *
 * Una muerte entre pasos deja la marca, que es lo que protege: la ejecución siguiente borra lo que
 * quede y no reintenta esa copia. Si la retomara igual, la recuperación de la subida tampoco
 * retoma una copia en claro con el cifrado activo.
 */
internal class PendingPlainUpload(
    private val state: suspend () -> BackupStateDao?,
    private val staging: suspend () -> BackupStaging,
    private val rejected: suspend () -> RejectedGenerationStore,
    private val ioContext: CoroutineContext,
) {
    /** Devuelve la generación descartada, o `null` si no había una subida en claro pendiente. */
    suspend fun discard(): Long? {
        val dao = state() ?: return null
        val generation = dao.get()?.pendingGeneration ?: return null
        val folder = staging()
        // Una copia cifrada que espera, de un código anterior, puede salir: no es texto plano.
        if (withContext(ioContext) { folder.encryptedFile(generation).isFile }) return null
        rejected().add(generation)
        withContext(ioContext) {
            DurableFile.delete(folder.compressedFile(generation))
            DurableFile.delete(folder.metaFile(generation))
        }
        dao.clearIntent()
        return generation
    }
}
