package io.github.yesidrobayo.shopledger.domain.usecase.pricing

import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.ProductRepository
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.storageSafe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * La cola `Costo nuevo sin revisar`: los productos vigentes a los que una compra les subió el costo
 * y nadie les revisó el precio todavía, el más antiguo primero. Vuelve a emitir con cada cambio.
 */
class ObservePriceReviewQueueUseCase(private val products: ProductRepository) {
    operator fun invoke(): Flow<List<Product>> = products.observe(ArchiveFilter.ACTIVE)
        .map { list ->
            list.filter { it.priceReviewSince != null }
                .sortedWith(compareBy<Product> { it.priceReviewSince }.thenBy { it.id })
        }
        .distinctUntilChanged()
}

/** Cómo termina descartar un producto de la cola. Salvo [Dismissed], no se escribió nada. */
sealed interface DismissPriceReviewResult {
    data object Dismissed : DismissPriceReviewResult

    /** No estaba en la cola, o ya no existe. */
    data object NotPending : DismissPriceReviewResult

    data class StorageFailed(val failure: StorageFailure) : DismissPriceReviewResult
}

/**
 * Saca un producto de la cola `Costo nuevo sin revisar` sin cambiar su precio: la persona lo revisó
 * y lo deja como está. La próxima compra que suba el costo lo vuelve a poner.
 */
class DismissPriceReviewUseCase(
    private val transactions: TransactionRunner,
    private val products: ProductRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(productId: Long): DismissPriceReviewResult =
        storageSafe(DismissPriceReviewResult::StorageFailed) {
            transactions.write { dismiss(productId) }
        }

    private suspend fun dismiss(productId: Long): TxResult<DismissPriceReviewResult> {
        val product = products.findById(productId)
        if (product?.priceReviewSince == null) {
            return TxResult.Rollback(DismissPriceReviewResult.NotPending)
        }
        products.setPriceReviewSince(product.id, since = null, updatedAt = clock.now())
        return TxResult.Commit(DismissPriceReviewResult.Dismissed)
    }
}
