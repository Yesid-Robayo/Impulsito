package io.github.yesidrobayo.shopledger.data.local.report

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

/**
 * Las sumas de ganancia de un período, por fecha de negocio. Solo lee.
 *
 * `startDate` y `endDate` son fechas `AAAA-MM-DD` y entran las dos. Cada tabla se busca por su
 * índice de fecha: `(status, business_date)` en ventas, devoluciones y gastos, y
 * `(type, business_date)` en el libro de stock. Los pagos se alcanzan desde sus ventas, por
 * `sale_id`. Así ninguna suma recorre años de historia para leer un día.
 *
 * Solo suma: las restas, el margen y la ganancia los calcula `:domain` con sus reglas.
 */
@Dao
internal interface PeriodReportDao {
    /**
     * Las sumas de los documentos activos con fecha entre [startDate] y [endDate]. Vuelve a emitir
     * con cada cambio en las tablas que lee.
     */
    @Query(PERIOD_TOTALS_SQL)
    fun observeTotals(startDate: String, endDate: String): Flow<PeriodTotalsRow>

    /**
     * Las mismas sumas por cada día del rango que tiene algún documento, ordenadas por fecha. Los
     * días sin documentos no aparecen.
     */
    @Query(DAILY_TOTALS_SQL)
    fun observeDailyTotals(startDate: String, endDate: String): Flow<List<DailyTotalsRow>>

    /**
     * La fecha más reciente con una venta o un gasto activos, `AAAA-MM-DD`, o vacía si no hay
     * ninguno. Cada tabla da su máximo desde el final de su índice `(status, business_date)`, sin
     * recorrerla.
     */
    @Query(LAST_ACTIVE_DATE_SQL)
    fun observeLastActiveDate(): Flow<String>
}

/** Las sumas de [PeriodReportDao.observeTotals], en unidad menor, y el número de ventas. */
internal data class PeriodTotalsRow(
    @ColumnInfo(name = "sales_net_total") val salesNetTotal: Long,
    @ColumnInfo(name = "sales_total_cost") val salesTotalCost: Long,
    @ColumnInfo(name = "sales_count") val salesCount: Long,
    @ColumnInfo(name = "cash_rounding_total") val cashRoundingTotal: Long,
    @ColumnInfo(name = "returns_net_refund") val returnsNetRefund: Long,
    @ColumnInfo(name = "returns_cost_returned") val returnsCostReturned: Long,
    @ColumnInfo(name = "recorded_expenses") val recordedExpenses: Long,
    @ColumnInfo(name = "net_shrinkage") val netShrinkage: Long,
    @ColumnInfo(name = "payment_fees") val paymentFees: Long,
)

/** Las sumas de un día de [PeriodReportDao.observeDailyTotals]. */
internal data class DailyTotalsRow(
    @ColumnInfo(name = "business_date") val businessDate: String,
    @ColumnInfo(name = "sales_net_total") val salesNetTotal: Long,
    @ColumnInfo(name = "sales_total_cost") val salesTotalCost: Long,
    @ColumnInfo(name = "sales_count") val salesCount: Long,
    @ColumnInfo(name = "cash_rounding_total") val cashRoundingTotal: Long,
    @ColumnInfo(name = "returns_net_refund") val returnsNetRefund: Long,
    @ColumnInfo(name = "returns_cost_returned") val returnsCostReturned: Long,
    @ColumnInfo(name = "recorded_expenses") val recordedExpenses: Long,
    @ColumnInfo(name = "net_shrinkage") val netShrinkage: Long,
    @ColumnInfo(name = "payment_fees") val paymentFees: Long,
)

// Las consultas viven aparte para que la prueba del plan de consulta mire la misma sentencia que
// corre la app.

/**
 * Lo que cuenta como merma: los ajustes del libro que nadie compensó. Anular un ajuste escribe un
 * `ADJUSTMENT_VOID` con la fecha del día en que se anula, pero la merma deja de contar en la fecha
 * del ajuste, porque una anulación saca al hecho de su período original. El compensatorio no es un
 * `ADJUSTMENT`, así que tampoco suma en su propia fecha.
 */
private const val LIVE_ADJUSTMENT =
    "m.type = 'ADJUSTMENT' " +
        "AND NOT EXISTS (SELECT 1 FROM stock_movements r WHERE r.reverses_movement_id = m.id)"

/**
 * La consulta de [PeriodReportDao.observeTotals]: una tabla derivada de una fila por tabla, para
 * leer cada rango una sola vez.
 *
 * - Ventas: total sin impuesto, costo congelado en sus líneas, cuántas y su redondeo de efectivo,
 *   con signo. La propina no se lee: no es de quien vende.
 * - Devoluciones: lo reembolsado sin impuesto y el costo de lo que volvió, en su propia fecha.
 * - Gastos registrados.
 * - Merma: el valor de los ajustes vivos, con el signo cambiado; negativa si sobró más de lo que
 *   se perdió.
 * - Comisiones de los pagos de las ventas activas, en la fecha de su venta.
 */
internal const val PERIOD_TOTALS_SQL =
    "SELECT sale_sums.*, return_sums.*, expense_sums.*, shrinkage_sums.*, fee_sums.* FROM " +
        "(SELECT COALESCE(SUM(net_total), 0) AS sales_net_total, " +
        "COALESCE(SUM(total_cost), 0) AS sales_total_cost, COUNT(*) AS sales_count, " +
        "COALESCE(SUM(cash_rounding_amount), 0) AS cash_rounding_total " +
        "FROM sales WHERE status = 'ACTIVE' " +
        "AND business_date BETWEEN :startDate AND :endDate) AS sale_sums, " +
        "(SELECT COALESCE(SUM(net_refund), 0) AS returns_net_refund, " +
        "COALESCE(SUM(cost_returned), 0) AS returns_cost_returned " +
        "FROM sale_returns WHERE status = 'ACTIVE' " +
        "AND business_date BETWEEN :startDate AND :endDate) AS return_sums, " +
        "(SELECT COALESCE(SUM(amount), 0) AS recorded_expenses " +
        "FROM expenses WHERE status = 'ACTIVE' " +
        "AND business_date BETWEEN :startDate AND :endDate) AS expense_sums, " +
        "(SELECT COALESCE(-SUM(m.total_cost), 0) AS net_shrinkage FROM stock_movements m " +
        "WHERE $LIVE_ADJUSTMENT AND m.business_date BETWEEN :startDate AND :endDate) " +
        "AS shrinkage_sums, " +
        "(SELECT COALESCE(SUM(sp.fee_amount), 0) AS payment_fees " +
        "FROM sales sa JOIN sale_payments sp ON sp.sale_id = sa.id " +
        "WHERE sa.status = 'ACTIVE' " +
        "AND sa.business_date BETWEEN :startDate AND :endDate) AS fee_sums"

/**
 * La consulta de [PeriodReportDao.observeDailyTotals]: las mismas fuentes, una fila por documento
 * con su fecha, agrupadas por día.
 */
internal const val DAILY_TOTALS_SQL =
    "SELECT business_date, SUM(sales_net_total) AS sales_net_total, " +
        "SUM(sales_total_cost) AS sales_total_cost, SUM(sales_count) AS sales_count, " +
        "SUM(cash_rounding_total) AS cash_rounding_total, " +
        "SUM(returns_net_refund) AS returns_net_refund, " +
        "SUM(returns_cost_returned) AS returns_cost_returned, " +
        "SUM(recorded_expenses) AS recorded_expenses, SUM(net_shrinkage) AS net_shrinkage, " +
        "SUM(payment_fees) AS payment_fees FROM (" +
        "SELECT business_date, net_total AS sales_net_total, total_cost AS sales_total_cost, " +
        "1 AS sales_count, cash_rounding_amount AS cash_rounding_total, " +
        "0 AS returns_net_refund, 0 AS returns_cost_returned, 0 AS recorded_expenses, " +
        "0 AS net_shrinkage, 0 AS payment_fees " +
        "FROM sales WHERE status = 'ACTIVE' AND business_date BETWEEN :startDate AND :endDate " +
        "UNION ALL SELECT business_date, 0, 0, 0, 0, net_refund, cost_returned, 0, 0, 0 " +
        "FROM sale_returns WHERE status = 'ACTIVE' " +
        "AND business_date BETWEEN :startDate AND :endDate " +
        "UNION ALL SELECT business_date, 0, 0, 0, 0, 0, 0, amount, 0, 0 " +
        "FROM expenses WHERE status = 'ACTIVE' " +
        "AND business_date BETWEEN :startDate AND :endDate " +
        "UNION ALL SELECT m.business_date, 0, 0, 0, 0, 0, 0, 0, -m.total_cost, 0 " +
        "FROM stock_movements m " +
        "WHERE $LIVE_ADJUSTMENT AND m.business_date BETWEEN :startDate AND :endDate " +
        "UNION ALL SELECT sa.business_date, 0, 0, 0, 0, 0, 0, 0, 0, sp.fee_amount " +
        "FROM sales sa JOIN sale_payments sp ON sp.sale_id = sa.id " +
        "WHERE sa.status = 'ACTIVE' AND sa.business_date BETWEEN :startDate AND :endDate" +
        ") GROUP BY business_date ORDER BY business_date"

/**
 * La consulta de [PeriodReportDao.observeLastActiveDate]. `MAX` de dos valores da nulo si uno lo
 * es, y por eso una tabla sin filas aporta el texto vacío, que ordena antes que cualquier fecha.
 */
internal const val LAST_ACTIVE_DATE_SQL =
    "SELECT MAX(" +
        "COALESCE((SELECT MAX(business_date) FROM sales WHERE status = 'ACTIVE'), ''), " +
        "COALESCE((SELECT MAX(business_date) FROM expenses WHERE status = 'ACTIVE'), '')" +
        ") AS last_active_date"
