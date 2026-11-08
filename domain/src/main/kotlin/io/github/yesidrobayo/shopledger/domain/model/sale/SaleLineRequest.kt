package io.github.yesidrobayo.shopledger.domain.model.sale

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli

/**
 * Una línea de la venta tal como la armó la persona, antes de confirmar.
 *
 * [quantity] va en milésimas de la unidad base del producto: vender en otra unidad llega después,
 * con su factor de conversión. [unitPrice] es el precio por unidad entera solo para esta línea; sin
 * él vale el precio de venta del producto. Cambiarlo aquí no cambia el precio del producto ni deja
 * fila en su historial. [discount] es el descuento de la línea.
 *
 * Agregar dos veces el mismo producto con la misma unidad lo suma la pantalla en una sola línea; el
 * caso de uso guarda las líneas tal como llegan, porque un producto sí puede repetirse en otra
 * unidad o como extra.
 */
data class SaleLineRequest(
    val productId: Long,
    val quantity: QuantityMilli,
    val unitPrice: Money? = null,
    val discount: LineDiscount = LineDiscount.None,
)
