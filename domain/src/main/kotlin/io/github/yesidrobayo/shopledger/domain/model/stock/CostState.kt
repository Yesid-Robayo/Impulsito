package io.github.yesidrobayo.shopledger.domain.model.stock

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli

/**
 * Lo que el costo de un producto sabe de su stock: cuántas milésimas hay, cuánto valen en total y
 * a qué costo promedio.
 *
 * [onHand] puede ser negativo, porque vender nunca se bloquea por falta de stock: el número
 * negativo avisa que falta registrar una compra o un conteo.
 *
 * [stockValue] es el valor total de esas milésimas en la unidad menor de la moneda. Se lleva aparte
 * porque el promedio redondeado no basta: una caja de 24 unidades por 10000 cuesta 416,67 por
 * unidad, y una de 1000 por 400 cuesta 0,4, que redondeado sería 0, como si no tuviera costo.
 * Con el total, vender toda la caja se lleva exactamente lo que costó. Nunca es negativo, y vale
 * cero sin unidades: con stock en cero o negativo no hay nada que valorar.
 *
 * [averageCost] es el costo de una unidad entera, redondeado a la unidad menor. Es el que congela
 * cada venta para mostrarlo, y el que valora lo que sale cuando no hay stock.
 *
 * Que el valor y el promedio sean cero a la vez significa "sin costo conocido". Un valor mayor que
 * cero con promedio cero sí tiene costo: el de 0,4 por unidad.
 */
data class CostState(val onHand: QuantityMilli, val averageCost: Money, val stockValue: Money) {
    init {
        require(stockValue >= Money.ZERO) { "El valor del stock nunca es negativo" }
    }

    companion object {
        /**
         * El punto de partida de un producto sin movimientos: sin stock, sin valor y con
         * [initialCost], el costo que se escribió al crearlo.
         */
        fun initial(initialCost: Money): CostState =
            CostState(QuantityMilli.ZERO, initialCost, Money.ZERO)
    }
}
