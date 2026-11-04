package io.github.yesidrobayo.shopledger.domain.model.stock

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli

/**
 * Una fila del libro de stock de un producto, con solo lo que el costo necesita para reproducirse.
 *
 * [quantity] y [totalCost] llevan el signo de la fila: una compra suma unidades y valor, y una
 * venta los resta. [unitCost] es el costo que la fila congeló al escribirse; en una corrección de
 * costo es el costo nuevo. [reversesMovementId] apunta a la fila que esta anula, si es una
 * compensación.
 *
 * Los valores se leen tal como se guardaron y nunca se recalculan: una venta conserva el costo con
 * el que salió aunque después cambie el promedio.
 */
data class CostMovement(
    val id: Long,
    val type: StockMovementType,
    val quantity: QuantityMilli,
    val unitCost: Money,
    val totalCost: Money,
    val reversesMovementId: Long? = null,
)
