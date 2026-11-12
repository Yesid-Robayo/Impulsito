package io.github.yesidrobayo.shopledger.domain.usecase.stock

import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
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
 */
internal suspend fun LowStockAlertRepository.applyAfterMovement(
    product: Product,
    movements: List<StockMovementDraft>,
    time: RecordTime,
) {
    applyTransaction(listOf(product to product.onHand), movements, time)
}

/**
 * Evalúa una sola vez la alerta de stock bajo de cada producto de [changes] tras las filas
 * [movements] de esta transacción, y la crea o la resuelve en la misma transacción. Cada producto
 * llega con su saldo antes de la primera fila de la transacción; del producto solo cuentan su modo
 * de stock, su mínimo y si está archivado.
 *
 * Un cruce hacia abajo crea la alerta pendiente de aviso: el aviso sale después de confirmar, y si
 * el proceso muere antes, queda pendiente para el siguiente arranque. Quedar por encima del mínimo
 * resuelve la alerta activa, y la próxima caída vuelve a avisar. Dos filas del mismo producto
 * cuentan como un solo cambio, así que avisan a lo sumo una vez.
 */
internal suspend fun LowStockAlertRepository.applyTransaction(
    changes: List<Pair<Product, QuantityMilli>>,
    movements: List<StockMovementDraft>,
    time: RecordTime,
) {
    val ids = changes.map { it.first.id }
    val active = findActiveByProducts(ids).associateBy { it.productId }
    val subjects = changes.map { (product, onHandBefore) ->
        LowStockSubject(
            productId = product.id,
            stockMode = product.details.stockMode,
            isArchived = product.isArchived,
            minStock = product.details.minStock,
            onHandBefore = onHandBefore,
            hasActiveAlert = product.id in active,
        )
    }
    val transitions = LowStockEvaluator.evaluateTransaction(subjects, movements)
    // En el orden de los productos, para que los avisos salgan en el orden de la transacción.
    for (productId in ids) {
        when (val transition = transitions[productId] ?: continue) {
            is LowStockTransition.Crossed ->
                insert(
                    NewLowStockAlert(
                        productId = productId,
                        onHandAtTrigger = transition.onHand,
                        minStockAtTrigger = transition.minStock,
                        notificationStatus = LowStockNotificationStatus.PENDING,
                        occurredAt = time.occurredAt,
                        businessDate = time.businessDate,
                    ),
                )

            LowStockTransition.Rearmed ->
                resolve(
                    checkNotNull(active[productId]) { "Resolver una alerta que no existe" }.id,
                    time.occurredAt,
                )

            LowStockTransition.Unchanged, LowStockTransition.NotWatched -> Unit
        }
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
