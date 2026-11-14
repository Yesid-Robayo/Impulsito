package io.github.yesidrobayo.shopledger.data.repository

import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.port.SaleOutflowSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Lo que salió de cada producto por ventas, con una sola consulta de solo lectura sobre el libro
 * de stock. Una venta anulada deja de contar, porque su fila ya tiene compensación.
 */
internal class RoomSaleOutflowSource(database: AppDatabase) : SaleOutflowSource {
    private val ledger = database.stockMovementDao()

    override fun observeUnitsOut(
        since: BusinessDate,
        locationId: Long?,
    ): Flow<Map<Long, QuantityMilli>> = ledger
        .observeSaleOutflow(since.toStored(), locationId)
        .map { rows -> rows.associate { it.productId to QuantityMilli(it.quantityOutMilli) } }
        .distinctUntilChanged()
}
