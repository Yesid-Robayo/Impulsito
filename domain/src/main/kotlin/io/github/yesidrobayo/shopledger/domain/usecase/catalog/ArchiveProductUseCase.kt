package io.github.yesidrobayo.shopledger.domain.usecase.catalog

import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.LowStockAlertRepository
import io.github.yesidrobayo.shopledger.domain.port.ProductRepository
import io.github.yesidrobayo.shopledger.domain.port.ProductVariantRepository
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.TxResult

/** Cómo termina archivar un producto. Salvo [Archived], no se escribió nada. */
sealed interface ArchiveProductResult {
    data object Archived : ArchiveProductResult

    data object NotFound : ArchiveProductResult

    /** Ya estaba archivado: no hay nada que hacer. */
    data object AlreadyArchived : ArchiveProductResult

    data class StorageFailed(val failure: StorageFailure) : ArchiveProductResult
}

/**
 * Archiva un producto: deja de ofrecerse en ventas, compras y ajustes nuevos, pero su historia
 * sigue intacta y se puede reactivar. Es lo que se hace con un producto que ya tiene historia, que
 * no se borra.
 *
 * Se puede archivar con stock: las unidades siguen existiendo aunque no se quieran vender más. Un
 * producto archivado no se vigila, así que su alerta activa queda resuelta en la misma transacción.
 *
 * Archivar un producto con variantes archiva también las que sigan activas, en el mismo instante:
 * sin su producto principal no hay dónde elegirlas al vender. Reactivarlo las devuelve juntas.
 * Una variante sola se archiva sin tocar a las demás ni a su principal.
 */
class ArchiveProductUseCase(
    private val transactions: TransactionRunner,
    private val products: ProductRepository,
    private val alerts: LowStockAlertRepository,
    private val clock: Clock,
    private val variants: ProductVariantRepository,
) {
    suspend operator fun invoke(productId: Long): ArchiveProductResult =
        storageSafe(ArchiveProductResult::StorageFailed) {
            transactions.write { archive(productId) }
        }

    private suspend fun archive(productId: Long): TxResult<ArchiveProductResult> {
        val product = products.findById(productId)
            ?: return TxResult.Rollback(ArchiveProductResult.NotFound)
        if (product.isArchived) return TxResult.Rollback(ArchiveProductResult.AlreadyArchived)
        val now = clock.now()
        val archived = listOf(productId) +
            variants.findVariants(productId).filterNot { it.isArchived }.map { it.id }
        for (id in archived) products.setArchivedAt(id, now, now)
        for (alert in alerts.findActiveByProducts(archived)) alerts.resolve(alert.id, now)
        return TxResult.Commit(ArchiveProductResult.Archived)
    }
}
