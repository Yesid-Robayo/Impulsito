package io.github.yesidrobayo.shopledger.domain.model.document

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli

/**
 * Una línea de cotización o de pedido como se guarda: el precio, el impuesto y el nombre del
 * producto quedan congelados al guardarla, como en una línea de venta, pero sin costo, porque un
 * documento para el cliente nunca lo lleva.
 *
 * - [unitId] es la unidad en que se pidió, [unitFactor] las milésimas de la unidad base que trae una
 *   unidad entera, [enteredQuantity] lo escrito en esa unidad y [quantity] lo mismo en la unidad
 *   base, que es lo que reserva un pedido.
 * - [unitPrice] es el precio por unidad entera de [unitId], y [lineDiscount] el descuento de la
 *   línea, ya en monto.
 * - [netAmount] más [taxAmount] es [lineTotal], con la tasa [taxRate] y su nombre del momento.
 */
data class NewDocumentLine(
    val productId: Long,
    val unitId: Long,
    val unitFactor: QuantityMilli,
    val enteredQuantity: QuantityMilli,
    val quantity: QuantityMilli,
    val unitPrice: Money,
    val lineDiscount: Money,
    val netAmount: Money,
    val taxRate: BasisPoints,
    val taxNameSnapshot: String?,
    val taxAmount: Money,
    val lineTotal: Money,
    val productNameSnapshot: String,
)

/** Una línea ya guardada, con su [id], de la cotización o el pedido [documentId]. */
data class DocumentLine(val id: Long, val documentId: Long, val content: NewDocumentLine)

/**
 * Lo que suman las líneas de un documento: [net] sin impuesto, [tax] el impuesto y [total] los dos.
 * Siempre `total = net + tax`, porque cada línea lo cumple.
 */
data class DocumentTotals(val net: Money, val tax: Money, val total: Money) {
    companion object {
        val ZERO = DocumentTotals(Money.ZERO, Money.ZERO, Money.ZERO)
    }
}
