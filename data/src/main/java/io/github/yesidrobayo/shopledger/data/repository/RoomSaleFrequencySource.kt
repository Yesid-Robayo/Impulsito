package io.github.yesidrobayo.shopledger.data.repository

import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.port.SaleFrequencySource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Cuántas líneas tiene cada producto en las ventas activas de un período, con una sola consulta de
 * solo lectura sobre `sales` y `sale_lines`. Una venta anulada deja de contar.
 */
internal class RoomSaleFrequencySource(database: AppDatabase) : SaleFrequencySource {
    private val lines = database.saleLineDao()

    override fun observeLineCounts(since: BusinessDate): Flow<Map<Long, Int>> = lines
        .observeLineCountsSince(since.toStored())
        .map { rows -> rows.associate { it.productId to it.lineCount } }
        .distinctUntilChanged()
}
