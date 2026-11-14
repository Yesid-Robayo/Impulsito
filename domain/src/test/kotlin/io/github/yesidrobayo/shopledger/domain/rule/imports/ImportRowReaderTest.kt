package io.github.yesidrobayo.shopledger.domain.rule.imports

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportCatalog
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportChoice
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportDraft
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportExistingPolicy
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportIssue
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportPreview
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportRow
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportRowIssue
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportSheet
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportTarget
import io.github.yesidrobayo.shopledger.domain.model.region.DecimalSeparator
import io.github.yesidrobayo.shopledger.domain.model.settings.CsvSeparator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Leer las filas de un archivo con las reglas de cada campo. Los valores esperados están escritos a
 * mano: un monto con dos decimales `4.200,50` son 420050 unidades menores.
 */
class ImportRowReaderTest {
    private val productColumns = listOf(
        "name",
        "category",
        "default_supplier",
        "base_unit",
        "stock_mode",
        "barcode",
        "sale_price",
        "average_cost",
        "on_hand",
        "min_stock",
    )

    private val kilo =
        ImportChoice(id = 7L, names = setOf("kg", "kilogramo"), allowsFraction = true)
    private val unit = ImportChoice(id = 1L, names = setOf("und", "unidad"), allowsFraction = false)

    private val catalog = ImportCatalog(
        existing = mapOf("arroz 1 kg" to false, "jabon viejo" to true),
        barcodes = mapOf("7700001" to "arroz 1 kg"),
        units = listOf(unit, kilo),
        defaultUnitAllowsFraction = false,
        categories = setOf("granos"),
        suppliers = setOf("distribuidora sur"),
    )

    private val commaCsv = ImportNumberFormat(DecimalSeparator.COMMA, 2, spreadsheet = false)

    private fun sheet(
        vararg rows: List<String>,
        separator: CsvSeparator? = CsvSeparator.SEMICOLON,
    ) = ImportSheet(
        header = emptyList(),
        rows = rows.mapIndexed { index, cells -> ImportRow(index + 2, cells) },
        separator = separator,
    )

    private fun products(
        vararg rows: List<String>,
        format: ImportNumberFormat = commaCsv,
        separator: CsvSeparator? = CsvSeparator.SEMICOLON,
    ): ImportPreview = ImportRowReader(ImportTarget.PRODUCTS, productColumns, format, catalog)
        .read(sheet(*rows, separator = separator))

    private fun productRow(
        name: String = "Lentejas",
        category: String = "",
        supplier: String = "",
        unitName: String = "",
        mode: String = "",
        barcode: String = "",
        price: String = "",
        cost: String = "",
        onHand: String = "",
        minStock: String = "",
    ) = listOf(name, category, supplier, unitName, mode, barcode, price, cost, onHand, minStock)

    @Test
    fun `a valid product is read with its amounts in minor units`() {
        val preview = products(
            productRow(
                name = "  Lentejas 500 g ",
                category = "Granos",
                supplier = "Distribuidora Norte",
                unitName = "KG",
                mode = "tracked",
                barcode = "'0012345",
                price = "4.200,50",
                cost = "3800",
                onHand = "12,5",
                minStock = "2",
            ),
        )

        assertEquals(emptyList<ImportRowIssue>(), preview.issues)
        val draft = preview.ready.single() as ImportDraft.Product
        assertEquals("Lentejas 500 g", draft.name)
        assertEquals("lentejas 500 g", draft.normalizedName)
        assertEquals(7L, draft.unitId)
        assertEquals(ProductStockMode.TRACKED, draft.stockMode)
        assertEquals("0012345", draft.barcode)
        assertEquals(Money(420_050L), draft.salePrice)
        assertEquals(Money(380_000L), draft.cost)
        assertEquals(QuantityMilli(12_500L), draft.onHand)
        assertEquals(QuantityMilli(2_000L), draft.minStock)
        // `Granos` ya existe; `Distribuidora Norte` no.
        assertEquals(0, preview.newCategories)
        assertEquals(1, preview.newSuppliers)
    }

    @Test
    fun `more decimals than the currency is an error and never a silent rounding`() {
        val preview = products(productRow(price = "12,345"))

        assertEquals(listOf(ImportRowIssue(2, "sale_price", ImportIssue.DECIMALS)), preview.issues)
        assertTrue(preview.ready.isEmpty())
    }

    @Test
    fun `a currency symbol or letters in an amount are an invalid amount`() {
        val preview = products(productRow(price = "$ 12"), productRow(name = "B", cost = "doce"))

        assertEquals(
            listOf(
                ImportRowIssue(2, "sale_price", ImportIssue.AMOUNT),
                ImportRowIssue(3, "average_cost", ImportIssue.AMOUNT),
            ),
            preview.issues,
        )
    }

    @Test
    fun `negative amounts and quantities are rejected`() {
        val preview = products(productRow(price = "-5", onHand = "-1"))

        assertEquals(
            listOf(
                ImportRowIssue(2, "sale_price", ImportIssue.AMOUNT),
                ImportRowIssue(2, "on_hand", ImportIssue.QUANTITY),
            ),
            preview.issues,
        )
    }

    @Test
    fun `amounts above the limit and quantities with four decimals are rejected`() {
        val preview = products(productRow(price = "99999999999999", onHand = "1,2345"))

        assertEquals(
            listOf(
                ImportRowIssue(2, "sale_price", ImportIssue.LIMIT),
                ImportRowIssue(2, "on_hand", ImportIssue.DECIMALS),
            ),
            preview.issues,
        )
    }

    @Test
    fun `every bad cell of a row is listed at once`() {
        val longName = "N".repeat(81)
        val preview = products(productRow(name = longName, unitName = "caja", price = "x"))

        assertEquals(
            listOf(
                ImportRowIssue(2, "name", ImportIssue.LENGTH),
                ImportRowIssue(2, "base_unit", ImportIssue.UNIT_UNKNOWN),
                ImportRowIssue(2, "sale_price", ImportIssue.AMOUNT),
            ),
            preview.issues,
        )
        assertEquals(1, preview.errorRows)
    }

    @Test
    fun `a missing name and an unknown stock mode are errors of their row`() {
        val preview = products(productRow(name = " ", mode = "COMPONENTS"))

        assertEquals(
            listOf(
                ImportRowIssue(2, "name", ImportIssue.MISSING),
                ImportRowIssue(2, "stock_mode", ImportIssue.INVALID_VALUE),
            ),
            preview.issues,
        )
    }

    @Test
    fun `a unit without decimals rejects a fractional stock`() {
        val counted = products(productRow(unitName = "Unidad", onHand = "1,5"))
        val byDefault = products(productRow(minStock = "0,5"))

        assertEquals(
            listOf(ImportRowIssue(2, "on_hand", ImportIssue.UNIT_NO_FRACTION)),
            counted.issues,
        )
        assertEquals(
            listOf(ImportRowIssue(2, "min_stock", ImportIssue.UNIT_NO_FRACTION)),
            byDefault.issues,
        )
    }

    @Test
    fun `a product without stock cannot bring stock`() {
        val preview = products(productRow(mode = "UNTRACKED", onHand = "3", minStock = "1"))

        assertEquals(
            listOf(
                ImportRowIssue(2, "on_hand", ImportIssue.QUANTITY),
                ImportRowIssue(2, "min_stock", ImportIssue.QUANTITY),
            ),
            preview.issues,
        )
    }

    @Test
    fun `repeated names and codes in the file keep the first row`() {
        val preview = products(
            productRow(name = "Panela", barcode = "111"),
            productRow(name = "PANELA"),
            productRow(name = "Sal", barcode = "111"),
        )

        assertEquals(listOf("Panela"), preview.ready.map { (it as ImportDraft.Product).name })
        assertEquals(
            listOf(
                ImportRowIssue(3, "name", ImportIssue.DUPLICATE),
                ImportRowIssue(4, "barcode", ImportIssue.DUPLICATE),
            ),
            preview.issues,
        )
    }

    @Test
    fun `a code used by another product is an error, and by the same product is not`() {
        val other = products(productRow(name = "Sal", barcode = "7700001"))
        val same = products(productRow(name = "Arroz 1 kg", barcode = "7700001"))

        assertEquals(
            listOf(ImportRowIssue(2, "barcode", ImportIssue.BARCODE_USED)),
            other.issues,
        )
        assertTrue(same.issues.isEmpty())
    }

    @Test
    fun `existing and archived products are told apart`() {
        val preview = products(
            productRow(name = "Arroz 1 KG"),
            productRow(name = "Jabón viejo"),
            productRow(name = "Nuevo"),
        )

        assertEquals(listOf(4), preview.ready.map { it.row })
        assertEquals(listOf(2), preview.existing.map { it.row })
        assertEquals(listOf(3), preview.locked.map { it.row })
        assertEquals(2, preview.existingRows)
        assertEquals(1, preview.toApply(ImportExistingPolicy.SKIP))
        assertEquals(2, preview.toApply(ImportExistingPolicy.UPDATE))
    }

    @Test
    fun `new products with stock and no cost are counted`() {
        val preview = products(
            productRow(name = "A", onHand = "2"),
            productRow(name = "B", onHand = "2", cost = "0"),
            productRow(name = "C", onHand = "2", cost = "1"),
            productRow(name = "Arroz 1 kg", onHand = "2"),
        )

        assertEquals(2, preview.stockWithoutCost)
    }

    @Test
    fun `a csv with thousands separators in the right places is read`() {
        val preview = products(productRow(price = "1.234.567,80"))
        val misplaced = products(productRow(price = "1.5"))

        assertEquals(
            Money(123_456_780L),
            (preview.ready.single() as ImportDraft.Product).salePrice,
        )
        assertEquals(
            listOf(ImportRowIssue(2, "sale_price", ImportIssue.AMOUNT)),
            misplaced.issues,
        )
    }

    @Test
    fun `a spreadsheet uses the point and forgives the binary drift`() {
        val spreadsheet = ImportNumberFormat(DecimalSeparator.COMMA, 2, spreadsheet = true)

        val preview = products(
            productRow(price = "0.30000000000000004", onHand = "2.0E0"),
            format = spreadsheet,
            separator = null,
        )

        val draft = preview.ready.single() as ImportDraft.Product
        assertEquals(Money(30L), draft.salePrice)
        assertEquals(QuantityMilli(2_000L), draft.onHand)
    }

    @Test
    fun `rows without anything in the matched columns are not counted`() {
        val columns = listOf("name", null)
        val reader = ImportRowReader(ImportTarget.PRODUCTS, columns, commaCsv, catalog)

        val preview = reader.read(sheet(listOf("", "solo esto"), listOf("Té", "")))

        assertEquals(listOf(3), preview.ready.map { it.row })
        assertTrue(preview.issues.isEmpty())
    }

    @Test
    fun `customers follow the rules of the customer form`() {
        val columns = listOf("name", "phone", "email", "notes", "price_list")
        val lists = listOf(ImportChoice(id = 3L, names = setOf("mayorista")))
        val reader = ImportRowReader(
            ImportTarget.CUSTOMERS,
            columns,
            commaCsv,
            ImportCatalog(existing = mapOf("ana" to false, "luis" to true), priceLists = lists),
        )

        val preview = reader.read(
            sheet(
                listOf("Marta", "300 000 0000", "marta@example.com", "", "Mayorista"),
                listOf("", "abc", "sin-arroba", "", "Otra"),
                listOf("Ana", "", "", "", ""),
                listOf("Luis", "", "", "", ""),
            ),
        )

        val marta = preview.ready.single() as ImportDraft.Contact
        assertEquals("marta@example.com", marta.email)
        assertEquals(3L, marta.priceListId)
        assertEquals(
            listOf(
                ImportRowIssue(3, "price_list", ImportIssue.INVALID_VALUE),
                ImportRowIssue(3, "name", ImportIssue.MISSING),
                ImportRowIssue(3, "phone", ImportIssue.PHONE),
                ImportRowIssue(3, "email", ImportIssue.EMAIL),
            ).toSet(),
            preview.issues.toSet(),
        )
        assertEquals(listOf(4), preview.existing.map { it.row })
        assertEquals(listOf(5), preview.locked.map { it.row })
    }

    @Test
    fun `suppliers read their payment days`() {
        val columns = listOf("name", "payment_terms_days")
        val reader = ImportRowReader(ImportTarget.SUPPLIERS, columns, commaCsv, ImportCatalog())

        val preview = reader.read(
            sheet(
                listOf("Molino", "30"),
                listOf("Granja", "treinta"),
                listOf("Huerta", "9999"),
                listOf("Vivero", "1234567"),
                listOf("Cosecha", "3/0"),
            ),
        )

        assertEquals(30, (preview.ready.single() as ImportDraft.Contact).paymentTermsDays)
        assertEquals(
            listOf(
                ImportRowIssue(3, "payment_terms_days", ImportIssue.INVALID_VALUE),
                ImportRowIssue(4, "payment_terms_days", ImportIssue.LIMIT),
                ImportRowIssue(5, "payment_terms_days", ImportIssue.LIMIT),
                ImportRowIssue(6, "payment_terms_days", ImportIssue.INVALID_VALUE),
            ),
            preview.issues,
        )
    }

    @Test
    fun `the price index reads months and signed percentages`() {
        val columns = listOf("month", "change_percent")
        val reader = ImportRowReader(
            ImportTarget.PRICE_INDEX,
            columns,
            commaCsv,
            ImportCatalog(existing = mapOf("2026-01" to false)),
        )

        val preview = reader.read(
            sheet(
                listOf("2026-01", "1,25"),
                listOf("2026-02", "-0,5"),
                listOf("2026-02", "3"),
                listOf("febrero", "x"),
                listOf("2026-04", "-100,01"),
                listOf("", ""),
                listOf("2026-05", ""),
                listOf("", "2"),
            ),
        )

        assertEquals(
            listOf(ImportDraft.PriceIndex(3, "2026-02", BasisPoints(-50L))),
            preview.ready,
        )
        assertEquals(
            listOf(ImportDraft.PriceIndex(2, "2026-01", BasisPoints(125L))),
            preview.existing,
        )
        assertEquals(
            listOf(
                ImportRowIssue(4, "month", ImportIssue.DUPLICATE),
                ImportRowIssue(5, "month", ImportIssue.MONTH),
                ImportRowIssue(5, "change_percent", ImportIssue.INVALID_VALUE),
                ImportRowIssue(6, "change_percent", ImportIssue.LIMIT),
                ImportRowIssue(8, "change_percent", ImportIssue.MISSING),
                ImportRowIssue(9, "month", ImportIssue.MISSING),
            ),
            preview.issues,
        )
    }

    @Test
    fun `the mapping must bring every required field once`() {
        val missing = ImportMappingRules.check(listOf("category", null), ImportTarget.PRODUCTS)
        val twice = ImportMappingRules.check(listOf("name", "name"), ImportTarget.PRODUCTS)
        val ready = ImportMappingRules.check(listOf("name", null), ImportTarget.PRODUCTS)

        assertEquals(listOf("name"), missing.missing)
        assertEquals(listOf("name"), twice.duplicated)
        assertTrue(ready.isComplete)
        assertEquals(false, missing.isComplete)
        assertEquals(false, twice.isComplete)
    }
}
