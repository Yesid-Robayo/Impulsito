package io.github.yesidrobayo.shopledger.domain.model.phrase

import io.github.yesidrobayo.shopledger.domain.model.Money

/**
 * Los precios con que la tarjeta muestra sus cifras antes de registrar, leídos junto con el
 * catálogo. Solo sirven para mostrar: lo que se registra lo vuelve a calcular la operación de
 * siempre con la misma regla.
 *
 * - [sale] es el precio de venta de una unidad base, por id de producto, en la lista inicial.
 * - [saleByUnit] es el precio de una unidad entera de cada presentación de venta, por producto y
 *   unidad: el propio de la presentación, o el del producto llevado con su factor, como lo calcula
 *   la venta. Una presentación que no se vende no está: la venta la lleva a la unidad base.
 * - [lastCost] es el costo de reposición de una unidad base, o el promedio si no hay reposición,
 *   por producto. Es solo una sugerencia para una compra: nunca se registra sin que la persona lo
 *   elija.
 */
data class PhrasePrices(
    val sale: Map<Long, Money> = emptyMap(),
    val saleByUnit: Map<Pair<Long, Long>, Money> = emptyMap(),
    val lastCost: Map<Long, Money> = emptyMap(),
)
