package io.github.yesidrobayo.shopledger.domain.usecase.sale

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleHistoryCursor
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleHistoryFilter
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleHistoryRow
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.SaleHistorySource
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow

/**
 * Lee el historial de ventas por tramos, con lo vendido en cada día y la marca de cambios que pide
 * volver a leer. No escribe nada. El instante actual sale del reloj inyectado, para fijar el
 * período en curso.
 */
class SaleHistoryReader(private val source: SaleHistorySource, private val clock: Clock) {
    /** Hasta [limit] ventas de [filter] después de [after], de la más nueva a la más antigua. */
    suspend fun page(
        filter: SaleHistoryFilter,
        after: SaleHistoryCursor?,
        limit: Int,
    ): List<SaleHistoryRow> {
        require(limit > 0) { "Un tramo trae al menos una venta" }
        return source.page(filter, after, limit)
    }

    /** Lo vendido en ventas activas en cada una de [dates], con [filter]. */
    suspend fun dayTotals(
        filter: SaleHistoryFilter,
        dates: Set<BusinessDate>,
    ): Map<BusinessDate, Money> = source.dayTotals(filter, dates)

    /** Cambia con cada venta nueva o anulada. */
    fun changes(): Flow<String> = source.observeChanges()

    /** Los métodos de [methodIds] que cobraron al menos una venta. */
    suspend fun methodsWithSales(methodIds: Set<Long>): Set<Long> =
        source.methodsWithSales(methodIds)

    fun now(): Instant = clock.now()
}
