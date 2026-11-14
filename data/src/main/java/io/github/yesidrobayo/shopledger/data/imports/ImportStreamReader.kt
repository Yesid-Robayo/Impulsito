package io.github.yesidrobayo.shopledger.data.imports

import io.github.yesidrobayo.shopledger.domain.model.imports.ImportCharSource
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportFileRead
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportLimits
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportRejection
import io.github.yesidrobayo.shopledger.domain.model.settings.CsvSeparator
import io.github.yesidrobayo.shopledger.domain.rule.imports.CsvSheetReader
import java.io.BufferedInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Lee los bytes de un archivo para importar, con su tope de tamaño, y los entrega al lector de su
 * tipo. El tipo sale de los primeros bytes, nunca del nombre: el nombre lo escribe cualquiera.
 *
 * - **Tope.** Nunca se piden al archivo más de [ImportLimits.maxFileBytes] más un byte: el que
 *   demuestra que lo supera. Así un archivo que no declara su tamaño, o que miente, se corta
 *   igual.
 * - **Codificación.** Solo UTF-8, con o sin marca de orden de bytes, que es lo que escribe la
 *   exportación y lo que ofrecen las hojas de cálculo. Un byte que no es UTF-8 rechaza el archivo:
 *   leerlo con otra tabla rompería tildes y eñes sin avisar, y la persona lo puede guardar de
 *   nuevo.
 * - **Hojas de cálculo.** Un ZIP, que es lo que es un `.xlsx`, va entero a [XlsxSheetReader], ya
 *   acotado por el tope del archivo. El `.xls` antiguo no se lee.
 */
internal class ImportStreamReader(private val limits: ImportLimits = ImportLimits()) {
    /**
     * Lee [stream] entero, o hasta el primer rechazo. [checkActive] se llama antes de cada bloque,
     * para que cancelar corte la lectura. No cierra [stream]: es de quien lo abrió.
     */
    fun read(
        stream: InputStream,
        separator: CsvSeparator?,
        preferred: CsvSeparator,
        onRows: (Int) -> Unit,
        checkActive: () -> Unit,
    ): ImportFileRead = try {
        val input = BufferedInputStream(BoundedStream(stream, limits.maxFileBytes), BUFFER_BYTES)
        when (kindOf(input)) {
            Kind.TEXT -> {
                val decoder =
                    StandardCharsets.UTF_8
                        .newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                val chars = ReaderSource(InputStreamReader(input, decoder), checkActive)
                CsvSheetReader(limits).read(chars, separator, preferred, onRows)
            }

            Kind.UTF16 -> ImportFileRead.Rejected(ImportRejection.ENCODING)

            Kind.SPREADSHEET ->
                XlsxSheetReader(limits).read(input.readBytes(), onRows, checkActive)

            Kind.OLD_SPREADSHEET -> ImportFileRead.Rejected(ImportRejection.UNSUPPORTED_TYPE)
        }
    } catch (tooLarge: TooLargeException) {
        ImportFileRead.Rejected(ImportRejection.TOO_LARGE)
    } catch (encoding: CharacterCodingException) {
        ImportFileRead.Rejected(ImportRejection.ENCODING)
    } catch (unreadable: IOException) {
        ImportFileRead.Rejected(ImportRejection.UNREADABLE)
    }

    /** El tipo por los primeros bytes, que se dejan en [input] para quien lea después. */
    private fun kindOf(input: BufferedInputStream): Kind {
        input.mark(MAGIC_BYTES)
        val head = ByteArray(MAGIC_BYTES)
        var size = 0
        while (size < MAGIC_BYTES) {
            val read = input.read(head, size, MAGIC_BYTES - size)
            if (read < 0) break
            size += read
        }
        input.reset()
        val start = head.copyOf(size)
        return when {
            startsWith(start, ZIP) || startsWith(start, EMPTY_ZIP) -> Kind.SPREADSHEET
            startsWith(start, COMPOUND_FILE) -> Kind.OLD_SPREADSHEET
            startsWith(start, UTF16_LITTLE) || startsWith(start, UTF16_BIG) -> Kind.UTF16
            else -> Kind.TEXT
        }
    }

    private fun startsWith(bytes: ByteArray, prefix: IntArray): Boolean =
        bytes.size >= prefix.size && prefix.indices.all { bytes[it] == prefix[it].toByte() }

    private enum class Kind { TEXT, UTF16, SPREADSHEET, OLD_SPREADSHEET }

    /** Los caracteres de [reader] de a bloques, con [checkActive] antes de cada uno. */
    private class ReaderSource(private val reader: Reader, private val checkActive: () -> Unit) :
        ImportCharSource {
        private val buffer = CharArray(BUFFER_CHARS)
        private var size = 0
        private var index = 0

        override fun read(): Int {
            if (index == size) {
                checkActive()
                size = maxOf(reader.read(buffer), 0)
                index = 0
                if (size == 0) return ImportCharSource.END
            }
            return buffer[index++].code
        }
    }

    /**
     * Deja pasar como máximo [max] bytes más uno de [source]. Al pasar el tope lanza
     * [TooLargeException], y nunca pide de más: cada lectura se recorta a lo que falta.
     */
    private class BoundedStream(source: InputStream, private val max: Long) :
        FilterInputStream(source) {
        private var count = 0L

        override fun read(): Int {
            val byte = super.read()
            if (byte >= 0) counted(1)
            return byte
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val allowed = minOf(length.toLong(), max - count + 1).toInt()
            val read = super.read(buffer, offset, allowed)
            if (read > 0) counted(read)
            return read
        }

        override fun skip(n: Long): Long = throw IOException("Saltar bytes no está permitido")

        override fun markSupported(): Boolean = false

        private fun counted(bytes: Int) {
            count += bytes
            if (count > max) throw TooLargeException()
        }
    }

    /** El archivo pasó su tope de bytes. */
    private class TooLargeException : IOException()

    private companion object {
        const val BUFFER_BYTES = 64 * 1024
        const val BUFFER_CHARS = 16 * 1024
        const val MAGIC_BYTES = 4

        /** Encabezado de una entrada ZIP, como las de un `.xlsx`: `PK\3\4`. */
        val ZIP = intArrayOf(0x50, 0x4B, 0x03, 0x04)

        /** Encabezado de un ZIP vacío: `PK\5\6`. */
        val EMPTY_ZIP = intArrayOf(0x50, 0x4B, 0x05, 0x06)

        /** Encabezado del formato compuesto del `.xls` antiguo. */
        val COMPOUND_FILE = intArrayOf(0xD0, 0xCF, 0x11, 0xE0)

        /** Marcas de orden de bytes de UTF-16, el "texto Unicode" que también ofrece Excel. */
        val UTF16_LITTLE = intArrayOf(0xFF, 0xFE)
        val UTF16_BIG = intArrayOf(0xFE, 0xFF)
    }
}
