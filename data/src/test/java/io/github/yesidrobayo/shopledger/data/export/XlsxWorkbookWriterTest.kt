package io.github.yesidrobayo.shopledger.data.export

import io.github.yesidrobayo.shopledger.domain.rule.export.SheetValue
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El `.xlsx` de la exportación, escrito con `java.util.zip`: sus partes, una hoja por archivo,
 * texto en línea y números como números. Datos inventados.
 */
class XlsxWorkbookWriterTest {
    @Test
    fun `the package has its parts and one sheet per file`() {
        val parts = workbook(listOf("productos", "ventas")) { writer ->
            writer.startSheet(listOf("Nombre", "Precio"))
            writer.row(listOf(SheetValue.Text("'=1+1"), SheetValue.Number("12.50")))
            writer.row(listOf(SheetValue.Empty, SheetValue.Number("-3")))
            writer.endSheet()
            writer.startSheet(listOf("Total"))
            writer.endSheet()
        }

        assertEquals(
            listOf(
                "[Content_Types].xml",
                "_rels/.rels",
                "xl/workbook.xml",
                "xl/_rels/workbook.xml.rels",
                "xl/styles.xml",
                "xl/worksheets/sheet1.xml",
                "xl/worksheets/sheet2.xml",
            ),
            parts.keys.toList(),
        )
        val workbook = parts.getValue("xl/workbook.xml")
        assertTrue(workbook.contains("<sheet name=\"productos\" sheetId=\"1\" r:id=\"rId1\"/>"))
        assertTrue(workbook.contains("<sheet name=\"ventas\" sheetId=\"2\" r:id=\"rId2\"/>"))
        val sheet = parts.getValue("xl/worksheets/sheet1.xml")
        assertTrue(
            sheet.contains(
                "<c r=\"A2\" t=\"inlineStr\"><is><t xml:space=\"preserve\">'=1+1</t></is></c>",
            ),
        )
        assertTrue(sheet.contains("<c r=\"B2\"><v>12.50</v></c>"))
        // La celda vacía no se escribe: la fila 3 empieza en la columna B.
        assertTrue(sheet.contains("<row r=\"3\"><c r=\"B3\"><v>-3</v></c></row>"))
        assertTrue(!sheet.contains("<f>"))
    }

    @Test
    fun `text is escaped and characters xml does not allow are dropped`() {
        assertEquals(
            "a &amp; b &lt;c&gt; &quot;d&quot;\tx",
            XlsxWorkbookWriter.escaped("a & b <c> \"d\"\tx" + Char(1)),
        )
        // Un emoji, que son dos unidades de UTF-16, se conserva; un sustituto suelto se quita.
        val emoji = String(Character.toChars(0x1F600))
        assertEquals(emoji, XlsxWorkbookWriter.escaped(emoji))
        assertEquals("ab", XlsxWorkbookWriter.escaped("a" + Char(0xD800) + "b"))
        assertEquals("", XlsxWorkbookWriter.escaped(Char(0xFFFE).toString()))
    }

    @Test
    fun `columns are named like a spreadsheet`() {
        assertEquals("A", XlsxWorkbookWriter.columnName(0))
        assertEquals("Z", XlsxWorkbookWriter.columnName(25))
        assertEquals("AA", XlsxWorkbookWriter.columnName(26))
        assertEquals("BA", XlsxWorkbookWriter.columnName(52))
    }

    @Test
    fun `sheet names are cleaned, shortened and unique`() {
        assertEquals(
            listOf("ab", "x".repeat(31), "Ventas", "ventas (2)", "5"),
            XlsxWorkbookWriter.uniqueNames(
                listOf("a[b]?", "x".repeat(40), "Ventas", "ventas", "/:"),
            ),
        )
    }

    /** Escribe un libro con [names] y [sheets], y devuelve cada parte con su texto, en orden. */
    private fun workbook(
        names: List<String>,
        sheets: (XlsxWorkbookWriter) -> Unit,
    ): Map<String, String> {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            val writer = XlsxWorkbookWriter(zip, names)
            writer.start()
            sheets(writer)
        }
        val parts = LinkedHashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes.toByteArray())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                parts[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        return parts
    }
}
