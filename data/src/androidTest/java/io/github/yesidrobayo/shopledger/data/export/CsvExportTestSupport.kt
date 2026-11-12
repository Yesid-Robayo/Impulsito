package io.github.yesidrobayo.shopledger.data.export

import io.github.yesidrobayo.shopledger.data.destination.newSha256
import io.github.yesidrobayo.shopledger.data.destination.toLowerHex
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipInputStream

// Apoyo de las pruebas de la exportación CSV. Lee los archivos como una hoja de cálculo: comillas
// dobles, comillas internas duplicadas y separador fuera de las comillas.

/** Los archivos del `.zip` en [file], por nombre y en su orden, como texto UTF-8. */
internal fun unzipCsv(file: File): Map<String, String> {
    val files = linkedMapOf<String, String>()
    ZipInputStream(FileInputStream(file)).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            files[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
        }
    }
    return files
}

/** Las líneas de un archivo sin saltos dentro de las celdas, sin el fin de la última. */
internal fun csvLines(content: String): List<String> = content.removeSuffix("\r\n").split("\r\n")

/**
 * Los registros de un archivo, cada uno con sus celdas tal como están escritas, comillas incluidas.
 * Un salto de línea o un [separator] dentro de comillas no corta nada.
 */
internal fun csvRecords(content: String, separator: Char = ';'): List<List<String>> {
    val records = mutableListOf<List<String>>()
    var cells = mutableListOf<String>()
    val current = StringBuilder()
    var quoted = false
    var index = 0
    while (index < content.length) {
        val char = content[index]
        when {
            char == '"' -> {
                quoted = !quoted
                current.append(char)
            }

            quoted -> current.append(char)

            char == separator -> {
                cells += current.toString()
                current.clear()
            }

            char == '\r' && content.getOrNull(index + 1) == '\n' -> {
                cells += current.toString()
                current.clear()
                records += cells
                cells = mutableListOf()
                index++
            }

            else -> current.append(char)
        }
        index++
    }
    if (current.isNotEmpty() || cells.isNotEmpty()) records += cells + current.toString()
    return records
}

/** Las celdas de una sola línea, separadas fuera de las comillas. */
internal fun csvCells(line: String, separator: Char = ';'): List<String> =
    csvRecords("$line\r\n", separator).single()

/** Un documento en un archivo de la caché, con fallos inventados al escribir o al releer. */
internal class FileDocument(
    private val file: File,
    private val failWrite: IOException? = null,
    private val rereadAs: ByteArray? = null,
) : CsvDocument {
    var writes = 0
    var discards = 0

    override suspend fun write(block: suspend (OutputStream) -> Unit): WrittenDocument {
        writes += 1
        file.outputStream().use { block(it) }
        failWrite?.let { throw it }
        return WrittenDocument(file.length(), newSha256().digest(file.readBytes()).toLowerHex())
    }

    override fun read(): InputStream = rereadAs?.let(::ByteArrayInputStream) ?: file.inputStream()

    override fun discard() {
        discards += 1
        file.delete()
    }
}
