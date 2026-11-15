package io.github.yesidrobayo.shopledger.domain.port

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.report.CashFlowSums
import kotlinx.coroutines.flow.Flow

/**
 * Las sumas del flujo de caja proyectado, en una sola consulta agregada. Solo lee, y vuelve a
 * emitir con cada cambio en las tablas que suma.
 */
fun interface CashFlowSource {
    /**
     * Las sumas desde [today], con el horizonte corto hasta [shortUntil] y el largo hasta
     * [longUntil], los dos incluidos.
     */
    fun observe(
        today: BusinessDate,
        shortUntil: BusinessDate,
        longUntil: BusinessDate,
    ): Flow<CashFlowSums>
}
