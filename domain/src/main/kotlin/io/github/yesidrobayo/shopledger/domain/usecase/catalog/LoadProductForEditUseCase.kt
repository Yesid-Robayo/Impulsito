package io.github.yesidrobayo.shopledger.domain.usecase.catalog

import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductForEdit
import io.github.yesidrobayo.shopledger.domain.port.ProductRepository
import io.github.yesidrobayo.shopledger.domain.port.StockLedgerRepository
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.rule.catalog.ProductHistoryLock

/**
 * Lee un producto para su formulario de edición, de una sola foto de la base: sus datos, lo que su
 * historia deja fijo y si su costo ya lo lleva el libro. No escribe nada. `null` si el producto ya
 * no existe.
 */
class LoadProductForEditUseCase(
    private val transactions: TransactionRunner,
    private val products: ProductRepository,
    private val ledger: StockLedgerRepository,
) {
    suspend operator fun invoke(productId: Long): ProductForEdit? = transactions.read {
        products.findById(productId)?.let { product ->
            ProductForEdit(
                product = product,
                lockedFields = ProductHistoryLock.lockedFields(products.hasHistory(productId)),
                costLocked = ledger.findByProduct(productId).isNotEmpty(),
            )
        }
    }
}
