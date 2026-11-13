package io.github.yesidrobayo.shopledger.domain.model.report

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli

/**
 * Lo que la ganancia si se repone necesita de un período, además de sus ventas sin impuesto.
 *
 * [salesReplacementCost] suma lo que costaría reponer lo vendido en las ventas activas del período,
 * congelado en cada línea al vender. [linesWithoutReplacement] cuenta las líneas que se vendieron sin
 * costo de reposición ni costo promedio: con alguna, la cifra se queda corta y la pantalla lo dice.
 * [restockedReturns] son las líneas devueltas al stock en devoluciones activas del período, en su
 * propia fecha: lo que vuelve al stock no hay que reponerlo.
 */
data class ReplacementTotals(
    val salesReplacementCost: Money,
    val linesWithoutReplacement: Long,
    val restockedReturns: List<RestockedReturnLine>,
)

/**
 * Una línea devuelta al stock, con lo que hace falta para repartir la reposición de su línea de
 * venta: [lineReplacementCost] y [lineQuantity] de la venta, [returnedBefore] lo que ya se había
 * devuelto de esa línea en devoluciones activas anteriores, y [returned] lo que devuelve esta.
 */
data class RestockedReturnLine(
    val lineReplacementCost: Money,
    val lineQuantity: QuantityMilli,
    val returnedBefore: QuantityMilli,
    val returned: QuantityMilli,
)

/**
 * La ganancia si se repone de un período. Es una vista adicional: la ganancia en ventas sigue siendo
 * la del costo promedio, y esta solo dice cuánto quedaría si hubiera que volver a comprar lo vendido
 * al costo de hoy.
 *
 * [netSales] son las ventas sin impuesto menos lo reembolsado, las mismas de la ganancia en ventas.
 * [replacementCogs] es lo que costaría reponer lo vendido, menos lo que volvió al stock, y
 * [replacementProfit] la diferencia. [linesWithoutReplacement] viene de [ReplacementTotals].
 */
data class ReplacementProfit(
    val netSales: Money,
    val replacementCogs: Money,
    val replacementProfit: Money,
    val linesWithoutReplacement: Long,
)
