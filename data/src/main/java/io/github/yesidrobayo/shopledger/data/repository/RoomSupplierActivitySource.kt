package io.github.yesidrobayo.shopledger.data.repository

import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.domain.model.DocumentStatus
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.catalog.SupplierProduct
import io.github.yesidrobayo.shopledger.domain.model.catalog.SupplierPurchase
import io.github.yesidrobayo.shopledger.domain.model.catalog.SupplierTotals
import io.github.yesidrobayo.shopledger.domain.port.SupplierActivitySource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Lo que rodea a cada proveedor, con consultas de solo lectura sobre compras, devoluciones, su libro
 * y productos. No guarda contadores: todo sale de las filas, así que nunca se descuadra.
 */
internal class RoomSupplierActivitySource(database: AppDatabase) : SupplierActivitySource {
    private val suppliers = database.supplierDao()

    override fun observeTotals(supplierId: Long): Flow<SupplierTotals> = suppliers
        .observeTotals(supplierId)
        .map { row ->
            SupplierTotals(
                purchasesCount = row.purchasesCount.toStoredInt(),
                activeTotal = Money(row.activeTotal),
                hasHistory = row.hasHistory,
            )
        }
        .distinctUntilChanged()

    override fun observePurchases(supplierId: Long): Flow<List<SupplierPurchase>> = suppliers
        .observePurchases(supplierId)
        .map { rows ->
            rows.map { row ->
                SupplierPurchase(
                    id = row.id,
                    status = storedEnum<DocumentStatus>(row.status),
                    total = Money(row.total),
                    invoiceReference = row.invoiceReference,
                    occurredAt = row.occurredAt.toInstant(),
                    businessDate = storedDate(row.businessDate),
                )
            }
        }
        .distinctUntilChanged()

    override fun observeUsualProducts(supplierId: Long): Flow<List<SupplierProduct>> = suppliers
        .observeUsualProducts(supplierId)
        .map { rows -> rows.map { SupplierProduct(it.id, it.name) } }
        .distinctUntilChanged()

    override fun observeUsualProductCounts(): Flow<Map<Long, Int>> = suppliers
        .observeUsualProductCounts()
        .map { rows -> rows.associate { it.supplierId to it.products.toStoredInt() } }
        .distinctUntilChanged()
}
