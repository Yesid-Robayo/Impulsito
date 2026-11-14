package io.github.yesidrobayo.shopledger.domain.model.catalog

import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli

/**
 * Un componente guardado de un combo o de una receta: cuánto de [componentProductId] lleva una
 * unidad de [parentProductId], en milésimas de la unidad base del componente.
 *
 * La receta solo dice qué se descuenta al vender. Cada venta congela lo que consumió, así que
 * cambiar la receta nunca cambia una venta pasada.
 */
data class RecipeComponent(
    val id: Long,
    val parentProductId: Long,
    val componentProductId: Long,
    val quantity: QuantityMilli,
)

/**
 * Una fila de la receta tal como la arma la persona antes de guardar: qué producto y cuánto lleva
 * una unidad del combo, en milésimas de la unidad base del componente.
 */
data class RecipeLine(val componentProductId: Long, val quantity: QuantityMilli)
