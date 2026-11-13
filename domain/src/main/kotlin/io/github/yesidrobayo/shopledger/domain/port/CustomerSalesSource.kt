package io.github.yesidrobayo.shopledger.domain.port

import io.github.yesidrobayo.shopledger.domain.model.sale.SaleHistoryCursor
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleHistoryRow

/**
 * Las ventas de un cliente, leídas por tramos, para el detalle del cliente. Trae las anuladas
 * también, marcadas: el detalle muestra toda su historia. Solo lee.
 */
fun interface CustomerSalesSource {
    /**
     * Hasta [limit] ventas de [customerId], de la más nueva a la más antigua, después de [after], o
     * desde el principio sin él. Mismo orden y mismo cursor que el historial de ventas.
     */
    suspend fun page(customerId: Long, after: SaleHistoryCursor?, limit: Int): List<SaleHistoryRow>
}
