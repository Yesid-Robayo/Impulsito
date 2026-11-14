package io.github.yesidrobayo.shopledger.domain.rule.imports

import io.github.yesidrobayo.shopledger.domain.model.imports.ImportCharSource
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportFileRead
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportLimits
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportRejection
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportRow
import io.github.yesidrobayo.shopledger.domain.model.settings.CsvSeparator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El lector de CSV para importar, con archivos escritos a mano: el formato de las hojas de
 * cálculo, la elección del separador, las filas en blanco y cada rechazo, con topes pequeños para
 * llegar a ellos sin archivos enormes.
 */
class CsvSheetReaderTest {
    private val reader = CsvSheetReader()

    @Test
    fun `an exported file reads back cell by cell`() {
        val text = "\uFEFF\"name\";\"sale_price\"\r\n" +
            "\"Arroz 1 kg\";12,50\r\n\"Café \"\"molido\"\"\";3,00\r\n"

        val sheet = sheetOf(text, CsvSeparator.SEMICOLON)

        assertEquals(listOf("name", "sale_price"), sheet.header)
        assertEquals(
            listOf(
                ImportRow(2, listOf("Arroz 1 kg", "12,50")),
                ImportRow(3, listOf("Café \"molido\"", "3,00")),
            ),
            sheet.rows,
        )
        assertEquals(CsvSeparator.SEMICOLON, sheet.separator)
    }

    @Test
    fun `a quoted cell keeps its line breaks and separators`() {
        val text = "a,b\n\"uno\ndos\",\"x,y\"\n\"tres\r\ncuatro\",z"

        val sheet = sheetOf(text, CsvSeparator.COMMA)

        assertEquals(
            listOf(
                ImportRow(2, listOf("uno\ndos", "x,y")),
                ImportRow(3, listOf("tres\r\ncuatro", "z")),
            ),
            sheet.rows,
        )
    }

    @Test
    fun `old mac line ends and a missing last line end are accepted`() {
        val sheet = sheetOf("a;b\r1;2\r3;4", CsvSeparator.SEMICOLON)

        assertEquals(listOf(listOf("1", "2"), listOf("3", "4")), sheet.rows.map { it.cells })
    }

    @Test
    fun `blank rows are skipped but keep the row numbers of the spreadsheet`() {
        val text = "\n;;\n\"a\";\"b\"\n\n\"  \";\n1;2\n;;\n"

        val sheet = sheetOf(text, CsvSeparator.SEMICOLON)

        assertEquals(listOf("a", "b"), sheet.header)
        assertEquals(listOf(ImportRow(6, listOf("1", "2"))), sheet.rows)
    }

    @Test
    fun `rows may be shorter or longer than the header`() {
        val sheet = sheetOf("a;b;c\n1\n1;2;3;4\n1;\n", CsvSeparator.SEMICOLON)

        assertEquals(listOf("1"), sheet.rows[0].cells)
        assertEquals("", sheet.rows[0].cell(2))
        assertEquals("", sheet.rows[0].cell(-1))
        assertEquals("4", sheet.rows[1].cell(3))
        assertEquals(listOf("1", ""), sheet.rows[2].cells)
    }

    @Test
    fun `cells are kept as written, formulas included`() {
        val text = "a;b\n  =1+1 ;\"@SUM(A1)\"\n'0123;-5\n"

        val sheet = sheetOf(text, CsvSeparator.SEMICOLON)

        assertEquals(listOf("  =1+1 ", "@SUM(A1)"), sheet.rows[0].cells)
        assertEquals(listOf("'0123", "-5"), sheet.rows[1].cells)
    }

    @Test
    fun `a quote inside an unquoted cell is text`() {
        val sheet = sheetOf("a\nTubo 3\" largo\n", CsvSeparator.SEMICOLON)

        assertEquals(listOf("Tubo 3\" largo"), sheet.rows[0].cells)
    }

    @Test
    fun `the separator is detected from the first written line`() {
        assertEquals(
            CsvSeparator.COMMA,
            detected("\n\nname,price\n\"A;B\",1\n", CsvSeparator.SEMICOLON),
        )
        assertEquals(CsvSeparator.SEMICOLON, detected("name;price\n1;2\n", CsvSeparator.COMMA))
        assertEquals(CsvSeparator.TAB, detected("name\tprice\n1\t2\n", CsvSeparator.SEMICOLON))
        // Las comillas esconden los separadores que van dentro de una celda.
        assertEquals(CsvSeparator.SEMICOLON, detected("\"a,b,c\";x\n1;2\n", CsvSeparator.COMMA))
    }

    @Test
    fun `the detected file reads with the detected separator`() {
        val sheet = sheetOf("\uFEFFname,price\r\n\"Arroz\",\"1.50\"\r\n", separator = null)

        assertEquals(CsvSeparator.COMMA, sheet.separator)
        assertEquals(listOf("Arroz", "1.50"), sheet.rows[0].cells)
    }

    @Test
    fun `a line break inside quotes does not end the detected line`() {
        val sheet = sheetOf("\"first\nname\",price\n1,2\n", separator = null)

        assertEquals(listOf("first\nname", "price"), sheet.header)
        assertEquals(CsvSeparator.COMMA, sheet.separator)
    }

    @Test
    fun `ties and single columns keep the separator of the settings`() {
        assertEquals(CsvSeparator.TAB, CsvSheetReader.chooseSeparator("name", CsvSeparator.TAB))
        assertEquals(
            CsvSeparator.COMMA,
            CsvSheetReader.chooseSeparator("a;b,c", CsvSeparator.COMMA),
        )
        // Un empate sin el de los ajustes: gana el primero de la lista.
        assertEquals(
            CsvSeparator.SEMICOLON,
            CsvSheetReader.chooseSeparator("a;b,c", CsvSeparator.TAB),
        )
    }

    @Test
    fun `detection stops at a bounded length and the full read still applies the limits`() {
        val small = CsvSheetReader(ImportLimits(maxColumns = 2, maxCellChars = 3))

        val read = small.read(source("abcdefgh"), separator = null, CsvSeparator.COMMA)

        assertEquals(ImportFileRead.Rejected(ImportRejection.CELL_TOO_LARGE), read)
    }

    @Test
    fun `an empty, blank or header only file has nothing to import`() {
        listOf("", "\uFEFF", "\n\r\n;;\n", "name;price\r\n", "\uFEFFname\n\n").forEach { text ->
            assertEquals(text, rejected(ImportRejection.EMPTY), read(text, CsvSeparator.SEMICOLON))
            assertEquals(text, rejected(ImportRejection.EMPTY), read(text, separator = null))
        }
    }

    @Test
    fun `a quote that never closes is a truncated file`() {
        assertEquals(
            rejected(ImportRejection.MALFORMED),
            read("a;b\n\"open;1\n2", CsvSeparator.SEMICOLON),
        )
        assertEquals(
            rejected(ImportRejection.MALFORMED),
            read("a;b\n\"open\"\"", CsvSeparator.SEMICOLON),
        )
    }

    @Test
    fun `text glued to a closing quote is a malformed file`() {
        assertEquals(
            rejected(ImportRejection.MALFORMED),
            read("a;b\n\"x\"y;1\n", CsvSeparator.SEMICOLON),
        )
    }

    @Test
    fun `a null character is a binary file`() {
        assertEquals(
            rejected(ImportRejection.UNSUPPORTED_TYPE),
            read("a\n1\u0000\n", CsvSeparator.SEMICOLON),
        )
        assertEquals(
            rejected(ImportRejection.UNSUPPORTED_TYPE),
            read("a\n\"1\u0000\"\n", CsvSeparator.SEMICOLON),
        )
    }

    @Test
    fun `a cell over the limit is rejected at the limit`() {
        val small = CsvSheetReader(ImportLimits(maxCellChars = 4))
        val source = CountingSource("a\n\"abcd\"\n\"abcdefghijklmnopqrstuvwxyz\"\n")

        val read = small.read(source, CsvSeparator.SEMICOLON, CsvSeparator.SEMICOLON)

        assertEquals(rejected(ImportRejection.CELL_TOO_LARGE), read)
        // Se corta en el quinto carácter de la celda, sin leer el resto del archivo.
        assertEquals(15, source.reads)
        assertEquals(
            rejected(ImportRejection.CELL_TOO_LARGE),
            small.read(source("a\nabcde\n"), null, CsvSeparator.COMMA),
        )
    }

    @Test
    fun `a row with too many columns is rejected`() {
        val small = CsvSheetReader(ImportLimits(maxColumns = 3))

        assertTrue(
            small.read(
                source("a;b;c\n1;2;3\n"),
                CsvSeparator.SEMICOLON,
                CsvSeparator.SEMICOLON,
            ) is ImportFileRead.Read,
        )
        assertEquals(
            rejected(ImportRejection.TOO_MANY_COLUMNS),
            small.read(source("a;b;c\n1;2;3;\n"), CsvSeparator.SEMICOLON, CsvSeparator.SEMICOLON),
        )
    }

    @Test
    fun `rows over the limit are rejected, and blank rows do not count`() {
        val small = CsvSheetReader(ImportLimits(maxRows = 2))

        assertTrue(
            small.read(
                source("a\n1\n\n;\n2\n"),
                CsvSeparator.SEMICOLON,
                CsvSeparator.SEMICOLON,
            ) is ImportFileRead.Read,
        )
        assertEquals(
            rejected(ImportRejection.TOO_MANY_ROWS),
            small.read(source("a\n1\n2\n3\n"), CsvSeparator.SEMICOLON, CsvSeparator.SEMICOLON),
        )
    }

    @Test
    fun `progress is reported every step and at the end`() {
        val text = buildString {
            append("a\n")
            repeat(CsvSheetReader.PROGRESS_STEP * 2 + 5) { append(it).append('\n') }
        }
        val seen = mutableListOf<Int>()

        reader.read(source(text), CsvSeparator.SEMICOLON, CsvSeparator.SEMICOLON) { seen += it }

        assertEquals(listOf(1_000, 2_000, 2_005), seen)
    }

    @Test
    fun `the result has no file name until the document adds it`() {
        val read = reader.read(source("a\n1\n"), CsvSeparator.SEMICOLON, CsvSeparator.SEMICOLON)

        assertNull((read as ImportFileRead.Read).fileName)
    }

    @Test
    fun `limits must be positive`() {
        val invalid =
            listOf<() -> ImportLimits>(
                { ImportLimits(maxFileBytes = 0) },
                { ImportLimits(maxEntryBytes = 0) },
                { ImportLimits(maxTotalBytes = 0) },
                { ImportLimits(maxRows = 0) },
                { ImportLimits(maxColumns = 0) },
                { ImportLimits(maxCellChars = 0) },
            )
        invalid.forEach { build -> assertThrows(IllegalArgumentException::class.java) { build() } }
        assertEquals(20L * 1024 * 1024, ImportLimits().maxFileBytes)
        assertEquals(64L * 1024 * 1024, ImportLimits().maxEntryBytes)
        assertEquals(128L * 1024 * 1024, ImportLimits().maxTotalBytes)
        assertEquals(50_000, ImportLimits().maxRows)
    }

    private fun sheetOf(text: String, separator: CsvSeparator?) =
        (read(text, separator) as ImportFileRead.Read).sheet

    private fun read(text: String, separator: CsvSeparator?): ImportFileRead =
        reader.read(source(text), separator, CsvSeparator.SEMICOLON)

    private fun detected(text: String, preferred: CsvSeparator): CsvSeparator? = (
        reader.read(
            source(text),
            separator = null,
            preferred,
        ) as ImportFileRead.Read
        ).sheet.separator

    private fun rejected(reason: ImportRejection) = ImportFileRead.Rejected(reason)

    private fun source(text: String): ImportCharSource = CountingSource(text)

    /** Un archivo en memoria que cuenta cuántos caracteres le pidieron. */
    private class CountingSource(private val text: String) : ImportCharSource {
        var reads = 0
            private set

        override fun read(): Int {
            reads++
            return if (reads <= text.length) text[reads - 1].code else ImportCharSource.END
        }
    }
}
