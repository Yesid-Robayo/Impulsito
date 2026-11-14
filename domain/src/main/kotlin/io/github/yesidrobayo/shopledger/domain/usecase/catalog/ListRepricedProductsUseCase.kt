package io.github.yesidrobayo.shopledger.domain.usecase.catalog

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.catalog.SystemRow
import io.github.yesidrobayo.shopledger.domain.port.PriceHistoryRepository
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.SystemRowRepository
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner

/** Cómo termina buscar los productos que cambiaron de precio. */
sealed interface RepricedProductsResult {
    data class Found(val productIds: Set<Long>) : RepricedProductsResult

    data class StorageFailed(val failure: StorageFailure) : RepricedProductsResult
}

/**
 * Los productos cuyo precio de venta cambió desde una fecha, para imprimir solo sus etiquetas. Lee
 * el historial de la lista inicial, la que da el precio de venta: un cambio en otra lista no
 * cambia lo que dice la etiqueta. No escribe nada.
 */
class ListRepricedProductsUseCase(
    private val transactions: TransactionRunner,
    private val systemRows: SystemRowRepository,
    private val history: PriceHistoryRepository,
) {
    suspend operator fun invoke(from: BusinessDate): RepricedProductsResult =
        storageSafe(RepricedProductsResult::StorageFailed) {
            transactions.read {
                val list = systemRows.requireId(SystemRow.DEFAULT_PRICE_LIST)
                RepricedProductsResult.Found(history.findProductsRepricedSince(list, from))
            }
        }
}
