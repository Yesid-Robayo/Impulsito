package io.github.yesidrobayo.shopledger.domain.usecase.catalog

import io.github.yesidrobayo.shopledger.domain.model.catalog.CatalogLink
import io.github.yesidrobayo.shopledger.domain.port.ProductRepository
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.TxResult

/** Cómo termina eliminar un producto. Salvo [Deleted], no se borró nada. */
sealed interface DeleteProductResult {
    /** Se borró, con su historial de precios y sus alertas. */
    data object Deleted : DeleteProductResult

    data object NotFound : DeleteProductResult

    /**
     * Tiene historia, aunque esté anulada: una fila del libro o una línea de documento. Ninguna se
     * borra, así que el producto tampoco: solo se archiva.
     */
    data object HasHistory : DeleteProductResult

    /**
     * Otros productos del catálogo lo usan, por cada uno de [links]. No es historia: se puede
     * eliminar después de quitarlo de esos productos.
     */
    data class LinkedInCatalog(val links: Set<CatalogLink>) : DeleteProductResult

    data class StorageFailed(val failure: StorageFailure) : DeleteProductResult
}

/**
 * Elimina un producto creado por error, sin historia.
 *
 * Antes de intentarlo dice por qué no se puede, si no se puede: con historia solo se archiva, y si
 * otro producto lo usa como componente, como extra o como principal de sus variantes, hay que
 * quitarlo de allí primero. La base lo rechazaría igual, pero sin decir el motivo.
 */
class DeleteProductUseCase(
    private val transactions: TransactionRunner,
    private val products: ProductRepository,
) {
    suspend operator fun invoke(productId: Long): DeleteProductResult =
        storageSafe(DeleteProductResult::StorageFailed) {
            transactions.write { delete(productId) }
        }

    private suspend fun delete(productId: Long): TxResult<DeleteProductResult> {
        if (products.findById(productId) == null) {
            return TxResult.Rollback(DeleteProductResult.NotFound)
        }
        if (products.hasHistory(productId)) return TxResult.Rollback(DeleteProductResult.HasHistory)
        val links = products.catalogLinks(productId)
        if (links.isNotEmpty()) return TxResult.Rollback(DeleteProductResult.LinkedInCatalog(links))
        products.delete(productId)
        return TxResult.Commit(DeleteProductResult.Deleted)
    }
}
