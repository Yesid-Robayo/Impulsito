package io.github.yesidrobayo.shopledger.data.imports

import io.github.yesidrobayo.shopledger.domain.model.imports.ImportFileRead
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportLimits
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportRejection
import io.github.yesidrobayo.shopledger.domain.model.settings.CsvSeparator
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Los bytes de un archivo para importar: el tope de tamaño sin leer de más, el tipo por los
 * primeros bytes, la codificación y la cancelación. Archivos escritos a mano y generados en
 * memoria.
 */
class ImportStreamReaderTest {
    @Test
    fun `a utf8 file with accents reads as text`() {
        val bytes = "\uFEFFnome;preço\r\nMaçã;1,50\r\nPiña;2,00\r\n".toByteArray(Charsets.UTF_8)

        val read = read(bytes) as ImportFileRead.Read

        assertEquals(listOf("nome", "preço"), read.sheet.header)
        assertEquals(listOf("Maçã", "1,50"), read.sheet.rows[0].cells)
        assertEquals(listOf("Piña", "2,00"), read.sheet.rows[1].cells)
    }

    @Test
    fun `a file without a byte order mark also reads`() {
        val read = read("a,b\n1,2\n".toByteArray(Charsets.UTF_8), separator = null)

        assertEquals(CsvSeparator.COMMA, (read as ImportFileRead.Read).sheet.separator)
    }

    @Test
    fun `a byte that is not utf8 rejects the file`() {
        // "Piña" guardado con la página de códigos de Windows: la ñ es un solo byte, 0xF1.
        val legacy = byteArrayOf(0x61, 0x0A, 0x50, 0x69, 0xF1.toByte(), 0x61, 0x0A)

        assertEquals(rejected(ImportRejection.ENCODING), read(legacy))
    }

    @Test
    fun `a sequence cut at the end of the file rejects it`() {
        val cut = "a\nPi".toByteArray(Charsets.UTF_8) + byteArrayOf(0xC3.toByte())

        assertEquals(rejected(ImportRejection.ENCODING), read(cut))
    }

    @Test
    fun `utf16 text is rejected as an encoding to save again`() {
        val little =
            byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "a\n1\n".toByteArray(Charsets.UTF_16LE)
        val big =
            byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + "a\n1\n".toByteArray(Charsets.UTF_16BE)

        assertEquals(rejected(ImportRejection.ENCODING), read(little))
        assertEquals(rejected(ImportRejection.ENCODING), read(big))
    }

    @Test
    fun `a broken zip or an old spreadsheet is not read`() {
        val zip = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x14, 0x00)
        val emptyZip = byteArrayOf(0x50, 0x4B, 0x05, 0x06) + ByteArray(18)
        val compound = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0x00)

        listOf(zip, emptyZip, compound).forEach { bytes ->
            assertEquals(rejected(ImportRejection.UNSUPPORTED_TYPE), read(bytes))
        }
    }

    @Test
    fun `a short text file is shorter than any magic number`() {
        assertEquals(rejected(ImportRejection.EMPTY), read("P".toByteArray(Charsets.UTF_8)))
        assertEquals(rejected(ImportRejection.EMPTY), read(ByteArray(0)))
    }

    @Test
    fun `a file over the size limit is cut one byte past it`() {
        val limits = ImportLimits(maxFileBytes = 1_000)
        val endless = EndlessRows()

        val read =
            ImportStreamReader(limits).read(endless, null, CsvSeparator.SEMICOLON, {}, {})

        assertEquals(rejected(ImportRejection.TOO_LARGE), read)
        assertEquals(1_001L, endless.served)
    }

    @Test
    fun `a file exactly at the limit is read`() {
        val bytes = "a\n".toByteArray(Charsets.UTF_8) + "1".repeat(998).toByteArray(Charsets.UTF_8)
        val limits = ImportLimits(maxFileBytes = bytes.size.toLong())

        val read = ImportStreamReader(
            limits,
        ).read(ByteArrayInputStream(bytes), null, CsvSeparator.SEMICOLON, {
        }, {})

        assertTrue(read is ImportFileRead.Read)
    }

    @Test
    fun `a stream that reads one byte at a time is counted too`() {
        val limits = ImportLimits(maxFileBytes = 5)

        val read =
            ImportStreamReader(
                limits,
            ).read(OneByteAtATime("a\n12345\n"), null, CsvSeparator.SEMICOLON, {
            }, {})

        assertEquals(rejected(ImportRejection.TOO_LARGE), read)
    }

    @Test
    fun `a failing stream is unreadable`() {
        val failing =
            object : InputStream() {
                override fun read(): Int = throw IOException("sin documento")
            }

        assertEquals(rejected(ImportRejection.UNREADABLE), read(failing))
    }

    @Test
    fun `cancelling stops the reading`() {
        val stop = IllegalStateException("cancelado")

        val thrown =
            assertThrows(IllegalStateException::class.java) {
                ImportStreamReader().read(EndlessRows(), null, CsvSeparator.SEMICOLON, {
                }) { throw stop }
            }

        assertEquals(stop, thrown)
    }

    @Test
    fun `the display name drops control and format characters and is bounded`() {
        assertEquals(
            "productos.csv",
            DocumentImportReader.displayName(" produc\u0000tos\u202E.csv\n"),
        )
        assertEquals(120, DocumentImportReader.displayName("x".repeat(500))?.length)
        assertNull(DocumentImportReader.displayName(" \u200B "))
        assertNull(DocumentImportReader.displayName(null))
    }

    private fun read(bytes: ByteArray, separator: CsvSeparator? = CsvSeparator.SEMICOLON) =
        read(ByteArrayInputStream(bytes), separator)

    private fun read(stream: InputStream, separator: CsvSeparator? = CsvSeparator.SEMICOLON) =
        ImportStreamReader().read(stream, separator, CsvSeparator.SEMICOLON, {}, {})

    private fun rejected(reason: ImportRejection) = ImportFileRead.Rejected(reason)

    /** Filas sin fin, que cuentan cuántos bytes entregaron. */
    private class EndlessRows : InputStream() {
        private val row = "1;2\n".toByteArray(Charsets.UTF_8)
        var served = 0L
            private set

        override fun read(): Int = row[(served++ % row.size).toInt()].toInt()

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            for (i in 0 until length) buffer[offset + i] = read().toByte()
            return length
        }
    }

    /** Un flujo que entrega un byte por lectura, como algunos proveedores. */
    private class OneByteAtATime(text: String) : InputStream() {
        private val bytes = text.toByteArray(Charsets.UTF_8)
        private var index = 0

        override fun read(): Int = if (index < bytes.size) bytes[index++].toInt() and 0xFF else -1

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            val byte = read()
            if (byte < 0) return -1
            buffer[offset] = byte.toByte()
            return 1
        }
    }
}
