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
}
