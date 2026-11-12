package io.github.yesidrobayo.shopledger.data.repository

import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.data.local.purchase.PurchaseHistoryRowEntity
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.DocumentStatus
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseHistoryCursor
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseHistoryFilter
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseHistoryRow
import io.github.yesidrobayo.shopledger.domain.model.purchase.SupplierFilter
import io.github.yesidrobayo.shopledger.domain.port.PurchaseHistorySource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * El historial de compras por tramos, sobre `purchases`, `purchase_lines` y `suppliers`. Cada tramo
 * es una consulta acotada por fecha de negocio e id, que se detiene al juntar el tramo. Solo lee.
 */
internal class RoomPurchaseHistorySource(database: AppDatabase) : PurchaseHistorySource {
    private val purchases = database.purchaseDao()

    override suspend fun page(
        filter: PurchaseHistoryFilter,
        after: PurchaseHistoryCursor?,
        limit: Int,
    ): List<PurchaseHistoryRow> {
        val (mode, supplierId) = supplierOf(filter.supplier)
        return purchases.historyPage(
            fromDate = filter.from?.toStored(),
            toDate = filter.to?.toStored(),
            supplierMode = mode,
            supplierId = supplierId,
            onlyVoided = filter.onlyVoided,
            afterDate = after?.businessDate?.toStored(),
            afterId = after?.id,
            limit = limit,
        ).map(::toRow)
    }

    override suspend fun dayTotals(
        filter: PurchaseHistoryFilter,
        dates: Set<BusinessDate>,
    ): Map<BusinessDate, Money> {
        if (dates.isEmpty()) return emptyMap()
        val (mode, supplierId) = supplierOf(filter.supplier)
        return purchases.dayTotals(dates.map { it.toStored() }, mode, supplierId)
            .associate { storedDate(it.businessDate) to Money(it.total) }
    }

    override fun observeChanges(): Flow<String> =
        purchases.observeHistoryMark().distinctUntilChanged()

    /** El modo del filtro de proveedor en la consulta, con su id cuando hace falta. */
    private fun supplierOf(filter: SupplierFilter): Pair<Int, Long?> = when (filter) {
        SupplierFilter.Any -> ANY_SUPPLIER to null
        SupplierFilter.None -> NO_SUPPLIER to null
        is SupplierFilter.Of -> ONE_SUPPLIER to filter.supplierId
    }

    private fun toRow(row: PurchaseHistoryRowEntity) = PurchaseHistoryRow(
        id = row.id,
        occurredAt = row.occurredAt.toInstant(),
        businessDate = storedDate(row.businessDate),
        total = Money(row.total),
        voided = row.status == DocumentStatus.VOIDED.name,
        supplierName = row.supplierName,
        lineCount = row.lineCount,
        invoiceReference = row.invoiceReference,
    )

    private companion object {
        const val ANY_SUPPLIER = 0
        const val NO_SUPPLIER = 1
        const val ONE_SUPPLIER = 2
    }
}
