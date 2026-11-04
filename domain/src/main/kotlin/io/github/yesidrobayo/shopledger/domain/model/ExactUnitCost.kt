package io.github.yesidrobayo.shopledger.domain.model

/**
 * El costo de una unidad entera sin redondear: [amount] por [quantity].
 *
 * Una caja de 1000 unidades por 400 cuesta 0,4 por unidad, que no cabe en un monto entero:
 * redondeado daría 0, como si el producto no tuviera costo, y una caja de 24 por 10000 daría 417 en
 * vez de 416,67. Aquí se guarda la fracción tal cual, y quien la usa redondea solo su resultado.
 *
 * Un costo que ya es un monto entero por unidad, como el costo de reposición o el costo promedio,
 * es ese monto por una unidad entera: [perUnit].
 */
data class ExactUnitCost(val amount: Money, val quantity: QuantityMilli) {
    init {
        require(quantity > QuantityMilli.ZERO) { "La cantidad de un costo debe ser positiva" }
    }

    companion object {
        /** [amount] por una unidad entera. */
        fun perUnit(amount: Money): ExactUnitCost =
            ExactUnitCost(amount, QuantityMilli(Scale.MILLI))
    }
}
