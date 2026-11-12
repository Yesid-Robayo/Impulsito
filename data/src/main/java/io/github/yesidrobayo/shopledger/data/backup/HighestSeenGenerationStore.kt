package io.github.yesidrobayo.shopledger.data.backup

import io.github.yesidrobayo.shopledger.data.crypto.DurableFile
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * La mayor generación de copia que este teléfono vio en el destino conectado, en
 * `no_backup/highest-seen-generation`, en una sola línea.
 *
 * Es el ancla del aviso `Faltan copias más recientes`: si la mayor que se ve ahora es menor, alguien
 * pudo borrar las recientes. Solo avisa, nunca rechaza una copia. Se conserva al desconectar, para
 * que reconectar el mismo destino siga avisando, y se olvida al confirmar otro destino, con lo demás
 * que el teléfono recordaba del anterior. Pertenece a la instalación: en un teléfono nuevo no hay.
 *
 * Nunca baja. Un archivo que no se lee como generación es como no tener ancla: nunca inventa una.
 *
 * @param directory la carpeta `noBackupFilesDir`. Se pide en [ioContext], porque toca el disco.
 */
internal class HighestSeenGenerationStore(
    private val directory: () -> File,
    private val ioContext: CoroutineContext,
) {
    private val mutex = Mutex()

    private fun file() = File(directory(), FILE_NAME)

    /**
     * La generación guardada, o nula si no hay ancla.
     *
     * @throws IOException si el archivo existe y no se pudo leer.
     */
    suspend fun load(): Long? = withContext(ioContext) { loadNow() }

    /**
     * Sube el ancla a [generation] si es mayor que la guardada, y devuelve la que había antes. Una
     * generación de cero o menos no dice nada y no se guarda.
     */
    suspend fun raise(generation: Long): Long? = mutex.withLock {
        withContext(ioContext) {
            val current = loadNow()
            if (generation > 0 && (current == null || generation > current)) {
                DurableFile.write(file(), "$generation\n".encodeToByteArray())
            }
            current
        }
    }

    private fun loadNow(): Long? {
        val bytes =
            try {
                file().readBytes()
            } catch (_: FileNotFoundException) {
                return null
            }
        if (bytes.size > MAX_BYTES) return null
        return bytes.decodeToString().trimEnd('\n').takeIf(GENERATION::matches)?.toLongOrNull()
    }

    companion object {
        /** El nombre del archivo dentro de `noBackupFilesDir`. */
        const val FILE_NAME = "highest-seen-generation"

        /** Una generación con su salto de línea cabe de sobra. */
        private const val MAX_BYTES = 64

        /** Hasta 19 cifras ASCII, sin signo ni ceros a la izquierda, desde 1. */
        private val GENERATION = Regex("[1-9][0-9]{0,18}")
    }
}
