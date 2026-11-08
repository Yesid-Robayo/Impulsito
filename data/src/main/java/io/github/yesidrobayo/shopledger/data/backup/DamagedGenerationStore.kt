package io.github.yesidrobayo.shopledger.data.backup

import io.github.yesidrobayo.shopledger.data.crypto.DurableFile
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.withContext

/**
 * Las generaciones **ya confirmadas** que una lectura completa no pudo comprobar, en
 * `no_backup/damaged-generation`, una por línea. Solo con WebDAV: con la nube del teléfono, el
 * estado de las ranuras guarda la copia como no válida.
 *
 * Una lectura nunca borra una copia confirmada: quizá falló la lectura, o un validador nuevo. La
 * copia sigue en el destino y cuenta para no pisar lo que hay, pero la poda la trata como no
 * válida: no ocupa un día y no deja borrar otras. La línea sale cuando otra lectura completa la
 * comprueba, o cuando esa copia ya no está en el destino.
 *
 * @param file la ruta del archivo, en `noBackupFilesDir`.
 * @param ioContext el contexto de las lecturas y escrituras, fuera del hilo principal.
 */
internal class DamagedGenerationStore(
    private val file: File,
    private val ioContext: CoroutineContext,
) {
    /**
     * Las generaciones dañadas. Sin archivo, ninguna. Una línea que no es una generación se
     * ignora: el archivo es de la app, y solo un disco dañado la dejaría así.
     *
     * @throws IOException si el archivo existe y no se pudo leer. Quien llama no debe seguir como
     *   si no hubiera ninguna: la poda contaría como válida una copia dañada.
     */
    suspend fun load(): Set<Long> = withContext(ioContext) {
        val bytes =
            try {
                file.readBytes()
            } catch (_: FileNotFoundException) {
                return@withContext emptySet()
            }
        if (bytes.size >
            MAX_BYTES
        ) {
            throw IOException("El archivo de copias dañadas es demasiado grande")
        }
        bytes
            .decodeToString()
            .lineSequence()
            .mapNotNull(::parseGeneration)
            .toSortedSet()
    }

    /** Agrega [generation]: una lectura completa no pudo comprobar esa copia confirmada. */
    suspend fun add(generation: Long) {
        require(generation > 0) { "La generación de una copia empieza en 1" }
        val current = load()
        if (generation !in current) save(current + generation)
    }

    /** Quita [generation]: otra lectura completa la comprobó. */
    suspend fun remove(generation: Long) = removeAll(setOf(generation))

    /** Quita [generations]: ya no están en el destino, o se comprobaron. */
    suspend fun removeAll(generations: Set<Long>) {
        val current = load()
        if (current.any { it in generations }) save(current - generations)
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
        const val FILE_NAME = "damaged-generation"

        /**
         * Tope del archivo. Cada línea son a lo sumo 20 bytes, y las copias del destino son unas
         * pocas: miles de líneas ya son un archivo dañado.
         */
        private const val MAX_BYTES = 64 * 1024

        /** Hasta 19 cifras ASCII, sin signo ni ceros a la izquierda, desde 1. */
        private val GENERATION = Regex("[1-9][0-9]{0,18}")

        private fun parseGeneration(line: String): Long? =
            line.takeIf(GENERATION::matches)?.toLongOrNull()
    }
}
