package io.github.yesidrobayo.shopledger.domain.port

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
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
}
