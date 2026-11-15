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

    /**
     * La suma de los gastos activos entre [startDate] y [endDate], las dos incluidas, cuya
     * categoría está marcada como fija. Los gastos se buscan por `(status, business_date)`, y cada
     * uno lee su categoría por su llave.
     */
    @Query(FIXED_EXPENSES_SQL)
    fun observeFixedExpenses(startDate: String, endDate: String): Flow<Long>
}

/** La consulta de [PlanningReportDao.observeFixedExpenses]. */
internal const val FIXED_EXPENSES_SQL =
    "SELECT COALESCE(SUM(e.amount), 0) AS fixed_expenses FROM expenses e " +
        "JOIN expense_categories c ON c.id = e.expense_category_id " +
        "WHERE e.status = 'ACTIVE' AND c.is_fixed = 1 " +
        "AND e.business_date BETWEEN :startDate AND :endDate"

/** La consulta de [PlanningReportDao.observeFirstSaleDate]. */
internal const val FIRST_SALE_DATE_SQL =
    "SELECT COALESCE((SELECT MIN(business_date) FROM sales WHERE status = 'ACTIVE'), '') " +
        "AS first_sale_date"
