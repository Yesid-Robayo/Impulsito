package io.github.yesidrobayo.shopledger.data.backup

import io.github.yesidrobayo.shopledger.data.crypto.DurableFile
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.withContext

/**
 * Las generaciones propias que nunca se confirmaron y que la app descartó: una lectura que no
 * coincidió, o una subida que quedó en el destino con otro tamaño. Vive en
 * `no_backup/rejected-generation`, una por línea.
 *
 * Se escribe **antes** de borrar esa copia del destino, con la misma durabilidad que el estado de
 * las ranuras: si el borrado falla, o el proceso muere a mitad, la marca ya está en disco. Mientras
 * una generación esté aquí, la recuperación borra esa copia y nunca la adopta, la guarda no la
 * cuenta y la generación nueva no la repite. Sin esto, una copia que falló la lectura completa
 * podría confirmarse después por una muestra, que no ve el centro del archivo.
 *
 * La línea sale cuando la copia y sus metadatos ya no están en el destino.
 *
 * Solo cubre copias de este teléfono: las de otro nunca se borran.
 *
 * @param file la ruta del archivo, en `noBackupFilesDir`.
 * @param ioContext el contexto de las lecturas y escrituras, fuera del hilo principal.
 */
internal class RejectedGenerationStore(
    private val file: File,
    private val ioContext: CoroutineContext,
) {
    /**
     * Las generaciones rechazadas. Sin archivo, ninguna. Una línea que no es una generación se
     * ignora: el archivo es de la app, y solo un disco dañado la dejaría así.
     *
     * @throws IOException si el archivo existe y no se pudo leer. Quien llama no debe seguir como
     *   si no hubiera ninguna: podría adoptar una copia rechazada.
     */
    suspend fun load(): Set<Long> = withContext(ioContext) {
        val bytes =
            try {
                file.readBytes()
            } catch (_: FileNotFoundException) {
                return@withContext emptySet()
            }
        if (bytes.size > MAX_BYTES) throw IOException("El archivo de rechazos es demasiado grande")
        bytes
            .decodeToString()
            .lineSequence()
            .mapNotNull(::parseGeneration)
            .toSortedSet()
    }

    /** Agrega [generation], en disco, antes de borrar su copia del destino. */
    suspend fun add(generation: Long) {
        require(generation > 0) { "La generación de una copia empieza en 1" }
        val current = load()
        if (generation !in current) save(current + generation)
    }

    /** Quita [generation]: su copia y sus metadatos ya no están en el destino. */
    suspend fun remove(generation: Long) {
        val current = load()
        if (generation in current) save(current - generation)
    }

    private suspend fun save(generations: Set<Long>) = withContext(ioContext) {
        if (generations.isEmpty()) {
            if (!DurableFile.delete(file)) throw IOException("No se pudo borrar el archivo")
        } else {
            val text = generations.sorted().joinToString(separator = "\n", postfix = "\n")
            DurableFile.write(file, text.encodeToByteArray())
        }
    }

    companion object {
        /** El nombre del archivo dentro de `noBackupFilesDir`. */
        const val FILE_NAME = "rejected-generation"

        /**
         * Tope del archivo. Cada línea son a lo sumo 20 bytes, y solo crece mientras el destino no
         * deja borrar: miles de líneas ya son un archivo dañado.
         */
        private const val MAX_BYTES = 64 * 1024

        /** Hasta 19 cifras ASCII, sin signo ni ceros a la izquierda, desde 1. */
        private val GENERATION = Regex("[1-9][0-9]{0,18}")

        private fun parseGeneration(line: String): Long? =
            line.takeIf(GENERATION::matches)?.toLongOrNull()
    }
}
