package io.github.yesidrobayo.shopledger.domain.rule

import io.github.yesidrobayo.shopledger.domain.model.Money

/**
 * El impuesto de una línea de compra y lo que suma al costo del producto.
 *
 * [lineTotal] es lo que se pagó, con el impuesto, y [taxAmount] el impuesto que trae. [costAmount]
 * es el valor con que la línea entra al stock, el total de la entrada en el costo promedio: sin el
 * impuesto si quien compra lo recupera, porque entonces no es un costo suyo; con él, si no.
 */
data class PurchaseLineTax(val lineTotal: Money, val taxAmount: Money, val costAmount: Money)
