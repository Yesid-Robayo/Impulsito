package io.github.yesidrobayo.shopledger.data.local.report

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

/** Las sumas de ventas y devoluciones de un período. Solo lee. */
@Dao
internal interface SalesReportDao {
    /**
     * Las sumas de los documentos activos con fecha de negocio entre [startDate] y [endDate], las
     * dos incluidas: lo vendido sin impuesto, su costo y cuántas ventas, y lo devuelto con su
     * costo. Vuelve a emitir con cada cambio en ventas o devoluciones.
     *
     * Suma la cabecera de cada documento, que ya es la suma de sus líneas con el costo congelado al
     * vender. Cada subconsulta filtra por `(status, business_date)`, que es su índice.
     */
    @Query(SALES_TOTALS_SQL)
    fun observeTotals(startDate: String, endDate: String): Flow<SalesTotalsRow>
}

/** Las sumas de [SalesReportDao.observeTotals], en unidad menor, y el número de ventas. */
internal data class SalesTotalsRow(
    @ColumnInfo(name = "sales_net_total") val salesNetTotal: Long,
    @ColumnInfo(name = "sales_total_cost") val salesTotalCost: Long,
    @ColumnInfo(name = "sales_count") val salesCount: Long,
    @ColumnInfo(name = "returns_net_refund") val returnsNetRefund: Long,
    @ColumnInfo(name = "returns_cost_returned") val returnsCostReturned: Long,
)

/**
 * La consulta de [SalesReportDao.observeTotals]. Vive aparte para que la prueba del plan de consulta
 * mire la misma sentencia que corre la app.
 */
internal const val SALES_TOTALS_SQL =
    "SELECT " +
        "(SELECT COALESCE(SUM(net_total), 0) FROM sales WHERE status = 'ACTIVE' " +
        "AND business_date BETWEEN :startDate AND :endDate) AS sales_net_total, " +
        "(SELECT COALESCE(SUM(total_cost), 0) FROM sales WHERE status = 'ACTIVE' " +
        "AND business_date BETWEEN :startDate AND :endDate) AS sales_total_cost, " +
        "(SELECT COUNT(*) FROM sales WHERE status = 'ACTIVE' " +
        "AND business_date BETWEEN :startDate AND :endDate) AS sales_count, " +
        "(SELECT COALESCE(SUM(net_refund), 0) FROM sale_returns WHERE status = 'ACTIVE' " +
        "AND business_date BETWEEN :startDate AND :endDate) AS returns_net_refund, " +
        "(SELECT COALESCE(SUM(cost_returned), 0) FROM sale_returns WHERE status = 'ACTIVE' " +
        "AND business_date BETWEEN :startDate AND :endDate) AS returns_cost_returned"
