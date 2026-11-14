package io.github.yesidrobayo.shopledger.data.imports

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportCatalog
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportDraft
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportFileRead
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportIssue
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportLimits
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportPreview
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportRejection
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportRowIssue
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportTarget
import io.github.yesidrobayo.shopledger.domain.model.region.DecimalSeparator
import io.github.yesidrobayo.shopledger.domain.model.settings.CsvSeparator
import io.github.yesidrobayo.shopledger.domain.rule.export.CsvText
import io.github.yesidrobayo.shopledger.domain.rule.imports.ColumnMatch
import io.github.yesidrobayo.shopledger.domain.rule.imports.ImportColumnMatcher
import io.github.yesidrobayo.shopledger.domain.rule.imports.ImportNumberFormat
import io.github.yesidrobayo.shopledger.domain.rule.imports.ImportRowReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * El corpus de importación hostil, generado aquí y con los topes reales: cada archivo pasa por el
 * mismo camino que uno elegido en el selector (tipo por los primeros bytes, lector de CSV o de
 * `.xlsx`, reconocimiento de columnas y lectura de filas) y da el resultado de la tabla de
 * archivos hostiles de la guía de pruebas.
 *
 * - **Se rechaza sin tocar nada:** bomba ZIP, ZIP anidado, entidad externa, entidades en
 *   expansión, libro cortado, archivo, filas, columnas o celda sobre su tope, UTF-16 y `.xls`.
 * - **Se importa como texto:** las celdas que empiezan como fórmula entran tal cual y vuelven a
 *   salir neutralizadas.
 * - **Va a relacionar columnas:** un encabezado desconocido.
 * - **Error de su fila:** decimales de más, la moneda en la celda y un mes que no existe.
 * - **Se acepta:** un archivo exportado en cada uno de los tres idiomas.
 *
 * Los datos son inventados.
 */
@RunWith(AndroidJUnit4::class)
class ImportCorpusTest {
    private val headers = mapOf(
        "name" to listOf("Name", "Nombre", "Nome"),
        "sale_price" to listOf("Sale price", "Precio de venta", "Preço de venda"),
        "on_hand" to listOf("Stock", "Stock", "Estoque"),
        "month" to listOf("Month", "Mes", "Mês"),
        "change_percent" to listOf("Change", "Variación", "Variação"),
    )
    private val matcher = ImportColumnMatcher { key -> headers[key].orEmpty() }

    @Test
    fun aZipBombIsCutAtTheRealEntryLimit() {
        // 65 MiB de ceros comprimen a unos pocos KiB: la entrada pasa los 64 MiB del tope.
        val zeros = ZeroStream(ImportLimits.MAX_IMPORT_ENTRY_BYTES + MIB)
        val bomb = zip(baseBook() + ("xl/worksheets/sheet1.xml" to zeros))

        assertTrue(bomb.size < ImportLimits.MAX_IMPORT_FILE_BYTES)
        assertRejected(ImportRejection.TOO_LARGE, read(bomb))
    }

    @Test
    fun aZipInsideTheBookIsRejected() {
        val inner = zip(baseBook())
        val nested = zip(baseBook() + ("xl/embeddings/otro.xlsx" to inner))

        assertRejected(ImportRejection.UNSUPPORTED_TYPE, read(nested))
    }

    @Test
    fun externalAndExpandingEntitiesAreNeverExpanded() {
        val external = """<?xml version="1.0"?>""" +
            """<!DOCTYPE x [<!ENTITY e SYSTEM "file:///etc/hosts">]>""" +
            entityRow("e")
        val laughs = """<?xml version="1.0"?><!DOCTYPE x [<!ENTITY a "aaaaaaaaaa">""" +
            """<!ENTITY b "&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;">""" +
            """<!ENTITY c "&b;&b;&b;&b;&b;&b;&b;&b;">]>""" +
            entityRow("c")

        assertRejected(
            ImportRejection.UNSUPPORTED_TYPE,
            read(zip(baseBook() + ("xl/worksheets/sheet1.xml" to external))),
        )
        assertRejected(
            ImportRejection.UNSUPPORTED_TYPE,
            read(zip(baseBook() + ("xl/worksheets/sheet1.xml" to laughs))),
        )
    }

    @Test
    fun aCutBookIsNotRead() {
        val book = zip(baseBook() + ("xl/worksheets/sheet1.xml" to sheet(listOf("Nombre"))))

        val result = read(book.copyOf(book.size / 2))

        assertTrue(result is ImportFileRead.Rejected)
    }

    @Test
    fun filesRowsColumnsAndCellsOverTheirLimitRejectTheWholeFile() {
        // Filas anchas, dentro de los topes de filas, columnas y celdas: el tope que salta es el
        // del tamaño del archivo.
        val wide = (1..WIDE_COLUMNS).joinToString(";") { "x".repeat(WIDE_CELL) } + "\r\n"
        val tooLarge = read(PatternStream(wide, ImportLimits.MAX_IMPORT_FILE_BYTES + 1))
        val rows = buildString {
            append("Nombre\r\n")
            repeat(ImportLimits.MAX_IMPORT_ROWS + 1) { append("P").append(it).append("\r\n") }
        }
        val header = (0..ImportLimits.MAX_IMPORT_COLUMNS).joinToString(";") { "c$it" }
        val columns = header + "\r\n1\r\n"
        val cell = "Nombre\r\n" + "x".repeat(ImportLimits.MAX_IMPORT_CELL_CHARS + 1) + "\r\n"

        assertRejected(ImportRejection.TOO_LARGE, tooLarge)
        assertRejected(ImportRejection.TOO_MANY_ROWS, read(rows.toByteArray()))
        assertRejected(ImportRejection.TOO_MANY_COLUMNS, read(columns.toByteArray()))
        assertRejected(ImportRejection.CELL_TOO_LARGE, read(cell.toByteArray()))
    }

    @Test
    fun otherEncodingsAndTheOldSpreadsheetAreNotRead() {
        val utf16 = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) +
            "Nombre\r\n".toByteArray(Charsets.UTF_16LE)
        val oldSheet = byteArrayOf(
            0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(),
            0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte(), 0, 0,
        )

        assertRejected(ImportRejection.ENCODING, read(utf16))
        assertRejected(ImportRejection.UNSUPPORTED_TYPE, read(oldSheet))
    }

    @Test
    fun formulasEnterAsTextAndLeaveNeutralized() {
        val names = listOf("=1+1", "+12", "-2+3", "@SUM(A1:A9)", "＝1+1", "=HYPERLINK(\"x\")")
        val csv = "Nombre\r\n" + names.joinToString("") { "\"${it.replace("\"", "\"\"")}\"\r\n" }

        val preview = preview(read(csv.toByteArray()), ImportTarget.PRODUCTS)

        val read = preview.ready.map { (it as ImportDraft.Product).name }
        assertEquals(names, read)
        read.forEach { assertTrue(it, CsvText.quoted(it).startsWith("\"'")) }
    }

    @Test
    fun anExportedFormulaKeepsItsTextWithoutTheApostrophe() {
        val csv = "\"Nombre\";\"Precio de venta\"\r\n\"'=1+1\";10\r\n\"'Clasico'\";20\r\n"

        val preview = preview(read(csv.toByteArray()), ImportTarget.PRODUCTS)

        assertEquals(
            listOf("=1+1", "'Clasico'"),
            preview.ready.map { (it as ImportDraft.Product).name },
        )
    }

    @Test
    fun anUnknownColumnGoesToTheMapping() {
        val file = "Nombre;Precio venta;Color\r\nTé;1;rojo\r\n"
        val sheet = (read(file.toByteArray()) as Read).sheet

        val matches = matcher.match(sheet.header, ImportTarget.PRODUCTS)

        assertEquals(
            listOf(ColumnMatch.Field("name"), ColumnMatch.Unmatched, ColumnMatch.Unmatched),
            matches,
        )
    }

    @Test
    fun badNumbersAndMonthsAreErrorsOfTheirRowOnly() {
        val products = "Nombre;Precio de venta\r\nA;12,345\r\nB;$ 12\r\nC;12,5\r\n"
        val months = "Mes;Variación\r\n2026-13;1\r\n2026-02;1\r\n"

        val prices = preview(read(products.toByteArray()), ImportTarget.PRODUCTS)
        val index = preview(read(months.toByteArray()), ImportTarget.PRICE_INDEX)

        assertEquals(
            listOf(
                ImportRowIssue(2, "sale_price", ImportIssue.DECIMALS),
                ImportRowIssue(3, "sale_price", ImportIssue.AMOUNT),
            ),
            prices.issues,
        )
        assertEquals(listOf(4), prices.ready.map { it.row })
        assertEquals(listOf(ImportRowIssue(2, "month", ImportIssue.MONTH)), index.issues)
        assertEquals(listOf(3), index.ready.map { it.row })
    }

    @Test
    fun aFileExportedInEachLanguageIsAccepted() {
        val files = listOf(
            BOM + "\"Name\",\"Sale price\",\"Stock\"\r\n\"Rice\",12.50,3.000\r\n",
            BOM + "\"Nombre\";\"Precio de venta\";\"Stock\"\r\n\"Arroz\";12,50;3,000\r\n",
            BOM + "\"Nome\";\"Preço de venda\";\"Estoque\"\r\n\"Arroz\";12,50;3,000\r\n",
        )

        for (file in files) {
            val read = read(file.toByteArray())
            val sheet = (read as Read).sheet
            val matches = matcher.match(sheet.header, ImportTarget.PRODUCTS)
            assertEquals(
                listOf("name", "sale_price", "on_hand"),
                matches.map { (it as ColumnMatch.Field).key },
            )
            val preview = preview(read, ImportTarget.PRODUCTS)
            val product = preview.ready.single() as ImportDraft.Product
            assertEquals(1_250L, product.salePrice?.minor)
            assertEquals(3_000L, product.onHand?.milli)
        }
    }

    @Test
    fun aSpreadsheetWithFormulasUsesTheirStoredValue() {
        val rows = """<row><c t="inlineStr"><is><t>Nombre</t></is></c>""" +
            """<c t="inlineStr"><is><t>Precio de venta</t></is></c></row>""" +
            """<row><c t="inlineStr"><is><t>Té</t></is></c>""" +
            """<c><f>WEBSERVICE("x")</f><v>0.30000000000000004</v></c></row>"""
        val book = zip(baseBook() + ("xl/worksheets/sheet1.xml" to sheetXml(rows)))

        val preview = preview(read(book), ImportTarget.PRODUCTS)

        assertEquals(30L, (preview.ready.single() as ImportDraft.Product).salePrice?.minor)
    }

    /** Una hoja con una celda que nombra la entidad [name]. */
    private fun entityRow(name: String): String =
        """<worksheet xmlns="$MAIN"><sheetData><row><c t="inlineStr"><is>""" +
            "<t>&$name;</t></is></c></row></sheetData></worksheet>"

    private fun read(bytes: ByteArray): ImportFileRead = read(ByteArrayInputStream(bytes))

    private fun read(stream: InputStream): ImportFileRead =
        ImportStreamReader().read(stream, null, CsvSeparator.SEMICOLON, {}, {})

    /** La vista previa de [result] con las columnas que reconoce y dos decimales de moneda. */
    private fun preview(result: ImportFileRead, target: ImportTarget): ImportPreview {
        val sheet = (result as Read).sheet
        val columns = matcher.match(sheet.header, target).map { (it as? ColumnMatch.Field)?.key }
        val decimal = when (sheet.separator) {
            CsvSeparator.COMMA -> DecimalSeparator.POINT
            else -> DecimalSeparator.COMMA
        }
        val format = ImportNumberFormat(decimal, 2, spreadsheet = sheet.separator == null)
        return ImportRowReader(target, columns, format, ImportCatalog()).read(sheet)
    }

    private fun assertRejected(reason: ImportRejection, result: ImportFileRead) {
        assertEquals(ImportFileRead.Rejected(reason), result)
    }

    private fun baseBook(): Map<String, Any> = linkedMapOf(
        "[Content_Types].xml" to "<Types/>",
        "xl/workbook.xml" to WORKBOOK,
        "xl/_rels/workbook.xml.rels" to RELATIONS,
        "xl/sharedStrings.xml" to """<sst xmlns="$MAIN"/>""",
    )

    private fun sheet(vararg rows: List<String>): String = sheetXml(
        rows.joinToString("") { cells ->
            "<row>" + cells.joinToString("") { "<c t=\"inlineStr\"><is><t>$it</t></is></c>" } +
                "</row>"
        },
    )

    private fun sheetXml(rows: String): String =
        """<?xml version="1.0" encoding="UTF-8"?><worksheet xmlns="$MAIN"><sheetData>""" +
            rows + "</sheetData></worksheet>"

    /** Un ZIP con [entries] en orden: texto, bytes o un flujo que se copia sin cargarlo entero. */
    private fun zip(entries: Map<String, Any>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                when (content) {
                    is ByteArray -> zip.write(content)
                    is InputStream -> content.copyTo(zip)
                    else -> zip.write(content.toString().toByteArray())
                }
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    /** [size] bytes en cero, sin ocupar memoria. */
    private class ZeroStream(private var size: Long) : InputStream() {
        override fun read(): Int = if (size-- > 0) 0 else -1

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (size <= 0) return -1
            val count = minOf(length.toLong(), size).toInt()
            buffer.fill(0, offset, offset + count)
            size -= count
            return count
        }
    }

    /** [size] bytes que repiten [pattern], sin ocupar memoria. */
    private class PatternStream(pattern: String, private var size: Long) : InputStream() {
        private val bytes = pattern.toByteArray()
        private var position = 0

        override fun read(): Int {
            if (size <= 0) return -1
            size--
            return bytes[position++ % bytes.size].toInt()
        }
    }

    private companion object {
        const val MIB = 1024L * 1024
        const val WIDE_COLUMNS = 250
        const val WIDE_CELL = 4_000
        const val MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
        const val OFFICE = "http://schemas.openxmlformats.org/officeDocument/2006"
        const val PACKAGE = "http://schemas.openxmlformats.org/package/2006"
        val BOM: String = Char(0xFEFF).toString()

        val WORKBOOK =
            """<?xml version="1.0" encoding="UTF-8"?>""" +
                """<workbook xmlns="$MAIN" """ +
                """xmlns:r="$OFFICE/relationships">""" +
                """<sheets><sheet name="Hoja" sheetId="1" r:id="rId1"/></sheets></workbook>"""

        val RELATIONS =
            """<?xml version="1.0" encoding="UTF-8"?>""" +
                """<Relationships xmlns="$PACKAGE/relationships">""" +
                """<Relationship Id="rId1" Target="worksheets/sheet1.xml" Type="worksheet"/>""" +
                "</Relationships>"
    }
}

private typealias Read = ImportFileRead.Read
