package io.github.yesidrobayo.shopledger.domain.model.sale

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli

/**
 * Una línea de la venta tal como la armó la persona, antes de confirmar.
 *
 * [unitId] es la unidad en que se vende: `null` o la unidad base del producto es la venta de siempre.
 * Con otra unidad, como una caja, [quantity] va en milésimas de esa unidad, y la venta la lleva a la
 * unidad base con el factor de su conversión, que la línea congela. [unitPrice] es el precio por una
 * unidad entera de [unitId] solo para esta línea; sin él vale el precio de esa unidad: el del
 * producto en la base, o el de la conversión. Cambiarlo aquí no cambia el precio del producto ni
 * deja fila en su historial. [discount] es el descuento de la línea.
 *
 * Agregar dos veces el mismo producto lo suma la pantalla en una sola línea, en la unidad que tenga
 * esa línea; el caso de uso guarda las líneas tal como llegan, porque un producto sí puede repetirse
 * en otra unidad o como extra.
 */
data class SaleLineRequest(
    val productId: Long,
    val quantity: QuantityMilli,
    val unitPrice: Money? = null,
    val discount: LineDiscount = LineDiscount.None,
    val unitId: Long? = null,
)
