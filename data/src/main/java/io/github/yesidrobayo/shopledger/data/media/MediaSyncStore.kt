package io.github.yesidrobayo.shopledger.data.media

import io.github.yesidrobayo.shopledger.data.crypto.DurableFile
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.withContext

/**
 * Lo que `media-sync` sabe del servicio, en `noBackupFilesDir`, fuera de la base: la base no guarda
 * rutas ni nada del destino, y restaurar una copia no debe cambiarlo.
 *
 * - `media-sync.txt`: las sumas de las fotos ya verificadas en el servicio, una por línea. Si se
 *   pierde, o el destino cambia, se reconstruye listando `media/`.
 * - `media-sync-stop`: por qué se detuvo la última ejecución, si fue por algo que la persona debe
 *   saber, como el espacio del servicio. Sin archivo, no se detuvo.
 *
 * Los dos se escriben con un temporal y un renombrado: un corte nunca los deja a medias.
 *
 * @param directory `noBackupFilesDir`.
 */
internal class MediaSyncStore(
    private val directory: File,
    private val ioContext: CoroutineContext,
) {
    private val verifiedFile = File(directory, FILE_NAME)
    private val stopFile = File(directory, STOP_FILE_NAME)

    /**
     * Las sumas verificadas, o `null` si el archivo no está: entonces hay que listar el servicio.
     * Una línea que no es una suma se ignora: solo un disco dañado la dejaría así.
     */
    suspend fun verified(): Set<String>? = withContext(ioContext) {
        val bytes =
            try {
                verifiedFile.readBytes()
            } catch (_: FileNotFoundException) {
                return@withContext null
            }
        if (bytes.size > MAX_BYTES) throw IOException("El estado de las fotos es demasiado grande")
        bytes.decodeToString().lineSequence().filter(::isSha256).toHashSet()
    }

    /** Guarda [sums] como las verificadas, reemplazando lo anterior. */
    suspend fun saveVerified(sums: Set<String>) = withContext(ioContext) {
        val text = sums.sorted().joinToString(separator = "") { it + "\n" }
        DurableFile.write(verifiedFile, text.encodeToByteArray())
    }

    /** Olvida lo verificado, por ejemplo al cambiar de destino: se reconstruye listando. */
    suspend fun forget() = withContext(ioContext) {
        if (!DurableFile.delete(verifiedFile)) throw IOException("No se pudo borrar el estado")
    }

    /** Por qué se detuvo la última ejecución, o `null` si no se detuvo. */
    suspend fun stop(): MediaSyncStop? = withContext(ioContext) {
        val text =
            try {
                stopFile.readText().trim()
            } catch (_: FileNotFoundException) {
                return@withContext null
            }
        MediaSyncStop.entries.firstOrNull { it.name == text }
    }

    /** Anota [stop], o con `null` que ya no está detenida. */
    suspend fun setStop(stop: MediaSyncStop?) = withContext(ioContext) {
        if (stop == null) {
            if (!DurableFile.delete(stopFile)) throw IOException("No se pudo borrar el estado")
        } else if (stopFile.takeIf { it.isFile }?.readText()?.trim() != stop.name) {
            DurableFile.write(stopFile, stop.name.encodeToByteArray())
        }
    }

    companion object {
        /** El nombre del archivo de las sumas verificadas. No cambia nunca. */
        const val FILE_NAME = "media-sync.txt"

        /** El nombre del archivo de la última parada. No cambia nunca. */
        const val STOP_FILE_NAME = "media-sync-stop"

        /**
         * Tope del archivo: 65 bytes por foto. Alcanza para unas 64.000, muy por encima del aviso de
         * 1 GiB de fotos.
         */
        private const val MAX_BYTES = 4 * 1024 * 1024

        private const val SHA256_LENGTH = 64

        fun isSha256(text: String): Boolean =
            text.length == SHA256_LENGTH && text.all { it in '0'..'9' || it in 'a'..'f' }
    }
}

/** Por qué `media-sync` se detuvo sin terminar, para decírselo a la persona. */
internal enum class MediaSyncStop {
    /** En el servicio caben menos de 3 copias: las fotos nunca desplazan a la base. */
    SERVICE_SPACE,
}
