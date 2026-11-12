package io.github.yesidrobayo.shopledger.domain.usecase.stock

import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.region.RecordTime
import io.github.yesidrobayo.shopledger.domain.model.stock.CostMovement
import io.github.yesidrobayo.shopledger.domain.model.stock.LowStockNotificationStatus
import io.github.yesidrobayo.shopledger.domain.model.stock.NewLowStockAlert
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementDraft
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementRecord
import io.github.yesidrobayo.shopledger.domain.port.LowStockAlertRepository
import io.github.yesidrobayo.shopledger.domain.rule.stock.LowStockEvaluator
import io.github.yesidrobayo.shopledger.domain.rule.stock.LowStockSubject
import io.github.yesidrobayo.shopledger.domain.rule.stock.LowStockTransition

/**
 * Evalúa la alerta de stock bajo de [product] tras las filas [movements] de esta transacción, y la
 * crea o la resuelve en la misma transacción. [product] es el que se leyó antes de la primera fila.
 *
 * Un cruce hacia abajo crea la alerta pendiente de aviso: el aviso sale después de confirmar, y si
 * el proceso muere antes, queda pendiente para el siguiente arranque. Quedar por encima del mínimo
 * resuelve la alerta activa, y la próxima caída vuelve a avisar.
 */
internal suspend fun LowStockAlertRepository.applyAfterMovement(
    product: Product,
    movements: List<StockMovementDraft>,
    time: RecordTime,
) {
    val active = findActiveByProducts(listOf(product.id)).firstOrNull()
    val subject = LowStockSubject(
        productId = product.id,
        stockMode = product.details.stockMode,
        isArchived = product.isArchived,
        minStock = product.details.minStock,
        onHandBefore = product.onHand,
        hasActiveAlert = active != null,
    )
    val transitions = LowStockEvaluator.evaluateTransaction(listOf(subject), movements)
    when (val transition = checkNotNull(transitions[product.id]) { "Falta el producto movido" }) {
        is LowStockTransition.Crossed ->
            insert(
                NewLowStockAlert(
                    productId = product.id,
                    onHandAtTrigger = transition.onHand,
                    minStockAtTrigger = transition.minStock,
                    notificationStatus = LowStockNotificationStatus.PENDING,
                    occurredAt = time.occurredAt,
                    businessDate = time.businessDate,
                ),
            )

        LowStockTransition.Rearmed ->
            resolve(
                checkNotNull(active) { "Resolver una alerta que no existe" }.id,
                time.occurredAt,
            )

        LowStockTransition.Unchanged, LowStockTransition.NotWatched -> Unit
    }
}

/** La fila guardada con lo que necesita la reproducción del costo. */
internal fun StockMovementRecord.toCostMovement(): CostMovement = CostMovement(
    id = id,
    type = movement.type,
    quantity = movement.quantity,
    unitCost = unitCost,
    totalCost = totalCost,
    reversesMovementId = movement.reversesMovementId,
)
