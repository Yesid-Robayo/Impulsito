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
 * - `media-sync.txt`: las sumas de las fotos ya verificadas en el servicio, una por línea, con el
 *   `key_id` detrás si están cifradas. Si se pierde, o el destino cambia, se reconstruye listando
 *   `media/`.
 * - `media-sync-stop`: por qué se detuvo la última ejecución, si fue por algo que la persona debe
 *   saber, como el espacio del servicio. Sin archivo, no se detuvo.
 * - `media-unavailable.txt`: las fotos en uso que faltan en el teléfono y que el servicio no tiene,
 *   o tiene dañadas. La pantalla dice `Foto no disponible` en vez de `Foto pendiente`.
 * - `media-delete.txt`: las fotos que la limpieza ya borró del teléfono y falta borrar del
 *   servicio.
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
    private val unavailableFile = File(directory, UNAVAILABLE_FILE_NAME)
    private val deletionsFile = File(directory, DELETIONS_FILE_NAME)

    /**
     * Las fotos verificadas con su forma, o `null` si el archivo no está: entonces hay que listar el
     * servicio. Una línea que no se entiende se ignora: solo un disco dañado la dejaría así.
     */
    suspend fun verifiedForms(): Map<String, MediaForm>? = withContext(ioContext) {
        val text = readSmall(verifiedFile) ?: return@withContext null
        text.lineSequence().mapNotNull(::parseLine).toMap()
    }

    /** Las sumas verificadas en cualquier forma, o `null` si hay que listar el servicio. */
    suspend fun verified(): Set<String>? = verifiedForms()?.keys

    /** Guarda [forms] como lo verificado, reemplazando lo anterior. */
    suspend fun saveVerifiedForms(forms: Map<String, MediaForm>) = withContext(ioContext) {
        val text =
            forms.entries.sortedBy { it.key }.joinToString(separator = "") { (sha, form) ->
                val suffix = MediaForm.encode(form)
                if (suffix.isEmpty()) "$sha\n" else "$sha $suffix\n"
            }
        DurableFile.write(verifiedFile, text.encodeToByteArray())
    }

    /** Guarda [sums] como verificadas en claro, reemplazando lo anterior. */
    suspend fun saveVerified(sums: Set<String>) =
        saveVerifiedForms(sums.associateWith { MediaForm.Plain })

    /** Olvida lo verificado, por ejemplo al cambiar de destino: se reconstruye listando. */
    suspend fun forget() = withContext(ioContext) {
        if (!DurableFile.delete(verifiedFile)) throw IOException("No se pudo borrar el estado")
    }

    /** Las fotos que el servicio no tiene, o tiene dañadas. Sin archivo, ninguna. */
    suspend fun unavailable(): Set<String> = readSums(unavailableFile)

    /** Guarda [sums] como las fotos no disponibles. Vacío borra el archivo. */
    suspend fun saveUnavailable(sums: Set<String>) = writeSums(unavailableFile, sums)

    /** Las fotos que falta borrar del servicio. Sin archivo, ninguna. */
    suspend fun deletions(): Set<String> = readSums(deletionsFile)

    /** Guarda [sums] como las fotos que falta borrar del servicio. Vacío borra el archivo. */
    suspend fun saveDeletions(sums: Set<String>) = writeSums(deletionsFile, sums)

    private suspend fun readSums(file: File): Set<String> = withContext(ioContext) {
        readSmall(file)?.lineSequence()?.filter(::isSha256)?.toHashSet().orEmpty()
    }

    private suspend fun writeSums(file: File, sums: Set<String>) = withContext(ioContext) {
        if (sums.isEmpty()) {
            if (!DurableFile.delete(file)) throw IOException("No se pudo borrar el estado")
        } else {
            val text = sums.sorted().joinToString(separator = "") { "$it\n" }
            DurableFile.write(file, text.encodeToByteArray())
        }
    }

    /** El texto de [file], o `null` si no está. Con más de [MAX_BYTES], el disco está dañado. */
    private fun readSmall(file: File): String? {
        val bytes =
            try {
                file.readBytes()
            } catch (_: FileNotFoundException) {
                return null
            }
        if (bytes.size > MAX_BYTES) throw IOException("El estado de las fotos es demasiado grande")
        return bytes.decodeToString()
    }

    private fun parseLine(line: String): Pair<String, MediaForm>? {
        val sha = line.substringBefore(' ')
        if (!isSha256(sha)) return null
        val form = MediaForm.decode(line.substringAfter(' ', missingDelimiterValue = ""))
        return form?.let { sha to it }
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

        /** El nombre del archivo de las fotos que el servicio no tiene. No cambia nunca. */
        const val UNAVAILABLE_FILE_NAME = "media-unavailable.txt"

        /** El nombre del archivo de las fotos que falta borrar del servicio. No cambia nunca. */
        const val DELETIONS_FILE_NAME = "media-delete.txt"

        /**
         * Tope de cada archivo: hasta 82 bytes por foto, con el `key_id`. Alcanza para unas 50.000,
         * muy por encima del aviso de 1 GiB de fotos.
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
