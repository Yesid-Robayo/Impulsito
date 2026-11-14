package io.github.yesidrobayo.shopledger.data.imports

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportFileRead
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportLimits
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportRejection
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportRow
import io.github.yesidrobayo.shopledger.domain.model.settings.CsvSeparator
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * La primera hoja de un `.xlsx`, leída con el analizador XML del teléfono. Cada libro se arma en
 * la prueba, con ZIP y XML escritos a mano, y los hostiles también: bomba ZIP, ZIP dentro del ZIP,
 * entidades, nombres con `../`, archivo cortado y topes.
 */
@RunWith(AndroidJUnit4::class)
internal class XlsxSheetReaderTest {
    @Test
    fun theFirstSheetIsReadCellByCell() {
        val read = read(book(sheet = SHEET))

        val sheet = (read as ImportFileRead.Read).sheet
        assertEquals(listOf("Nombre", "Precio de venta", "Stock"), sheet.header)
        assertEquals(
            listOf(
                // Un número sale como lo guarda la hoja, con punto decimal; la fórmula vale su
                // resultado.
                ImportRow(2, listOf("Arroz & café", "12.5", "24")),
                // La columna B falta: queda vacía. El texto en línea suma sus tramos.
                ImportRow(4, listOf("Pan de queijo", "", "TRUE")),
                // Una fórmula sin resultado guardado queda vacía, y nunca se evalúa.
                ImportRow(5, listOf("=1+1", "", "")),
            ),
            sheet.rows,
        )
        assertNull(sheet.separator)
    }

    @Test
    fun aBookWithoutRelationsReadsTheDefaultSheet() {
        val read = read(book(sheet = SHEET, relations = null))

        assertEquals(3, (read as ImportFileRead.Read).sheet.rows.size)
    }

    @Test
    fun theFirstSheetOfTheBookIsTheOneRead() {
        val entries =
            mapOf(
                "xl/workbook.xml" to WORKBOOK.replace("rId1", "rId2"),
                "xl/_rels/workbook.xml.rels" to RELATIONS,
                "xl/sharedStrings.xml" to SHARED,
                "xl/worksheets/sheet1.xml" to SHEET,
                "xl/worksheets/sheet2.xml" to sheetWithRows(listOf("a"), listOf("b")),
            )

        val read = read(zip(entries))

        assertEquals(listOf(ImportRow(2, listOf("b"))), (read as ImportFileRead.Read).sheet.rows)
    }

    @Test
    fun anEntryOverItsLimitIsRejectedWhileDecompressing() {
        val bomb = ByteArray(1024 * 1024)
        val entries = mapOf("xl/media/zeros.bin" to bomb)
        val small = ImportLimits(maxEntryBytes = 64 * 1024)

        val result = read(book(SHEET, extra = entries), small)

        assertEquals(rejected(ImportRejection.TOO_LARGE), result)
    }

    @Test
    fun theWholeBookOverTheTotalLimitIsRejected() {
        val entries = (1..8).associate { "xl/media/part$it.bin" to ByteArray(32 * 1024) }
        val small = ImportLimits(maxEntryBytes = 64 * 1024, maxTotalBytes = 200 * 1024)

        val result = read(book(SHEET, extra = entries), small)

        assertEquals(rejected(ImportRejection.TOO_LARGE), result)
    }

    @Test
    fun aZipInsideTheBookIsRejected() {
        val nested = zip(mapOf("inner.txt" to "x"))

        assertEquals(
            rejected(ImportRejection.UNSUPPORTED_TYPE),
            read(book(SHEET, extra = mapOf("xl/embeddings/other.xlsx" to nested))),
        )
    }

    @Test
    fun anEntryNamedWithParentFoldersIsOnlyData() {
        val read = read(book(SHEET, extra = mapOf("../../evil.xml" to "<x/>")))

        // Desde Android 14 el propio sistema rechaza ese nombre al abrir la entrada. Antes, el
        // nombre es solo un dato que no coincide con ninguna parte del libro. Nada se escribe en
        // el disco.
        val rows = (read as? ImportFileRead.Read)?.sheet?.rows?.size
        assertTrue(rows == 3 || read == rejected(ImportRejection.UNSUPPORTED_TYPE))
    }

    @Test
    fun externalEntitiesAndEntityExpansionAreRejectedBeforeUse() {
        val external =
            """<?xml version="1.0"?><!DOCTYPE x [""" +
                """<!ENTITY e SYSTEM "file:///system/etc/hosts">]>""" +
                SHEET.substringAfter("?>").replace("Arroz", "&e;")
        val laughs =
            """<?xml version="1.0"?><!DOCTYPE s [<!ENTITY a "aaaaaaaaaa">""" +
                """<!ENTITY b "&a;&a;&a;&a;&a;">]>""" +
                SHARED.substringAfter("?>").replace("Nombre", "&b;")

        assertEquals(rejected(ImportRejection.UNSUPPORTED_TYPE), read(book(sheet = external)))
        assertEquals(
            rejected(ImportRejection.UNSUPPORTED_TYPE),
            read(book(sheet = SHEET, shared = laughs)),
        )
    }

    @Test
    fun aTruncatedOrBrokenBookIsRejected() {
        val whole = book(SHEET)

        // Según dónde quede el corte, falla el ZIP o la hoja: en los dos casos no entra nada.
        assertTrue(read(whole.copyOf(whole.size / 2)) is ImportFileRead.Rejected)
        assertTrue(read(book(sheet = "<worksheet><row>")) is ImportFileRead.Rejected)
        assertEquals(rejected(ImportRejection.UNSUPPORTED_TYPE), read(zip(mapOf("a.txt" to "x"))))
    }

    @Test
    fun aSharedStringOutOfRangeIsMalformed() {
        val sheet = sheetXml("""<row r="1"><c r="A1" t="s"><v>99</v></c></row>""")

        assertEquals(rejected(ImportRejection.MALFORMED), read(book(sheet)))
    }

    @Test
    fun aCellInAFarColumnIsRejectedWithoutFillingTheGap() {
        val far = """<c r="XFD1" t="inlineStr"><is><t>x</t></is></c>"""
        val sheet = sheetXml("<row r=\"1\">$far</row>")

        assertEquals(rejected(ImportRejection.TOO_MANY_COLUMNS), read(book(sheet)))
    }

    @Test
    fun rowsAndCellsOverTheirLimitsAreRejected() {
        val many = sheetWithRows(listOf("a"), listOf("1"), listOf("2"), listOf("3"))
        val giant = sheetWithRows(listOf("a"), listOf("x".repeat(5_000)))

        assertEquals(
            rejected(ImportRejection.TOO_MANY_ROWS),
            read(book(many), ImportLimits(maxRows = 2)),
        )
        assertEquals(rejected(ImportRejection.CELL_TOO_LARGE), read(book(giant)))
    }

    @Test
    fun aSheetWithOnlyTheHeaderHasNothingToImport() {
        assertEquals(rejected(ImportRejection.EMPTY), read(book(sheetWithRows(listOf("a")))))
    }

    @Test
    fun aBookIsRecognizedByItsBytesThroughTheStreamReader() {
        val stream = ByteArrayInputStream(book(SHEET))

        val read = ImportStreamReader().read(stream, null, CsvSeparator.SEMICOLON, {}, {})

        assertEquals(3, (read as ImportFileRead.Read).sheet.rows.size)
    }

    @Test
    fun columnReferencesAreReadAsLetters() {
        assertEquals(0, XlsxSheetReader.columnOf("A1"))
        assertEquals(25, XlsxSheetReader.columnOf("Z9"))
        assertEquals(26, XlsxSheetReader.columnOf("AA3"))
        assertEquals(16_383, XlsxSheetReader.columnOf("XFD1"))
    }

    private fun read(file: ByteArray, limits: ImportLimits = ImportLimits()) =
        XlsxSheetReader(limits).read(file, {}, {})

    private fun rejected(reason: ImportRejection) = ImportFileRead.Rejected(reason)

    private fun book(
        sheet: String,
        shared: String = SHARED,
        relations: String? = RELATIONS,
        extra: Map<String, Any> = emptyMap(),
    ): ByteArray {
        val entries = LinkedHashMap<String, Any>()
        entries["[Content_Types].xml"] = "<Types/>"
        entries["xl/workbook.xml"] = WORKBOOK
        if (relations != null) entries["xl/_rels/workbook.xml.rels"] = relations
        // Los textos compartidos van después de la hoja, como los deja más de un programa.
        entries["xl/worksheets/sheet1.xml"] = sheet
        entries["xl/sharedStrings.xml"] = shared
        entries.putAll(extra)
        return zip(entries)
    }

    private fun zip(entries: Map<String, Any>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(if (content is ByteArray) content else content.toString().toByteArray())
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    private companion object {
        val WORKBOOK =
            """<?xml version="1.0" encoding="UTF-8"?>
            <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
                xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
            <sheets><sheet name="Hoja" sheetId="1" r:id="rId1"/><sheet name="Otra" sheetId="2" r:id="rId2"/></sheets>
            </workbook>
            """.trimIndent()

        val RELATIONS =
            """<?xml version="1.0" encoding="UTF-8"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
            <Relationship Id="rId1" Target="worksheets/sheet1.xml" Type="worksheet"/>
            <Relationship Id="rId2" Target="/xl/worksheets/sheet2.xml" Type="worksheet"/>
            </Relationships>
            """.trimIndent()

        val SHARED =
            """<?xml version="1.0" encoding="UTF-8"?>
            <sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" count="4" uniqueCount="4">
            <si><t>Nombre</t></si>
            <si><r><t>Precio </t></r><r><t xml:space="preserve">de venta</t></r></si>
            <si><t>Stock</t><rPh><t>sutokku</t></rPh></si>
            <si><t>Arroz &amp; café</t></si>
            </sst>
            """.trimIndent()

        val SHEET =
            sheetXml(
                """
                <row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c><c r="C1" t="s"><v>2</v></c></row>
                <row r="2"><c r="A2" t="s"><v>3</v></c><c r="B2"><v>12.5</v></c><c r="C2"><f>SUM(1,23)</f><v>24</v></c></row>
                <row r="3"><c r="A3" t="inlineStr"><is><t> </t></is></c></row>
                <row r="4"><c r="A4" t="inlineStr"><is><r><t>Pan de </t></r><r><t>queijo</t></r></is></c><c r="C4" t="b"><v>1</v></c></row>
                <row><c t="inlineStr"><is><t>=1+1</t></is></c><c r="C5"><f>A1*2</f></c></row>
                """.trimIndent(),
            )

        const val MAIN_NAMESPACE = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"

        fun sheetXml(rows: String): String = """<?xml version="1.0" encoding="UTF-8"?>""" +
            """<worksheet xmlns="$MAIN_NAMESPACE"><sheetData>""" +
            rows +
            "</sheetData></worksheet>"

        fun sheetWithRows(vararg rows: List<String>): String = sheetXml(
            rows.joinToString("") { cells ->
                "<row>" +
                    cells.joinToString("") { "<c t=\"inlineStr\"><is><t>$it</t></is></c>" } +
                    "</row>"
            },
        )
    }
}
