package io.github.yesidrobayo.shopledger.domain.model.sale

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli

/**
 * Una línea de venta que el caso de uso va a guardar, todavía sin id. Un producto puede repetirse
 * en una venta, con otra unidad o como extra de otra línea.
 *
 * [enteredQuantity] está en la unidad [unitId] en que se vendió, y [quantity] en la unidad base del
 * producto: [unitFactor] es cuántas unidades base trae una unidad [unitId], copiado al vender.
 * [unitPrice] es por una unidad entera de [unitId], y [unitCost] por una de la unidad base: los dos
 * quedan congelados aunque después cambien el precio o el costo.
 *
 * [unitCost] es solo para mostrar: el costo real de la línea es [lineCost], que sale del valor del
 * stock y no de multiplicar. Por eso un [unitCost] en cero no significa que la línea no tenga
 * costo. [orderAdjustmentShare] es la parte de la línea en el ajuste de toda la venta, con signo.
 * [netAmount] va sin impuesto, [taxAmount] es el impuesto y [lineTotal] su suma. La tasa, el nombre
 * del impuesto y el del producto se copian, porque pueden cambiar después.
 *
 * [parentLineId] es la línea principal de un extra: la base da su id al guardarla, y por eso las
 * líneas se guardan una a una, la principal antes que sus extras.
 */
data class NewSaleLine(
    val productId: Long,
    val unitId: Long,
    val unitFactor: QuantityMilli,
    val enteredQuantity: QuantityMilli,
    val quantity: QuantityMilli,
    val unitPrice: Money,
    val unitCost: Money,
    val lineDiscount: Money,
    val orderAdjustmentShare: Money,
    val netAmount: Money,
    val taxRate: BasisPoints,
    val taxNameSnapshot: String?,
    val taxAmount: Money,
    val lineTotal: Money,
    val lineCost: Money,
    val unitReplacementCost: Money,
    val lineReplacementCost: Money,
    val productNameSnapshot: String,
    val stockEffect: LineStockEffect,
    val parentLineId: Long?,
    val priceListId: Long,
)
