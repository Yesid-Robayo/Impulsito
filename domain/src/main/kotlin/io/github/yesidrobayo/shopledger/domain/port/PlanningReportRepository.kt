package io.github.yesidrobayo.shopledger.domain.port

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import kotlinx.coroutines.flow.Flow

/**
 * Las sumas que piden el límite de ingresos y el punto de equilibrio, aparte de las de las
 * estadísticas. Solo lee, con consultas que suman en la base.
 */
interface PlanningReportRepository {
    /**
     * La fecha de la primera venta activa, o `null` si todavía no hay ninguna. De nuevo con cada
     * venta o anulación confirmada.
     */
    fun observeFirstSaleDate(): Flow<BusinessDate?>

    /**
     * Los gastos activos de [period] cuya categoría está marcada como fija, por su fecha de
     * negocio. Cero si no hay ninguno. De nuevo con cada gasto, anulación o categoría que cambia.
     */
    fun observeFixedExpenses(period: ReportPeriod): Flow<Money>
}
