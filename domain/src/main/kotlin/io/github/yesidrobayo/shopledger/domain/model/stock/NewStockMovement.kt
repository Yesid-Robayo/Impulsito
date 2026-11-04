package io.github.yesidrobayo.shopledger.domain.model.stock

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import kotlin.time.Instant

/**
 * Una fila completa que un caso de uso va a agregar al libro de stock, todavía sin id.
 *
 * [movement] es lo que revisan las reglas de cantidades: producto, ubicación, lote, tipo, cantidad
 * y fila anulada. El resto lo calcula el caso de uso antes de escribir, dentro de la misma
 * transacción, porque la base solo guarda: [unitCost] es el costo que la fila congela, y
 * [totalCost], con el signo de la cantidad, el que suma o resta al valor del stock.
 *
 * [balanceAfter], [averageCostAfter] y [stockValueAfter] son el saldo, el costo promedio y el valor
 * del stock del producto justo después de esta fila. Al guardarla, el producto queda con esos tres
 * valores: así cada fila dice cómo dejó al producto, y una copia puede comprobarlo.
 *
 * [reason] solo lo lleva un ajuste, y [note] lo explica. [businessDate] de un compensatorio es la
 * fecha de la anulación, no la del original.
 */
data class NewStockMovement(
    val movement: StockMovementDraft,
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
    /** La fila que queda en el libro cuando la base la guarda con [id]. */
    fun recordedAs(id: Long): StockMovementRecord = StockMovementRecord(
        movement = movement.recordedAs(id),
        reason = reason,
        unitCost = unitCost,
        totalCost = totalCost,
        balanceAfter = balanceAfter,
        averageCostAfter = averageCostAfter,
        stockValueAfter = stockValueAfter,
        source = source,
        note = note,
        occurredAt = occurredAt,
        businessDate = businessDate,
        createdAt = createdAt,
    )
}
