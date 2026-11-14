package io.github.yesidrobayo.shopledger.domain.rule.imports

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportCatalog
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportDraft
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportIssue
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportRow
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportRowIssue
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportSheet
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportTarget
import io.github.yesidrobayo.shopledger.domain.model.region.DecimalSeparator
import io.github.yesidrobayo.shopledger.domain.model.settings.CsvSeparator
import io.github.yesidrobayo.shopledger.domain.rule.export.CsvRowWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El separador decimal que se deduce de un CSV, el archivo con las filas con error, y los bordes de
 * la lectura de filas que no cubren los casos comunes: textos de más en cada campo de contacto y
 * números fuera de tope. Datos inventados.
 */
class ImportFileFormatTest {
    private fun csv(vararg cells: String, separator: CsvSeparator? = CsvSeparator.SEMICOLON) =
        ImportSheet(
            header = listOf("a"),
            rows = cells.mapIndexed { index, cell -> ImportRow(index + 2, listOf(cell)) },
            separator = separator,
        )

    @Test
    fun `the numbers of a csv decide its decimal separator`() {
        val comma = csv("12,5", "1.234,50", "texto", "7")
        val point = csv("12.5", "1,234.50", "-3.25")
        val undecided = csv("1.234", "1,234", "1.234.567", "12", "+")

        assertEquals(
            DecimalSeparator.COMMA,
            ImportFileFormat.decimalOf(comma, DecimalSeparator.POINT),
        )
        assertEquals(
            DecimalSeparator.POINT,
            ImportFileFormat.decimalOf(point, DecimalSeparator.COMMA),
        )
        assertEquals(
            DecimalSeparator.COMMA,
            ImportFileFormat.decimalOf(undecided, DecimalSeparator.COMMA),
        )
    }

    @Test
    fun `a spreadsheet always uses the point`() {
        val sheet = csv("12,5", separator = null)

        assertEquals(
            DecimalSeparator.POINT,
            ImportFileFormat.decimalOf(sheet, DecimalSeparator.COMMA),
        )
    }

    @Test
    fun `the rows with errors keep the header and never come back as formulas`() {
        val sheet = ImportSheet(
            header = listOf("Nombre", "Precio"),
            rows = listOf(
                ImportRow(2, listOf("Arroz", "10")),
                ImportRow(3, listOf("=HYPERLINK(\"x\")", "'-5")),
            ),
            separator = CsvSeparator.SEMICOLON,
        )

        val text = ImportFileFormat.errorRowsCsv(sheet, setOf(3), CsvSeparator.COMMA)

        assertEquals(
            CsvRowWriter.BYTE_ORDER_MARK +
                "\"Nombre\",\"Precio\"" + CsvRowWriter.LINE_END +
                "\"'=HYPERLINK(\"\"x\"\")\",\"'-5\"" + CsvRowWriter.LINE_END,
            text,
        )
    }

    @Test
    fun `a csv with a decimal point reads thousands with commas`() {
        val format = ImportNumberFormat(DecimalSeparator.POINT, 2, spreadsheet = false)
        val reader = ImportRowReader(
            ImportTarget.PRODUCTS,
            listOf("name", "sale_price"),
            format,
            ImportCatalog(),
        )

        val preview = reader.read(
            ImportSheet(emptyList(), listOf(ImportRow(2, listOf("Té", "1,234.5"))), null),
        )

        assertEquals(Money(123_450L), (preview.ready.single() as ImportDraft.Product).salePrice)
    }

    @Test
    fun `every contact field too long is listed`() {
        val long = "x".repeat(501)
        val email = "a@" + "b".repeat(120)
        val phone = "1".repeat(31)
        val customers = ImportRowReader(
            ImportTarget.CUSTOMERS,
            listOf("name", "phone", "email", "notes"),
            ImportNumberFormat(DecimalSeparator.COMMA, 0, spreadsheet = false),
            ImportCatalog(),
        ).read(
            ImportSheet(emptyList(), listOf(ImportRow(2, listOf(long, phone, email, long))), null),
        )
        val suppliers = ImportRowReader(
            ImportTarget.SUPPLIERS,
            listOf("name", "phone", "email", "notes"),
            ImportNumberFormat(DecimalSeparator.COMMA, 0, spreadsheet = false),
            ImportCatalog(),
        ).read(
            ImportSheet(
                emptyList(),
                listOf(
                    ImportRow(2, listOf(long, phone, email, long)),
                    ImportRow(3, listOf("", "abc", "sin", "")),
                ),
                null,
            ),
        )

        val tooLong = listOf("name", "phone", "email", "notes").map {
            ImportRowIssue(2, it, ImportIssue.LENGTH)
        }.toSet()
        assertEquals(tooLong, customers.issues.toSet())
        assertEquals(
            tooLong + setOf(
                ImportRowIssue(3, "name", ImportIssue.MISSING),
                ImportRowIssue(3, "phone", ImportIssue.PHONE),
                ImportRowIssue(3, "email", ImportIssue.EMAIL),
            ),
            suppliers.issues.toSet(),
        )
    }

    @Test
    fun `numbers out of range and edge rows are errors of their cell`() {
        val reader = ImportRowReader(
            ImportTarget.PRODUCTS,
            listOf("name", "category", "barcode", "on_hand", "min_stock", "stock_mode"),
            ImportNumberFormat(DecimalSeparator.COMMA, 2, spreadsheet = false),
            ImportCatalog(barcodes = mapOf("77" to "otro")),
        )

        val preview = reader.read(
            ImportSheet(
                emptyList(),
                listOf(
                    ImportRow(2, listOf("A", "c".repeat(41), "", "abc", "", "")),
                    ImportRow(3, listOf("B", "", "", "2000000", "", "")),
                    ImportRow(4, listOf("", "", "77", "", "", "")),
                    ImportRow(5, listOf("C", "", "", "", "", "untracked")),
                ),
                null,
            ),
        )

        assertEquals(
            listOf(
                ImportRowIssue(2, "category", ImportIssue.LENGTH),
                ImportRowIssue(2, "on_hand", ImportIssue.QUANTITY),
                ImportRowIssue(3, "on_hand", ImportIssue.LIMIT),
                // Sin nombre no se sabe de quién es el código: solo falta el nombre.
                ImportRowIssue(4, "name", ImportIssue.MISSING),
            ),
            preview.issues,
        )
        val untracked = preview.ready.single() as ImportDraft.Product
        assertEquals(null, untracked.onHand)
    }

    @Test
    fun `percentages out of range or with three decimals are rejected`() {
        val reader = ImportRowReader(
            ImportTarget.PRICE_INDEX,
            listOf("month", "change_percent"),
            ImportNumberFormat(DecimalSeparator.COMMA, 2, spreadsheet = true),
            ImportCatalog(existing = mapOf("2026-01" to false)),
        )

        val preview = reader.read(
            ImportSheet(
                emptyList(),
                listOf(
                    ImportRow(2, listOf("46023", "1.234")),
                    ImportRow(3, listOf("2026-03", "10000.01")),
                    ImportRow(4, listOf("2026-01", "2")),
                ),
                null,
            ),
        )

        assertEquals(
            listOf(
                ImportRowIssue(2, "change_percent", ImportIssue.DECIMALS),
                ImportRowIssue(3, "change_percent", ImportIssue.LIMIT),
            ),
            preview.issues,
        )
        // Solo hay una fila que ya existe: ninguna se crea.
        assertTrue(preview.ready.isEmpty())
        assertEquals(listOf(4), preview.existing.map { it.row })
        assertEquals(0, preview.stockWithoutCost)
    }
}
