package io.github.yesidrobayo.shopledger.data.local.report

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

/**
 * Lo vendido y lo cobrado por fecha de negocio, y si el negocio vende a crédito. Solo lee.
 *
 * `startDate` y `endDate` son fechas `AAAA-MM-DD` y entran las dos. Las ventas se buscan por su
 * índice `(status, business_date)` y sus pagos por `sale_id`. Los abonos salen del libro de
 * clientes, sin los que tienen su anulación.
 */
@Dao
internal interface CollectionReportDao {
    /**
     * Lo vendido, lo cobrado al vender y lo abonado de cada día del rango con alguna venta o
     * abono, ordenado por fecha. Vuelve a emitir con cada cambio en las tablas que lee.
     */
    @Query(DAILY_COLLECTION_SQL)
    fun observeDaily(startDate: String, endDate: String): Flow<List<DailyCollectionRow>>

    /** Si el libro de clientes tiene alguna fila, de nuevo con cada cambio. */
    @Query("SELECT EXISTS (SELECT 1 FROM customer_ledger_entries)")
    fun observeOnCredit(): Flow<Boolean>
}

/** Un día de [CollectionReportDao.observeDaily], en unidad menor. */
internal data class DailyCollectionRow(
    @ColumnInfo(name = "business_date") val businessDate: String,
    @ColumnInfo(name = "sold") val sold: Long,
    @ColumnInfo(name = "collected_at_sale") val collectedAtSale: Long,
    @ColumnInfo(name = "collected_on_account") val collectedOnAccount: Long,
)

/**
 * La consulta de [CollectionReportDao.observeDaily]. Vendido suma `amount_due`, lo que cada venta
 * pidió cobrar, y no `total`: así una venta pagada entera tiene vendido igual a cobrado, con su
 * propina y su redondeo. Lo cobrado al vender son los pagos que no quedaron a crédito, en la fecha
 * de su venta. Un abono resta en el libro, así que se suma con el signo cambiado, en su fecha.
 */
internal const val DAILY_COLLECTION_SQL =
    "SELECT business_date, SUM(sold) AS sold, SUM(collected_at_sale) AS collected_at_sale, " +
        "SUM(collected_on_account) AS collected_on_account FROM (" +
        "SELECT business_date, amount_due AS sold, 0 AS collected_at_sale, " +
        "0 AS collected_on_account FROM sales " +
        "WHERE status = 'ACTIVE' AND business_date BETWEEN :startDate AND :endDate " +
        "UNION ALL SELECT s.business_date, 0, p.amount, 0 " +
        "FROM sales s JOIN sale_payments p ON p.sale_id = s.id " +
        "WHERE s.status = 'ACTIVE' AND s.business_date BETWEEN :startDate AND :endDate " +
        "AND p.method_kind_snapshot <> 'ON_ACCOUNT' " +
        "UNION ALL SELECT e.business_date, 0, 0, -e.amount FROM customer_ledger_entries e " +
        "WHERE e.type = 'PAYMENT' AND e.business_date BETWEEN :startDate AND :endDate " +
        "AND NOT EXISTS (SELECT 1 FROM customer_ledger_entries v " +
        "WHERE v.reverses_entry_id = e.id)" +
        ") GROUP BY business_date ORDER BY business_date"
