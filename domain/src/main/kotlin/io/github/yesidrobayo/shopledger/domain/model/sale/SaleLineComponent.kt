package io.github.yesidrobayo.shopledger.domain.model.sale

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli

/**
 * Una hoja que consumió una línea de combo, congelada al vender: qué producto, cuánto en toda la
 * línea, si descontó stock y a qué costo.
 *
 * Nunca cambia, ni siquiera al anular la venta: explica las filas que la línea dejó en el libro
 * aunque la receta cambie después. [quantity] está en milésimas de la unidad base del componente.
 */
data class NewSaleLineComponent(
    val componentProductId: Long,
    val quantity: QuantityMilli,
    val tracked: Boolean,
    val unitCost: Money,
    val lineCost: Money,
)

/** Una hoja guardada de una línea de combo, con su id y el de su línea. */
data class SaleLineComponent(val id: Long, val saleLineId: Long, val content: NewSaleLineComponent)
