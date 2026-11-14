package io.github.yesidrobayo.shopledger.data.export

import androidx.sqlite.SQLiteStatement
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.export.CsvCell
import io.github.yesidrobayo.shopledger.domain.model.export.CsvExportTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Las consultas de cada archivo de la exportación, sin base: cada una lee todas las columnas de su
 * `SELECT`, una sola vez y en orden, y da tantas celdas como columnas tiene su archivo. Así una
 * columna agregada al archivo y olvidada en la consulta, o al revés, falla aquí y no en un
 * teléfono.
 * La prueba sobre la base real está en `CsvExportOnDatabaseTest`.
 */
class CsvTableQueriesTest {
    @Test
    fun `every query reads each selected column once and fills every column of its file`() {
        CsvExportTable.entries.forEach { table ->
            val query = CsvTableQueries.of(table)
            val row = row(FakeStatement())

            val cells = query.cells(row)

            assertEquals(table.key, table.columns.size, cells.size)
            assertEquals(table.key, selectedColumns(query.sqlFor(ranged = false)), row.columnsRead)
        }
    }

    @Test
    fun `a row of nulls gives empty cells and never fails`() {
        CsvExportTable.entries.forEach { table ->
            val cells = CsvTableQueries.of(table).cells(row(FakeStatement(allNull = true)))

            assertEquals(table.key, table.columns.size, cells.size)
            // La ganancia de una venta es una cuenta: con todo nulo da cero, no una celda vacía.
            val computed = cells.filter { it != CsvCell.Empty }
            assertTrue(table.key, computed.all { it == CsvCell.Amount(Money.ZERO) })
        }
    }

    @Test
    fun `documents and ledgers follow the date range and catalogs never do`() {
        CsvExportTable.entries.forEach { table ->
            val query = CsvTableQueries.of(table)
            val ranged = query.sqlFor(ranged = true)
            if (table in WHOLE) {
                assertFalse(table.key, query.usesRange(ranged = true))
                assertEquals(table.key, query.sqlFor(ranged = false), ranged)
            } else {
                assertTrue(table.key, query.usesRange(ranged = true))
                assertTrue(table.key, "WHERE" in ranged && "?1" in ranged && "?2" in ranged)
                // La condición va antes del orden: las filas siguen saliendo por número.
                assertTrue(table.key, ranged.indexOf("WHERE") < ranged.indexOf("ORDER BY"))
            }
        }
    }

    @Test
    fun `an account balance uses the decimals of its own currency`() {
        val columns = CsvExportTable.MONEY_ACCOUNTS.columns
        // id, clave, clave y nombre, clase, moneda, decimales, saldo, decimales de nuevo.
        val statement = FakeStatement(longs = mapOf(6 to 0L, 7 to 1_234L, 8 to 0L))

        val cells = CsvTableQueries.of(CsvExportTable.MONEY_ACCOUNTS).cells(row(statement))

        assertEquals(CsvCell.Count(0), cells[columns.indexOf("currency_decimals")])
        assertEquals(CsvCell.Scaled(1_234, 0), cells[columns.indexOf("balance")])
    }

    @Test
    fun `decimals out of range keep the stored integer`() {
        val columns = CsvExportTable.MONEY_ACCOUNTS.columns
        val statement = FakeStatement(longs = mapOf(7 to 1_234L, 8 to 9L))

        val cells = CsvTableQueries.of(CsvExportTable.MONEY_ACCOUNTS).cells(row(statement))

        assertEquals(CsvCell.Count(1_234), cells[columns.indexOf("balance")])
    }

    @Test
    fun `a stored rate comes out with six decimals`() {
        val columns = CsvExportTable.FX_RATES.columns
        val statement = FakeStatement(longs = mapOf(2 to 4_000_500_000L))

        val cells = CsvTableQueries.of(CsvExportTable.FX_RATES).cells(row(statement))

        assertEquals(CsvCell.Scaled(4_000_500_000L, 6), cells[columns.indexOf("fx_rate")])
    }

    @Test
    fun `an unnamed initial row takes its name from its key`() {
        val columns = CsvExportTable.LOCATIONS.columns
        // La ubicación inicial: con su clave y sin nombre escrito.
        val statement = FakeStatement(texts = mapOf(1 to "MAIN", 2 to "MAIN"), nulls = setOf(3))

        val cells = CsvTableQueries.of(CsvExportTable.LOCATIONS).cells(row(statement))

        assertEquals(CsvCell.Text("MAIN"), cells[columns.indexOf("system_key")])
        assertEquals(CsvCell.Text("seed:MAIN"), cells[columns.indexOf("name")])
    }

    private fun row(statement: SQLiteStatement) = CsvRow(statement) { _, key -> "seed:$key" }

    /** Las expresiones del `SELECT` principal: ninguna lleva comas propias. */
    private fun selectedColumns(sql: String): Int =
        sql.substringAfter("SELECT ").substringBefore("\nFROM ").split(',').size

    /**
     * Una fila inventada: cada columna vale lo que diga [longs] o [texts], o un valor fijo, y es
     * nula si está en [nulls] o con [allNull].
     */
    private class FakeStatement(
        private val longs: Map<Int, Long> = emptyMap(),
        private val texts: Map<Int, String> = emptyMap(),
        private val nulls: Set<Int> = emptySet(),
        private val allNull: Boolean = false,
    ) : SQLiteStatement {
        override fun isNull(index: Int): Boolean = allNull || index in nulls

        override fun getLong(index: Int): Long = longs[index] ?: 1L

        override fun getText(index: Int): String = texts[index] ?: "text"

        override fun bindBlob(index: Int, value: ByteArray) = unused()

        override fun bindDouble(index: Int, value: Double) = unused()

        override fun bindLong(index: Int, value: Long) = unused()

        override fun bindText(index: Int, value: String) = unused()

        override fun bindNull(index: Int) = unused()

        override fun getBlob(index: Int): ByteArray = unused()

        override fun getDouble(index: Int): Double = unused()

        override fun getColumnCount(): Int = unused()

        override fun getColumnName(index: Int): String = unused()

        override fun getColumnType(index: Int): Int = unused()

        override fun step(): Boolean = unused()

        override fun reset() = unused()

        override fun clearBindings() = unused()

        override fun close() = Unit

        private fun unused(): Nothing = throw UnsupportedOperationException()
    }

    private companion object {
        /** Los catálogos y saldos: van completos con cualquier rango de fechas. */
        val WHOLE =
            setOf(
                CsvExportTable.PRODUCTS,
                CsvExportTable.SUPPLIERS,
                CsvExportTable.TAX_RATES,
                CsvExportTable.PAYMENT_METHODS,
                CsvExportTable.CUSTOMERS,
                CsvExportTable.MONEY_ACCOUNTS,
                CsvExportTable.PRICE_LISTS,
                CsvExportTable.PRODUCT_PRICES,
                CsvExportTable.PRICE_INDEX,
                CsvExportTable.UNITS,
                CsvExportTable.PRODUCT_UNITS,
                CsvExportTable.PRODUCT_COMPONENTS,
                CsvExportTable.PRODUCT_MODIFIERS,
                CsvExportTable.CUSTOM_FIELDS,
                CsvExportTable.PRODUCT_CUSTOM_VALUES,
                CsvExportTable.TAGS,
                CsvExportTable.PRODUCT_TAGS,
                CsvExportTable.MEDIA_ASSETS,
                CsvExportTable.LOCATIONS,
                CsvExportTable.STOCK_BALANCES,
                CsvExportTable.STOCK_LOTS,
                CsvExportTable.DOCUMENT_ATTACHMENTS,
                CsvExportTable.BUSINESS_GOALS,
                CsvExportTable.RECURRING_EXPENSES,
            )
    }
}
