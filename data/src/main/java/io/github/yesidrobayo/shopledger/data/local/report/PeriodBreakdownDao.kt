package io.github.yesidrobayo.shopledger.data.local.report

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

/**
 * Los desgloses de un período para las estadísticas. Solo lee.
 *
 * Cada consulta parte de las ventas o los gastos activos del rango por su índice
 * `(status, business_date)`, y llega a líneas y pagos por la clave de su documento.
 */
@Dao
internal interface PeriodBreakdownDao {
    @Query(PRODUCT_SALES_SQL)
    fun observeProductSales(startDate: String, endDate: String): Flow<List<ProductSalesRow>>

    @Query(PAYMENT_METHODS_SQL)
    fun observePaymentMethods(startDate: String, endDate: String): Flow<List<PaymentMethodRow>>

    @Query(EXPENSE_CATEGORIES_SQL)
    fun observeExpenseCategories(startDate: String, endDate: String): Flow<List<ExpenseCategoryRow>>

    @Query(NOTICE_COUNTS_SQL)
    fun observeNoticeCounts(
        startDate: String,
        endDate: String,
        periodEndMs: Long,
    ): Flow<NoticeCountsRow>
}

/** Lo vendido de un producto, o de las variantes de un padre, en el rango. */
internal data class ProductSalesRow(
    @ColumnInfo(name = "product_id") val productId: Long,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "base_unit_id") val baseUnitId: Long,
    @ColumnInfo(name = "quantity_milli") val quantityMilli: Long,
    @ColumnInfo(name = "net_sales") val netSales: Long,
    @ColumnInfo(name = "cost") val cost: Long,
)

/** Lo cobrado con un método, por el nombre que quedó en cada pago. */
internal data class PaymentMethodRow(
    @ColumnInfo(name = "method_name") val methodName: String,
    @ColumnInfo(name = "method_kind") val methodKind: String,
    @ColumnInfo(name = "sales_count") val salesCount: Long,
    @ColumnInfo(name = "amount") val amount: Long,
    @ColumnInfo(name = "fees") val fees: Long,
)

/** Lo gastado en una categoría. */
internal data class ExpenseCategoryRow(
    @ColumnInfo(name = "category_id") val categoryId: Long,
    @ColumnInfo(name = "name") val name: String?,
    @ColumnInfo(name = "system_key") val systemKey: String?,
    @ColumnInfo(name = "amount") val amount: Long,
)

/** Los conteos de los avisos del rango. */
internal data class NoticeCountsRow(
    @ColumnInfo(name = "sales_without_cost") val salesWithoutCost: Long,
    @ColumnInfo(name = "negative_stock") val negativeStock: Long,
    @ColumnInfo(name = "voided_after_period") val voidedAfterPeriod: Long,
    @ColumnInfo(name = "late_records") val lateRecords: Long,
    @ColumnInfo(name = "voided_count") val voidedCount: Long,
)

// Las consultas viven aparte para que la prueba del plan de consulta mire la misma sentencia que
// corre la app.

/** Las ventas activas del rango, por su índice de fecha. */
private const val ACTIVE_SALES_IN_RANGE =
    "s.status = 'ACTIVE' AND s.business_date BETWEEN :startDate AND :endDate"

/**
 * Lo vendido por producto, con lo devuelto de esas mismas líneas restado, en cualquier fecha: el
 * ranking dice qué se vendió y se quedó vendido. El costo devuelto es solo el de lo que volvió al
 * stock. Las variantes se suman en su padre, con su nombre y su unidad.
 */
internal const val PRODUCT_SALES_SQL =
    "SELECT g.id AS product_id, g.name AS name, g.base_unit_id AS base_unit_id, " +
        "SUM(sl.quantity_milli - COALESCE(r.quantity_milli, 0)) AS quantity_milli, " +
        "SUM(sl.net_amount - COALESCE(r.net_amount, 0)) AS net_sales, " +
        "SUM(sl.line_cost - COALESCE(r.line_cost, 0)) AS cost " +
        "FROM sales s JOIN sale_lines sl ON sl.sale_id = s.id " +
        "JOIN products p ON p.id = sl.product_id " +
        "JOIN products g ON g.id = COALESCE(p.parent_product_id, p.id) " +
        "LEFT JOIN (SELECT rl.sale_line_id, SUM(rl.quantity_milli) AS quantity_milli, " +
        "SUM(rl.net_amount) AS net_amount, " +
        "SUM(CASE WHEN rl.restock = 1 THEN rl.line_cost ELSE 0 END) AS line_cost " +
        "FROM sale_return_lines rl JOIN sale_returns rr ON rr.id = rl.sale_return_id " +
        "WHERE rr.status = 'ACTIVE' GROUP BY rl.sale_line_id) r ON r.sale_line_id = sl.id " +
        "WHERE $ACTIVE_SALES_IN_RANGE GROUP BY g.id"

/**
 * Lo cobrado por método, agrupado por el nombre que quedó en cada pago: así un método renombrado
 * conserva su historia. La suma de todos es lo que se cobró en las ventas, propina y redondeo
 * incluidos.
 */
internal const val PAYMENT_METHODS_SQL =
    "SELECT sp.method_name_snapshot AS method_name, MAX(sp.method_kind_snapshot) AS method_kind, " +
        "COUNT(DISTINCT sp.sale_id) AS sales_count, SUM(sp.amount) AS amount, " +
        "SUM(sp.fee_amount) AS fees " +
        "FROM sales s JOIN sale_payments sp ON sp.sale_id = s.id " +
        "WHERE $ACTIVE_SALES_IN_RANGE GROUP BY sp.method_name_snapshot " +
        "ORDER BY amount DESC, method_name"

/** Lo gastado por categoría, de mayor a menor. */
internal const val EXPENSE_CATEGORIES_SQL =
    "SELECT e.expense_category_id AS category_id, c.name AS name, c.system_key AS system_key, " +
        "SUM(e.amount) AS amount FROM expenses e " +
        "JOIN expense_categories c ON c.id = e.expense_category_id " +
        "WHERE e.status = 'ACTIVE' AND e.business_date BETWEEN :startDate AND :endDate " +
        "GROUP BY e.expense_category_id ORDER BY amount DESC, category_id"

/**
 * Los avisos del rango.
 *
 * - Ventas con alguna línea vendida sin costo: una línea con importe y costo cero. Un costo menor
 *   que una unidad menor por unidad sigue siendo costo, por eso se mira el costo de la línea.
 * - Productos vigentes con stock negativo, hoy.
 * - Ventas y gastos anulados desde el fin del período, y ajustes del período cuya anulación se
 *   escribió después. Una anulación de ajuste no tiene fecha de anulación propia: cuenta su
 *   registro.
 * - Ventas y gastos activos del período registrados desde su fin.
 * - Ventas y gastos anulados con fecha en el período.
 */
internal const val NOTICE_COUNTS_SQL =
    "SELECT " +
        "(SELECT COUNT(DISTINCT s.id) FROM sales s JOIN sale_lines sl ON sl.sale_id = s.id " +
        "WHERE $ACTIVE_SALES_IN_RANGE AND sl.line_cost = 0 AND sl.net_amount > 0) " +
        "AS sales_without_cost, " +
        "(SELECT COUNT(*) FROM products WHERE archived_at IS NULL AND on_hand_milli < 0) " +
        "AS negative_stock, " +
        "(SELECT COUNT(*) FROM sales WHERE status = 'VOIDED' " +
        "AND business_date BETWEEN :startDate AND :endDate AND voided_at >= :periodEndMs) + " +
        "(SELECT COUNT(*) FROM expenses WHERE status = 'VOIDED' " +
        "AND business_date BETWEEN :startDate AND :endDate AND voided_at >= :periodEndMs) + " +
        "(SELECT COUNT(*) FROM stock_movements v JOIN stock_movements m " +
        "ON m.id = v.reverses_movement_id WHERE v.type = 'ADJUSTMENT_VOID' " +
        "AND v.created_at >= :periodEndMs AND m.type = 'ADJUSTMENT' " +
        "AND m.business_date BETWEEN :startDate AND :endDate) AS voided_after_period, " +
        "(SELECT COUNT(*) FROM sales WHERE status = 'ACTIVE' " +
        "AND business_date BETWEEN :startDate AND :endDate AND created_at >= :periodEndMs) + " +
        "(SELECT COUNT(*) FROM expenses WHERE status = 'ACTIVE' " +
        "AND business_date BETWEEN :startDate AND :endDate AND created_at >= :periodEndMs) " +
        "AS late_records, " +
        "(SELECT COUNT(*) FROM sales WHERE status = 'VOIDED' " +
        "AND business_date BETWEEN :startDate AND :endDate) + " +
        "(SELECT COUNT(*) FROM expenses WHERE status = 'VOIDED' " +
        "AND business_date BETWEEN :startDate AND :endDate) AS voided_count"
