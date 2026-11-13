package io.github.yesidrobayo.shopledger.domain.rule.report

import io.github.yesidrobayo.shopledger.domain.model.Checked
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.report.ReplacementProfit
import io.github.yesidrobayo.shopledger.domain.model.report.ReplacementTotals
import io.github.yesidrobayo.shopledger.domain.model.report.RestockedReturnLine
import io.github.yesidrobayo.shopledger.domain.model.valueOrNull
import io.github.yesidrobayo.shopledger.domain.rule.mulDivRound

/**
 * La ganancia si se repone y el valor del inventario a reposición. Nunca tocan la ganancia en
 * ventas: esa se sigue midiendo con el costo promedio, que es la verdad del libro.
 *
 * ```text
 * replacement_profit = net_sales - suma de total_replacement_cost (ACTIVE) + reposición de lo devuelto
 * replacement_value  = suma de mulDivRound(on_hand_milli, replacement_cost, 1000), con on_hand_milli > 0
 * ```
 *
 * La reposición de lo devuelto es la parte de la reposición de cada línea devuelta al stock,
 * repartida con cantidades acumuladas como el resto de lo devuelto, para que devolver una línea por
 * partes sume exactamente su reposición.
 */
object ReplacementProfitRules {
    /**
     * La ganancia si se repone de [netSales] con [totals]. [Checked.Overflow] si alguna cifra no
     * cabe en `Long`: solo pasa con sumas absurdas, y la pantalla muestra «sin valor».
     */
    fun profit(netSales: Money, totals: ReplacementTotals): Checked<ReplacementProfit> {
        return try {
            var returned = Money.ZERO
            for (line in totals.restockedReturns) {
                returned += returnedShare(line).valueOrNull() ?: return Checked.Overflow
            }
            val cogs = totals.salesReplacementCost - returned
            Checked.Value(
                ReplacementProfit(
                    netSales = netSales,
                    replacementCogs = cogs,
                    replacementProfit = netSales - cogs,
                    linesWithoutReplacement = totals.linesWithoutReplacement,
                ),
            )
        } catch (overflow: ArithmeticException) {
            Checked.Overflow
        }
    }

    /**
     * La parte de la reposición de su línea que vuelve con [line]: lo que toca a lo devuelto hasta
     * esta devolución, menos lo que tocó a lo devuelto antes. Una línea sin cantidad no reparte nada.
     */
    fun returnedShare(line: RestockedReturnLine): Checked<Money> {
        return try {
            val quantity = line.lineQuantity.milli
            if (quantity <= 0L) {
                Checked.Value(Money.ZERO)
            } else {
                val before = line.returnedBefore.milli
                val shares = listOf(
                    Math.addExact(before, line.returned.milli),
                    before,
                ).map { returned ->
                    mulDivRound(line.lineReplacementCost.minor, returned, quantity).valueOrNull()
                        ?: return Checked.Overflow
                }
                Checked.Value(Money(Math.subtractExact(shares[0], shares[1])))
            }
        } catch (overflow: ArithmeticException) {
            Checked.Overflow
        }
    }

    /**
     * El valor del inventario a reposición: cada producto con stock positivo por su costo de
     * reposición. Un producto sin stock, o con stock negativo, no suma; uno sin reposición suma cero.
     */
    fun replacementValue(stock: List<Pair<QuantityMilli, Money>>): Checked<Money> {
        return try {
            var total = Money.ZERO
            for ((onHand, replacement) in stock) {
                if (onHand.milli <= 0L) continue
                total += Money(
                    mulDivRound(onHand.milli, replacement.minor, MILLI).valueOrNull()
                        ?: return Checked.Overflow,
                )
            }
            Checked.Value(total)
        } catch (overflow: ArithmeticException) {
            Checked.Overflow
        }
    }

    /**
     * Lo que deja una unidad vendida a [netPrice], el precio sin impuesto, si hubiera que reponerla
     * a [replacementCost]. Sin reposición o sin precio no hay cifra: `null`, nunca un número
     * inventado. Puede ser negativa, si reponer cuesta más de lo que se cobra.
     */
    fun unitProfit(netPrice: Money, replacementCost: Money): Money? =
        if (replacementCost > Money.ZERO && netPrice > Money.ZERO) {
            netPrice - replacementCost
        } else {
            null
        }

    private const val MILLI = 1_000L
}
