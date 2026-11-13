package io.github.yesidrobayo.shopledger.data.local.report

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

/**
 * Lo que la ganancia si se repone lee de un período, por fecha de negocio. Solo lee, y nunca toca
 * las sumas de la ganancia en ventas, que lee `PeriodReportDao`.
 *
 * `startDate` y `endDate` son fechas `AAAA-MM-DD` y entran las dos. Ventas y devoluciones se buscan
 * por su índice `(status, business_date)`, y sus líneas por la llave de su documento. Solo suma y
 * lista: el reparto y las restas los hace `:domain`.
 */
@Dao
internal interface ReplacementReportDao {
    /**
     * La reposición congelada en las ventas activas del rango, y cuántas de sus líneas se vendieron
     * sin reposición ni costo. Vuelve a emitir con cada cambio en ventas o sus líneas.
     */
    @Query(SALES_REPLACEMENT_SQL)
    fun observeSales(startDate: String, endDate: String): Flow<SalesReplacementRow>

    /**
     * Las líneas devueltas al stock en devoluciones activas del rango, cada una con su línea de
     * venta y lo que ya se había devuelto de ella en devoluciones activas anteriores, en orden de
     * `id`. Vuelve a emitir con cada cambio en devoluciones, sus líneas o las líneas de venta.
     */
    @Query(RESTOCKED_RETURNS_SQL)
    fun observeRestockedReturns(startDate: String, endDate: String): Flow<List<RestockedReturnRow>>
}

/** Las sumas de [ReplacementReportDao.observeSales], en unidad menor. */
internal data class SalesReplacementRow(
    @ColumnInfo(name = "sales_replacement_cost") val salesReplacementCost: Long,
    @ColumnInfo(name = "lines_without_replacement") val linesWithoutReplacement: Long,
)

/** Una línea de [ReplacementReportDao.observeRestockedReturns], con cantidades en milésimas. */
internal data class RestockedReturnRow(
    @ColumnInfo(name = "id") val id: Long,
    @ColumnInfo(name = "line_replacement_cost") val lineReplacementCost: Long,
    @ColumnInfo(name = "line_quantity_milli") val lineQuantityMilli: Long,
    @ColumnInfo(name = "returned_before_milli") val returnedBeforeMilli: Long,
    @ColumnInfo(name = "returned_milli") val returnedMilli: Long,
)

// Las consultas viven aparte para que la prueba del plan de consulta mire la misma sentencia que
// corre la app.

/**
 * La consulta de [ReplacementReportDao.observeSales]. La reposición sale de la cabecera, que ya la
 * suma; las líneas sin reposición se cuentan aparte, porque la cabecera no lo dice.
 */
internal const val SALES_REPLACEMENT_SQL =
    "SELECT " +
        "(SELECT COALESCE(SUM(total_replacement_cost), 0) FROM sales WHERE status = 'ACTIVE' " +
        "AND business_date BETWEEN :startDate AND :endDate) AS sales_replacement_cost, " +
        "(SELECT COUNT(*) FROM sales s JOIN sale_lines sl ON sl.sale_id = s.id " +
        "WHERE s.status = 'ACTIVE' AND s.business_date BETWEEN :startDate AND :endDate " +
        "AND sl.unit_replacement_cost = 0) AS lines_without_replacement"

/**
 * La consulta de [ReplacementReportDao.observeRestockedReturns]. Lo devuelto antes de una línea es
 * la suma de las líneas de devoluciones activas de la misma línea de venta con `id` menor: el mismo
 * orden en que se repartió lo devuelto, para que las partes sumen exacto.
 */
internal const val RESTOCKED_RETURNS_SQL =
    "SELECT rl.id AS id, sl.line_replacement_cost AS line_replacement_cost, " +
        "sl.quantity_milli AS line_quantity_milli, " +
        "COALESCE((SELECT SUM(p.quantity_milli) FROM sale_return_lines p " +
        "JOIN sale_returns pr ON pr.id = p.sale_return_id " +
        "WHERE p.sale_line_id = rl.sale_line_id AND p.id < rl.id AND pr.status = 'ACTIVE'), 0) " +
        "AS returned_before_milli, " +
        "rl.quantity_milli AS returned_milli " +
        "FROM sale_returns r JOIN sale_return_lines rl ON rl.sale_return_id = r.id " +
        "JOIN sale_lines sl ON sl.id = rl.sale_line_id " +
        "WHERE r.status = 'ACTIVE' AND r.business_date BETWEEN :startDate AND :endDate " +
        "AND rl.restock = 1 ORDER BY rl.id"
