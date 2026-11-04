package io.github.yesidrobayo.shopledger.domain.model.stock

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import kotlin.time.Instant

/**
 * Una fila completa ya guardada del libro de stock. Nunca se edita ni se borra.
 *
 * [movement] lleva el id, que también es el orden del libro, y lo que usan las reglas de
 * cantidades. Los demás campos son los de [NewStockMovement], tal como se guardaron: nada de esto
 * se recalcula al leer, porque una venta conserva el costo con el que salió.
 */
data class StockMovementRecord(
    val movement: StockMovement,
    val reason: StockAdjustmentReason?,
    val unitCost: Money,
    val totalCost: Money,
    val balanceAfter: QuantityMilli,
    val averageCostAfter: Money,
    val stockValueAfter: Money,
    val source: StockMovementSource,
    val note: String?,
    val occurredAt: Instant,
    val businessDate: BusinessDate,
    val createdAt: Instant,
) {
    /** El id de la fila, que es su lugar en el orden del libro. */
    val id: Long get() = movement.id
}
