package io.github.yesidrobayo.shopledger.domain.port

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleHistoryCursor
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleHistoryFilter
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleHistoryRow
import kotlinx.coroutines.flow.Flow

/**
 * El historial de ventas, leído por tramos: sin límite de años, la lista nunca carga todo de una
 * vez. Solo lee.
 */
interface SaleHistorySource {
    /**
     * Hasta [limit] ventas de [filter], de la más nueva a la más antigua, después de [after], o
     * desde el principio sin él.
     */
    suspend fun page(
        filter: SaleHistoryFilter,
        after: SaleHistoryCursor?,
        limit: Int,
    ): List<SaleHistoryRow>

    /**
     * Lo vendido en cada fecha de [dates] con [filter], solo de ventas activas: una anulada no
     * suma en su día. Una fecha sin ventas activas no aparece.
     */
    suspend fun dayTotals(
        filter: SaleHistoryFilter,
        dates: Set<BusinessDate>,
    ): Map<BusinessDate, Money>

    /**
     * Una marca que cambia con cada venta nueva o anulada: quien muestra el historial lo vuelve a
     * leer entonces.
     */
    fun observeChanges(): Flow<String>
}
