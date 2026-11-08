package io.github.yesidrobayo.shopledger.data.backup

import io.github.yesidrobayo.shopledger.data.crypto.DurableFile
import io.github.yesidrobayo.shopledger.domain.model.backup.ServiceSpace
import java.io.File
import java.io.FileNotFoundException
import kotlin.coroutines.CoroutineContext
import kotlin.time.Instant
import kotlinx.coroutines.withContext

/**
 * Lo que dejó la última sonda de la cabeza terminada, en `no_backup/last-probe`: su instante, en
 * la primera línea, y con WebDAV, si el servicio informó un tope, el espacio libre y el tamaño de
 * la cabeza en la segunda. Pertenece a la instalación y no viaja en una copia.
 *
 * Vencido, abrir la app vuelve a sondear. El espacio es lo que mira el aviso del servicio casi
 * lleno, que el estado calcula sin esperar a la red.
 *
 * Se escribe con temporal, sincronización y renombrado. Un archivo que no se entiende se lee como
 * ausente: la sonda toca otra vez, que es lo prudente.
 *
 * @param file la ruta del archivo, en `noBackupFilesDir`.
 * @param ioContext el contexto de las lecturas y escrituras, fuera del hilo principal.
 */
internal class LastProbeStore(private val file: File, private val ioContext: CoroutineContext) {
    /** La última sonda, o nula si no hubo o el archivo no se entiende. */
    suspend fun load(): LastProbe? = withContext(ioContext) {
        val bytes =
            try {
                file.readBytes()
            } catch (_: FileNotFoundException) {
                return@withContext null
            }
        if (bytes.size > MAX_BYTES) return@withContext null
        val lines = bytes.decodeToString().trimEnd('\n').split('\n')
        val at = lines.first().takeIf(INSTANT::matches)?.toLongOrNull() ?: return@withContext null
        val space =
            lines.getOrNull(1)?.split(' ')?.takeIf { it.size == 2 }?.let { (available, copy) ->
                val free = available.takeIf(COUNT::matches)?.toLongOrNull()
                val size = copy.takeIf(COUNT::matches)?.toLongOrNull()
                if (free != null && size != null) ServiceSpace(free, size) else null
            }
        LastProbe(Instant.fromEpochMilliseconds(at), space)
    }

    /** Deja [probe] como la última sonda terminada. Lanza `IOException` si no quedó en el disco. */
    suspend fun save(probe: LastProbe) = withContext(ioContext) {
        val text = buildString {
            append(probe.at.toEpochMilliseconds()).append('\n')
            probe.serviceSpace?.let {
                append(it.availableBytes).append(' ').append(it.copyBytes).append('\n')
            }
        }
        DurableFile.write(file, text.encodeToByteArray())
    }

    companion object {
        /** El nombre del archivo dentro de `noBackupFilesDir`. */
        const val FILE_NAME = "last-probe"

        /** Dos líneas cortas caben de sobra. */
        private const val MAX_BYTES = 128

        /** Un instante en milisegundos, sin signo ni ceros a la izquierda. */
        private val INSTANT = Regex("0|[1-9][0-9]{0,18}")

        /** Un número de bytes, sin signo ni ceros a la izquierda. */
        private val COUNT = Regex("0|[1-9][0-9]{0,18}")
    }
}

/**
 * Una sonda de la cabeza terminada.
 *
 * @property at cuándo terminó, por el reloj del teléfono.
 * @property serviceSpace con WebDAV, el espacio del servicio, o nulo sin tope informado.
 */
internal data class LastProbe(val at: Instant, val serviceSpace: ServiceSpace?)
