package io.github.yesidrobayo.shopledger.domain.usecase.catalog

import io.github.yesidrobayo.shopledger.domain.port.CategoryRepository
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.TxResult

/** Cómo termina eliminar una categoría. */
sealed interface DeleteCategoryResult {
    /** Se borró, y sus [affectedProducts] productos quedaron sin categoría. */
    data class Deleted(val affectedProducts: Int) : DeleteCategoryResult

    data object NotFound : DeleteCategoryResult

    data class StorageFailed(val failure: StorageFailure) : DeleteCategoryResult
}

/**
 * Elimina una categoría aunque esté en uso: sus productos quedan sin categoría, en la misma
 * transacción. No hay historia que proteger, porque la categoría no entra en ninguna cuenta de
 * dinero. La confirmación dice antes cuántos productos quedan sin categoría, con [affectedProducts].
 */
class DeleteCategoryUseCase(
    private val transactions: TransactionRunner,
    private val categories: CategoryRepository,
) {
    /** Cuántos productos, archivados o no, quedarían sin categoría: lo dice la confirmación. */
    suspend fun affectedProducts(categoryId: Long): Int =
        transactions.read { categories.countProducts(categoryId) }

    suspend operator fun invoke(categoryId: Long): DeleteCategoryResult =
        storageSafe(DeleteCategoryResult::StorageFailed) {
            transactions.write { delete(categoryId) }
        }

    private suspend fun delete(categoryId: Long): TxResult<DeleteCategoryResult> {
        if (categories.findById(categoryId) == null) {
            return TxResult.Rollback(DeleteCategoryResult.NotFound)
        }
        val affected = categories.countProducts(categoryId)
        categories.delete(categoryId)
        return TxResult.Commit(DeleteCategoryResult.Deleted(affected))
    }
}
