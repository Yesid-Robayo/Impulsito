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

    /**
     * Con filtro de método, elige el camino más corto: un método con pocos pagos se lee desde sus
     * pagos, y uno muy usado recorriendo las ventas, donde aparece a cada rato. Las dos consultas
     * dan las mismas filas en el mismo orden.
     */
    override suspend fun page(
        filter: SaleHistoryFilter,
        after: SaleHistoryCursor?,
        limit: Int,
    ): List<SaleHistoryRow> {
        val fromDate = filter.from?.toStored()
        val toDate = filter.to?.toStored()
        val afterAt = after?.occurredAt?.toStoredMillis()
        val methodId = filter.paymentMethodId
        val rows =
            if (methodId != null &&
                sales.paymentCountUpTo(methodId, FEW_PAYMENTS) < FEW_PAYMENTS
            ) {
                sales.historyPageOfMethod(
                    methodId,
                    fromDate,
                    toDate,
                    filter.onlyVoided,
                    afterAt,
                    after?.id,
                    limit,
                )
            } else {
                sales.historyPage(
                    fromDate = fromDate,
                    toDate = toDate,
                    methodId = methodId,
                    onlyVoided = filter.onlyVoided,
                    afterAt = afterAt,
                    afterId = after?.id,
                    limit = limit,
                )
            }
        return rows.map(::toRow)
    }

    override suspend fun dayTotals(
        filter: SaleHistoryFilter,
        dates: Set<BusinessDate>,
    ): Map<BusinessDate, Money> {
        if (dates.isEmpty()) return emptyMap()
        return sales.dayTotals(dates.map { it.toStored() }, filter.paymentMethodId)
            .associate { storedDate(it.businessDate) to Money(it.total) }
    }

    override fun observeChanges(): Flow<String> = sales.observeHistoryMark().distinctUntilChanged()

    override suspend fun methodsWithSales(methodIds: Set<Long>): Set<Long> =
        if (methodIds.isEmpty()) {
            emptySet()
        } else {
            sales.methodsWithPayments(
                methodIds.toList(),
            ).toSet()
        }

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

    internal companion object {
        /**
         * Hasta cuántos pagos un método se lee desde sus pagos. Leer así cuesta lo que tenga el
         * método; recorrer las ventas cuesta lo que haya que saltar para juntar un tramo. Con
         * 100000 ventas y tramos de 50, los dos caminos se igualan cerca de los 2000 pagos.
         */
        const val FEW_PAYMENTS = 2_000
    }
}
