package io.github.yesidrobayo.shopledger.domain.model.export

import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** El nombre sugerido de la exportación y las claves estables de sus archivos y columnas. */
class CsvExportModelTest {
    @Test
    fun `the suggested name uses the business time`() {
        val at = Instant.parse("2026-01-02T03:04:00Z")
        val zone = requireNotNull(BusinessTimeZone.of("Etc/GMT+5"))
        // A las 3:04 en UTC, en el negocio todavía es el día anterior.
        assertEquals("App-export-20260101-2204.zip", CsvExportFileName.of("App", at, zone))
    }

    @Test
    fun `without a business zone the suggested name uses utc`() {
        val at = Instant.parse("2026-11-12T09:05:00Z")
        assertEquals("App-export-20261112-0905.zip", CsvExportFileName.of("App", at, null))
    }

    @Test
    fun `the files keep the documented keys in their order`() {
        // Los nueve primeros, y después los de la guía de redacción, cada tabla con sus líneas.
        assertEquals(
            listOf(
                "products",
                "suppliers",
                "sales",
                "sale_lines",
                "purchases",
                "purchase_lines",
                "stock_movements",
                "expenses",
                "price_history",
                "tax_rates",
                "payment_methods",
                "sale_payments",
                "customers",
                "customer_ledger",
                "loyalty_ledger",
                "money_accounts",
                "money_movements",
                "supplier_ledger",
                "cash_sessions",
                "cash_session_counts",
                "sale_returns",
                "sale_return_lines",
                "purchase_returns",
                "purchase_return_lines",
                "price_lists",
                "product_prices",
                "fx_rates",
                "price_index",
                "units",
                "product_units",
                "product_components",
                "product_modifiers",
                "custom_fields",
                "product_custom_values",
                "tags",
                "product_tags",
                "media_assets",
                "sale_line_components",
                "locations",
                "stock_balances",
                "stock_lots",
                "stock_transfers",
                "stock_transfer_lines",
                "production_batches",
                "production_batch_lines",
                "count_sessions",
                "count_session_lines",
                "quotes",
                "quote_lines",
                "orders",
                "order_lines",
                "sale_fiscal_marks",
                "document_attachments",
                "business_goals",
                "recurring_expenses",
            ),
            CsvExportTable.entries.map { it.key },
        )
    }

    @Test
    fun `every column key is stable snake case and unique in its file`() {
        val stableKey = Regex("[a-z][a-z0-9_]*")
        CsvExportTable.entries.forEach { table ->
            assertEquals(table.columns.size, table.columns.toSet().size)
            table.columns.forEach { assertTrue(it, stableKey.matches(it)) }
        }
    }

    @Test
    fun `every record file starts with its number and lines name their document`() {
        val numbered = CsvExportTable.entries.drop(CORE_FILES)
        numbered.forEach { assertEquals(it.key, "id", it.columns.first()) }
        val lines =
            mapOf(
                CsvExportTable.SALE_RETURN_LINES to "sale_return_id",
                CsvExportTable.PURCHASE_RETURN_LINES to "purchase_return_id",
                CsvExportTable.STOCK_TRANSFER_LINES to "transfer_id",
                CsvExportTable.PRODUCTION_BATCH_LINES to "production_batch_id",
                CsvExportTable.COUNT_SESSION_LINES to "count_session_id",
                CsvExportTable.QUOTE_LINES to "quote_id",
                CsvExportTable.ORDER_LINES to "order_id",
                CsvExportTable.CASH_SESSION_COUNTS to "cash_session_id",
            )
        lines.forEach { (table, parent) -> assertEquals(table.key, parent, table.columns[1]) }
    }

    @Test
    fun `quote and order lines have the same columns`() {
        val quote = CsvExportTable.QUOTE_LINES.columns
        assertEquals(quote.drop(2), CsvExportTable.ORDER_LINES.columns.drop(2))
    }

    @Test
    fun `sales carry the profit and the void columns`() {
        val sales = CsvExportTable.SALES.columns
        assertEquals("sale_id", sales.first())
        assertEquals(listOf("status", "voided_at", "void_reason", "void_note"), sales.takeLast(4))
        assertTrue("sales_profit" in sales)
    }

    @Test
    fun `progress and results keep their values`() {
        assertEquals(CsvExportProgress(2, 9), CsvExportProgress(2, 9))
        assertEquals(9, CsvExport.Saved(files = 9, rows = 120L).files)
        assertEquals(120L, CsvExport.Saved(files = 9, rows = 120L).rows)
    }

    private companion object {
        /** Los archivos de la primera exportación, que no empiezan con su número. */
        const val CORE_FILES = 9
    }
}
