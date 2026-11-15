package io.github.yesidrobayo.shopledger.data.local.report

import androidx.room3.Dao
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

/**
 * Las sumas del límite de ingresos y del punto de equilibrio, aparte de las de las estadísticas.
 * Solo lee, y cada consulta va por un índice de fecha: ninguna carga filas a memoria.
 */
@Dao
internal interface PlanningReportDao {
    /**
     * La fecha de la primera venta activa, `AAAA-MM-DD`, o vacía si no hay ninguna. Sale del
     * principio del índice `(status, business_date)`, sin recorrer la tabla.
     */
    @Query(FIRST_SALE_DATE_SQL)
    fun observeFirstSaleDate(): Flow<String>
}

/** La consulta de [PlanningReportDao.observeFirstSaleDate]. */
internal const val FIRST_SALE_DATE_SQL =
    "SELECT COALESCE((SELECT MIN(business_date) FROM sales WHERE status = 'ACTIVE'), '') " +
        "AS first_sale_date"
