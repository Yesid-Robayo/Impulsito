package io.github.yesidrobayo.shopledger.domain.model.stock

import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli

/**
 * Una fila que un caso de uso quiere agregar al libro de stock y que todavía no tiene id: se lo da
 * la base al guardarla, y ese id fija su lugar en el orden del libro.
 *
 * Tiene los mismos campos que [StockMovement], salvo el id. [reversesMovementId] solo lo lleva un
 * compensatorio.
 */
data class StockMovementDraft(
    val key: StockKey,
    val type: StockMovementType,
    val quantity: QuantityMilli,
    val reversesMovementId: Long?,
) {
    /** La fila que queda en el libro cuando la base la guarda con [id]. */
    fun recordedAs(id: Long): StockMovement = StockMovement(
        id = id,
        key = key,
        type = type,
        quantity = quantity,
        reversesMovementId = reversesMovementId,
    )
}
