package io.github.yesidrobayo.shopledger.domain.rule

import io.github.yesidrobayo.shopledger.domain.model.Money

/**
 * Cómo se reparte el monto de una línea entre lo que es de quien vende y el impuesto.
 *
 * [netAmount] va sin impuesto, y es la base del margen y de la ganancia: el impuesto se cobra para
 * otro. [taxAmount] es el impuesto y [lineTotal] lo que paga quien compra. Siempre
 * `lineTotal = netAmount + taxAmount`, y por eso un total que suma sus líneas cuadra con la suma de
 * sus netos y sus impuestos.
 */
data class LineTax(val netAmount: Money, val taxAmount: Money, val lineTotal: Money)
