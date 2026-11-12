package io.github.yesidrobayo.shopledger.domain.port

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseHistoryCursor
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseHistoryFilter
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseHistoryRow
import kotlinx.coroutines.flow.Flow

/**
 * El historial de compras, leído por tramos: sin límite de años, la lista nunca carga todo de una
 * vez. Solo lee.
 */
interface PurchaseHistorySource {
    /**
     * Hasta [limit] compras de [filter], de la más nueva a la más antigua, después de [after], o
     * desde el principio sin él.
     */
    suspend fun page(
        filter: PurchaseHistoryFilter,
        after: PurchaseHistoryCursor?,
        limit: Int,
    ): List<PurchaseHistoryRow>

    /**
     * Lo comprado en cada fecha de [dates] con [filter], solo de compras activas: una anulada no
     * suma en su día. Una fecha sin compras activas no aparece.
     */
    suspend fun dayTotals(
        filter: PurchaseHistoryFilter,
        dates: Set<BusinessDate>,
    ): Map<BusinessDate, Money>

    /**
     * Una marca que cambia con cada compra nueva o anulada: quien muestra el historial lo vuelve a
     * leer entonces.
     */
    fun observeChanges(): Flow<String>
}
