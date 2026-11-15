package io.github.yesidrobayo.shopledger.data.local.report

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

/**
 * Lo que el análisis de `Números` lee de un período, además de lo vendido por producto, que lee
 * `PeriodBreakdownDao`. Solo lee, y todo se suma y se cuenta en la base.
 *
 * `startDate` y `endDate` son fechas `AAAA-MM-DD` y entran las dos. Las salidas por venta se
 * buscan por el índice `(type, business_date)` del libro de stock, y su compensación por el de
 * `reverses_movement_id`; las ventas, por su índice `(status, business_date)`.
 */
@Dao
internal interface AnalysisReportDao {
    /**
     * Los primeros [limit] productos quietos del rango, del de más valor en stock al de menos, con
     * un empate por id. Vuelve a emitir con cada cambio en productos o en el libro de stock.
     */
    @Query(IDLE_PRODUCTS_SQL)
    fun observeIdleProducts(startDate: String, endDate: String, limit: Int): Flow<List<IdleRow>>

    /** Cuántos productos quietos tiene el rango, con las mismas condiciones. */
    @Query(IDLE_PRODUCTS_COUNT_SQL)
    fun observeIdleProductsCount(startDate: String, endDate: String): Flow<Long>

    /**
     * Los primeros [limit] clientes del rango por lo vendido sin impuesto en sus ventas activas.
     * Vuelve a emitir con cada cambio en ventas o clientes.
     */
    @Query(TOP_CUSTOMERS_SQL)
    fun observeTopCustomers(
        startDate: String,
        endDate: String,
        limit: Int,
    ): Flow<List<CustomerSalesRow>>
}

/** Un producto quieto, con su stock en milésimas y su valor en unidad menor. */
internal data class IdleRow(
    @ColumnInfo(name = "product_id") val productId: Long,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "base_unit_id") val baseUnitId: Long,
    @ColumnInfo(name = "on_hand_milli") val onHandMilli: Long,
    @ColumnInfo(name = "stock_value") val stockValue: Long,
)

/** Lo que compró un cliente en el rango. Un cliente anonimizado no tiene nombre. */
internal data class CustomerSalesRow(
    @ColumnInfo(name = "customer_id") val customerId: Long,
    @ColumnInfo(name = "name") val name: String?,
    @ColumnInfo(name = "sales_count") val salesCount: Long,
    @ColumnInfo(name = "net_sales") val netSales: Long,
)

// Las consultas viven aparte para que la prueba del plan de consulta mire la misma sentencia que
// corre la app.

/**
 * Un producto quieto: vigente, con stock, que se vende, y sin ninguna fila `SALE` sin compensar
 * en el rango. Así cuentan también los que salieron dentro de un combo, y no cuenta una venta
 * anulada. Los productos vendidos se buscan una sola vez, no producto por producto.
 */
private const val IDLE_PRODUCTS_WHERE =
    "WHERE p.archived_at IS NULL AND p.on_hand_milli > 0 AND p.usage <> 'INPUT' " +
        "AND p.id NOT IN (SELECT sm.product_id FROM stock_movements sm " +
        "WHERE sm.type = 'SALE' AND sm.business_date BETWEEN :startDate AND :endDate " +
        "AND NOT EXISTS (SELECT 1 FROM stock_movements r WHERE r.reverses_movement_id = sm.id))"

/** La consulta de [AnalysisReportDao.observeIdleProducts]. */
internal const val IDLE_PRODUCTS_SQL =
    "SELECT p.id AS product_id, p.name AS name, p.base_unit_id AS base_unit_id, " +
        "p.on_hand_milli AS on_hand_milli, p.stock_value AS stock_value FROM products p " +
        "$IDLE_PRODUCTS_WHERE ORDER BY p.stock_value DESC, p.id LIMIT :limit"

/** La consulta de [AnalysisReportDao.observeIdleProductsCount]. */
internal const val IDLE_PRODUCTS_COUNT_SQL = "SELECT COUNT(*) FROM products p $IDLE_PRODUCTS_WHERE"

/**
 * La consulta de [AnalysisReportDao.observeTopCustomers]: `net_total` de cada venta activa del
 * rango con cliente, sumado por cliente.
 */
internal const val TOP_CUSTOMERS_SQL =
    "SELECT s.customer_id AS customer_id, c.name AS name, COUNT(*) AS sales_count, " +
        "SUM(s.net_total) AS net_sales FROM sales s JOIN customers c ON c.id = s.customer_id " +
        "WHERE s.status = 'ACTIVE' AND s.business_date BETWEEN :startDate AND :endDate " +
        "GROUP BY s.customer_id ORDER BY net_sales DESC, s.customer_id LIMIT :limit"
