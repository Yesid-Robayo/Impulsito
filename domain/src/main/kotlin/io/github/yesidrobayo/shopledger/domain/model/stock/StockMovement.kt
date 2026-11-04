package io.github.yesidrobayo.shopledger.domain.model.stock

import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli

/**
 * Una fila ya guardada del libro de stock, con lo que necesitan las reglas de cantidades.
 *
 * Nunca se edita ni se borra. [id] es también el orden del libro: la fila de id mayor se registró
 * después, aunque su fecha sea anterior. [quantity] va en milésimas de la unidad base del producto
 * y con signo: lo que entra suma y lo que sale resta. [reversesMovementId] solo lo lleva un
 * compensatorio, y es el id de la fila que anula.
 */
data class StockMovement(
    val id: Long,
    val key: StockKey,
    val type: StockMovementType,
    val quantity: QuantityMilli,
    val reversesMovementId: Long?,
)
