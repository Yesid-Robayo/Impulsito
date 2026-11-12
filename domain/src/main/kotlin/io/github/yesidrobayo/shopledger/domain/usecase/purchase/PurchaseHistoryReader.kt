package io.github.yesidrobayo.shopledger.domain.usecase.purchase

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseHistoryCursor
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseHistoryFilter
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseHistoryRow
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.PurchaseHistorySource
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow

/**
 * Lee el historial de compras por tramos, con lo comprado en cada día y la marca de cambios que
 * pide volver a leer. No escribe nada. El instante actual sale del reloj inyectado, para fijar el
 * período en curso.
 */
class PurchaseHistoryReader(private val source: PurchaseHistorySource, private val clock: Clock) {
    /** Hasta [limit] compras de [filter] después de [after], de la más nueva a la más antigua. */
    suspend fun page(
        filter: PurchaseHistoryFilter,
        after: PurchaseHistoryCursor?,
        limit: Int,
    ): List<PurchaseHistoryRow> {
        require(limit > 0) { "Un tramo trae al menos una compra" }
        return source.page(filter, after, limit)
    }

    /** Lo comprado en compras activas en cada una de [dates], con [filter]. */
    suspend fun dayTotals(
        filter: PurchaseHistoryFilter,
        dates: Set<BusinessDate>,
    ): Map<BusinessDate, Money> = source.dayTotals(filter, dates)

    /** Cambia con cada compra nueva o anulada. */
    fun changes(): Flow<String> = source.observeChanges()

    fun now(): Instant = clock.now()
}
