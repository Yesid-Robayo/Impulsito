package io.github.yesidrobayo.shopledger.domain.usecase.catalog

import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.LowStockAlertRepository
import io.github.yesidrobayo.shopledger.domain.port.ProductRepository
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.rule.stock.LowStockEvaluator
import io.github.yesidrobayo.shopledger.domain.rule.stock.LowStockTransition
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource

/** Cómo termina reactivar un producto. Salvo [Reactivated], no se escribió nada. */
sealed interface ReactivateProductResult {
    data object Reactivated : ReactivateProductResult

    data object NotFound : ReactivateProductResult

    /** No estaba archivado: no hay nada que hacer. */
    data object NotArchived : ReactivateProductResult

    /**
     * Otro producto sin archivar ya usa su nombre. Hay que renombrar uno de los dos antes:
     * [existingName] es el del otro.
     */
    data class NameTaken(val existingName: String) : ReactivateProductResult

    /** Otro producto sin archivar ya usa su código de barras. */
    data class BarcodeTaken(val existingName: String) : ReactivateProductResult

    /** La zona del negocio no se reconoce, y la alerta que hace falta crear no tendría fecha. */
    data object UnknownTimeZone : ReactivateProductResult

    data class StorageFailed(val failure: StorageFailure) : ReactivateProductResult
}

/**
 * Reactiva un producto archivado: vuelve a ofrecerse en ventas, compras y ajustes.
 *
 * Un archivado no reserva su nombre, así que mientras estuvo archivado otro pudo tomarlo: entonces
 * se rechaza y se pide renombrar. Si vuelve bajo de stock, su alerta nace callada: la persona lo
 * está viendo, y avisar sería ruido.
 */
class ReactivateProductUseCase(
    private val transactions: TransactionRunner,
    private val products: ProductRepository,
    private val alerts: LowStockAlertRepository,
    private val calendar: BusinessCalendarSource,
    private val clock: Clock,
) {
    suspend operator fun invoke(productId: Long): ReactivateProductResult =
        storageSafe(ReactivateProductResult::StorageFailed) {
            transactions.write { reactivate(productId) }
        }

    private suspend fun reactivate(productId: Long): TxResult<ReactivateProductResult> {
        val product = products.findById(productId)
            ?: return TxResult.Rollback(ReactivateProductResult.NotFound)
        if (!product.isArchived) return TxResult.Rollback(ReactivateProductResult.NotArchived)
        val details = product.details
        products.findActiveByNormalizedName(details.normalizedName)?.let {
            return TxResult.Rollback(ReactivateProductResult.NameTaken(it.details.name))
        }
        details.barcode?.let { products.findActiveByBarcode(it) }?.let {
            return TxResult.Rollback(ReactivateProductResult.BarcodeTaken(it.details.name))
        }
        val now = clock.now()
        val activeAlert = alerts.findActiveByProducts(listOf(productId)).firstOrNull()
        val transition = LowStockEvaluator.evaluateWithoutMovement(
            stockMode = details.stockMode,
            isArchived = false,
            onHand = product.onHand,
            minStock = details.minStock,
            hasActiveAlert = activeAlert != null,
        )
        products.setArchivedAt(productId, null, now)
        if (transition != LowStockTransition.Unchanged) {
            val time = calendar.stamp(now)
                ?: return TxResult.Rollback(ReactivateProductResult.UnknownTimeZone)
            alerts.applyWithoutNotice(productId, transition, activeAlert?.id, time)
        }
        return TxResult.Commit(ReactivateProductResult.Reactivated)
    }
}
