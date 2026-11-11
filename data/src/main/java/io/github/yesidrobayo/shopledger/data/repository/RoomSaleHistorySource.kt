package io.github.yesidrobayo.shopledger.data.repository

import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.data.local.sale.SaleHistoryRowEntity
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.DocumentStatus
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleHistoryCursor
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleHistoryFilter
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleHistoryRow
import io.github.yesidrobayo.shopledger.domain.port.SaleHistorySource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * El historial de ventas por tramos, sobre `sales`, `sale_lines` y `sale_payments`. Cada tramo es una
 * consulta acotada por el índice `(occurred_at, id)`: con años de ventas, leer el siguiente cuesta lo
 * mismo que leer el primero. Solo lee.
 */
internal class RoomSaleHistorySource(database: AppDatabase) : SaleHistorySource {
    private val sales = database.saleDao()

    override suspend fun page(
        filter: SaleHistoryFilter,
        after: SaleHistoryCursor?,
        limit: Int,
    ): List<SaleHistoryRow> = sales.historyPage(
        fromDate = filter.from?.toStored(),
        toDate = filter.to?.toStored(),
        methodId = filter.paymentMethodId,
        onlyVoided = filter.onlyVoided,
        afterAt = after?.occurredAt?.toStoredMillis(),
        afterId = after?.id,
        limit = limit,
    ).map(::toRow)

    override suspend fun dayTotals(
        filter: SaleHistoryFilter,
        dates: Set<BusinessDate>,
    ): Map<BusinessDate, Money> {
        if (dates.isEmpty()) return emptyMap()
        return sales.dayTotals(dates.map { it.toStored() }, filter.paymentMethodId)
            .associate { storedDate(it.businessDate) to Money(it.total) }
    }

    override fun observeChanges(): Flow<String> = sales.observeHistoryMark().distinctUntilChanged()

    private fun toRow(row: SaleHistoryRowEntity) = SaleHistoryRow(
        id = row.id,
        occurredAt = row.occurredAt.toInstant(),
        businessDate = storedDate(row.businessDate),
        total = Money(row.total),
        voided = row.status == DocumentStatus.VOIDED.name,
        firstProductName = row.firstProduct,
        lineCount = row.lineCount,
        methodName = row.methodName,
        paymentCount = row.paymentCount,
    )
}
