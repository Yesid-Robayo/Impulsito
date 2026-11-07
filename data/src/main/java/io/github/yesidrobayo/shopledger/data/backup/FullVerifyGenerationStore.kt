package io.github.yesidrobayo.shopledger.data.backup

import io.github.yesidrobayo.shopledger.data.crypto.DurableFile
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.withContext

/**
 * La generación de la última copia que se leyó entera del destino y cuya suma coincidió, en
 * `no_backup/full-verify-generation`, en una sola línea.
 *
 * La poda nunca borra esa copia: con red solo medida, las demás se comprobaron por una muestra, que
 * no ve el centro del archivo. Pertenece a la instalación y no viaja en una copia.
 *
 * Nunca baja: una lectura que termina tarde no quita su lugar a una copia más nueva ya leída
 * entera.
 *
 * @param file la ruta del archivo, en `noBackupFilesDir`.
 * @param ioContext el contexto de las lecturas y escrituras, fuera del hilo principal.
 */
internal class FullVerifyGenerationStore(
    private val file: File,
    private val ioContext: CoroutineContext,
) {
    /**
     * La generación guardada, o nula si no hay archivo o lo que tiene no es una generación: la poda
     * lo trata como que ninguna copia se leyó entera.
     *
     * @throws IOException si el archivo existe y no se pudo leer.
     */
    suspend fun load(): Long? = withContext(ioContext) {
        val bytes =
            try {
                file.readBytes()
            } catch (_: FileNotFoundException) {
                return@withContext null
            }
        if (bytes.size > MAX_BYTES) return@withContext null
        bytes.decodeToString().trimEnd('\n').takeIf(GENERATION::matches)?.toLongOrNull()
    }

    /** Deja [generation] como la última leída entera, salvo que ya haya una más nueva. */
    suspend fun advanceTo(generation: Long) {
        require(generation > 0) { "La generación de una copia empieza en 1" }
        val current = load()
        if (current != null && current >= generation) return
        withContext(ioContext) { DurableFile.write(file, "$generation\n".encodeToByteArray()) }
    }

    companion object {
        /** El nombre del archivo dentro de `noBackupFilesDir`. */
        const val FILE_NAME = "full-verify-generation"

        /** Una generación con su salto de línea cabe de sobra. */
        private const val MAX_BYTES = 64

        /** Hasta 19 cifras ASCII, sin signo ni ceros a la izquierda, desde 1. */
        private val GENERATION = Regex("[1-9][0-9]{0,18}")
    }
}
