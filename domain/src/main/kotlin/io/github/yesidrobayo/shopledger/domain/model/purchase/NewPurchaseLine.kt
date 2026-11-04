package io.github.yesidrobayo.shopledger.domain.model.purchase

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli

/**
 * Una línea de compra que el caso de uso va a guardar, todavía sin id. Un producto puede repetirse
 * en una compra.
 *
 * [enteredQuantity] está en la unidad [unitId] en que se compró, y [quantity] en la unidad base del
 * producto, que es la del libro de stock: [unitFactor] es cuántas unidades base trae una unidad
 * [unitId], copiado al momento de comprar. [unitCost] es por una unidad entera de [unitId].
 * [lineTotal] lleva el impuesto, y [costAmount] es lo que la línea suma al valor del stock: sin el
 * impuesto si es recuperable. La tasa y el nombre del impuesto se copian, porque pueden cambiar
 * después. [referenceUnitCost] va en la moneda en que se compró, si no es la del libro.
 */
data class NewPurchaseLine(
    val productId: Long,
    val unitId: Long,
    val unitFactor: QuantityMilli,
    val enteredQuantity: QuantityMilli,
    val quantity: QuantityMilli,
    val unitCost: Money,
    val lineTotal: Money,
    val taxRate: BasisPoints,
    val taxNameSnapshot: String?,
    val taxAmount: Money,
    val costAmount: Money,
    val lotId: Long?,
    val referenceUnitCost: Money?,
)
