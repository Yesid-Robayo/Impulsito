package io.github.yesidrobayo.shopledger.domain.usecase.catalog

import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.LowStockAlertRepository
import io.github.yesidrobayo.shopledger.domain.port.ProductRepository
import io.github.yesidrobayo.shopledger.domain.port.ProductVariantRepository
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.rule.stock.LowStockEvaluator
import io.github.yesidrobayo.shopledger.domain.rule.stock.LowStockTransition
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import kotlin.time.Instant

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

    /**
     * Es una variante y su producto principal está archivado: hay que reactivar el principal, que
     * la devuelve junto con las demás que se archivaron con él.
     */
    data object ParentArchived : ReactivateProductResult

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
 *
 * Un producto con variantes vuelve con las variantes que se archivaron con él, en el mismo
 * instante, y no con las que la persona archivó antes una por una. Si alguna choca por nombre o
 * código, no vuelve ninguna. Una variante no vuelve sola mientras su principal siga archivado. El
 * principal no se vigila: no tiene stock propio.
 */
class ReactivateProductUseCase(
    private val transactions: TransactionRunner,
    private val products: ProductRepository,
    private val alerts: LowStockAlertRepository,
    private val calendar: BusinessCalendarSource,
    private val clock: Clock,
    private val variants: ProductVariantRepository,
) {
    suspend operator fun invoke(productId: Long): ReactivateProductResult =
        storageSafe(ReactivateProductResult::StorageFailed) {
            transactions.write { reactivate(productId) }
        }

    private suspend fun reactivate(productId: Long): TxResult<ReactivateProductResult> {
        val product = products.findById(productId)
            ?: return TxResult.Rollback(ReactivateProductResult.NotFound)
        if (!product.isArchived) return TxResult.Rollback(ReactivateProductResult.NotArchived)
        val parentId = product.details.parentProductId
        if (parentId != null && products.findById(parentId)?.isArchived == true) {
            return TxResult.Rollback(ReactivateProductResult.ParentArchived)
        }
        val family = variants.findVariants(productId)
        val returning = listOf(product) +
            family.filter { it.archivedAt == product.archivedAt }
        for (candidate in returning) {
            clashOf(candidate)?.let { return TxResult.Rollback(it) }
        }
        val now = clock.now()
        for (candidate in returning) {
            // El principal no se vigila: su stock es la suma de sus variantes.
            val watched = candidate.id != productId || family.isEmpty()
            products.setArchivedAt(candidate.id, null, now)
            if (watched && !rearm(candidate, now)) {
                return TxResult.Rollback(ReactivateProductResult.UnknownTimeZone)
            }
        }
        return TxResult.Commit(ReactivateProductResult.Reactivated)
    }

    /** Por qué [product] no puede volver, o `null` si nadie activo usa su nombre ni su código. */
    private suspend fun clashOf(product: Product): ReactivateProductResult? {
        val details = product.details
        products.findActiveByNormalizedName(details.normalizedName)?.let {
            return ReactivateProductResult.NameTaken(it.details.name)
        }
        details.barcode?.let { products.findActiveByBarcode(it) }?.let {
            return ReactivateProductResult.BarcodeTaken(it.details.name)
        }
        return null
    }

    /**
     * Crea callada o resuelve la alerta de [product] según su stock al volver. `false` si hacía
     * falta una fecha de negocio y la zona guardada no se reconoce.
     */
    private suspend fun rearm(product: Product, now: Instant): Boolean {
        val details = product.details
        val activeAlert = alerts.findActiveByProducts(listOf(product.id)).firstOrNull()
        val transition = LowStockEvaluator.evaluateWithoutMovement(
            stockMode = details.stockMode,
            isArchived = false,
            onHand = product.onHand,
            minStock = details.minStock,
            hasActiveAlert = activeAlert != null,
        )
        if (transition == LowStockTransition.Unchanged) return true
        val time = calendar.stamp(now) ?: return false
        alerts.applyWithoutNotice(product.id, transition, activeAlert?.id, time)
        return true
    }
}
