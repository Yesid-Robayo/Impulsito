package io.github.yesidrobayo.shopledger.data.erase

import io.github.yesidrobayo.shopledger.data.backup.DurableFileOps
import io.github.yesidrobayo.shopledger.domain.model.erase.ErasePhase
import java.io.File
import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.withContext

/**
 * El registro de un `Borrar mis datos` confirmado, en `noBackupFilesDir/erase-journal`: una línea
 * con la fase. Se escribe con un temporal, su `fsync`, el renombrado y el `fsync` de la carpeta,
 * así que un corte deja la fase anterior o la nueva, nunca una a medias.
 *
 * Es lo único, junto con `db_initialized`, que el borrado local deja para el final: mientras
 * exista, el borrado se retoma.
 *
 * @param directory la carpeta `noBackupFilesDir`. Pedirla puede tocar el disco.
 */
internal class EraseJournalStore(
    private val directory: () -> File,
    private val ops: DurableFileOps,
    private val ioContext: CoroutineContext,
) {
    /**
     * La fase guardada, o `null` si no hay borrado en curso. Lo que no se entiende, o no se deja
     * leer, cuenta como [ErasePhase.REMOTE]: nunca lleva a borrar lo local sin que la nube se
     * confirme otra vez.
     */
    suspend fun read(): ErasePhase? = withContext(ioContext) {
        val file = File(directory(), FILE_NAME)
        if (!file.exists()) return@withContext null
        val text =
            try {
                file.readText(Charsets.US_ASCII).trim()
            } catch (_: IOException) {
                ""
            }
        if (text == ErasePhase.LOCAL.name) ErasePhase.LOCAL else ErasePhase.REMOTE
    }

    /** Guarda [phase], durable, antes de que el borrado dé el paso siguiente. */
    suspend fun write(phase: ErasePhase) = withContext(ioContext) {
        val folder = directory()
        ops.writeAtomically(
            File(folder, FILE_NAME),
            File(folder, TEMPORARY_NAME),
            phase.name.toByteArray(Charsets.US_ASCII),
        )
    }

    companion object {
        const val FILE_NAME = "erase-journal"
        const val TEMPORARY_NAME = "erase-journal.tmp"
    }
}
