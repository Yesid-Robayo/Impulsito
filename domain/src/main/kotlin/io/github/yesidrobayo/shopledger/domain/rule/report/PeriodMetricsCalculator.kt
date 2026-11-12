package io.github.yesidrobayo.shopledger.domain.rule.report

import io.github.yesidrobayo.shopledger.domain.model.Checked
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.ProfitTotals
import io.github.yesidrobayo.shopledger.domain.model.map
import io.github.yesidrobayo.shopledger.domain.model.report.PeriodMetrics
import io.github.yesidrobayo.shopledger.domain.model.report.PeriodTotals
import io.github.yesidrobayo.shopledger.domain.rule.MarginCalculator
import io.github.yesidrobayo.shopledger.domain.rule.roundDiv

/**
 * Las métricas de un período desde sus sumas: las mismas cuentas de la ganancia de Inicio, más el
 * número de ventas y su ticket promedio.
 */
object PeriodMetricsCalculator {
    /**
     * Las métricas de [totals]. Devuelve [Checked.Overflow] si alguna cifra no cabe en `Long`: solo
     * pasa con sumas absurdas, y la pantalla muestra «sin valor» en vez de caerse.
     */
    fun of(totals: PeriodTotals): Checked<PeriodMetrics> =
        MarginCalculator.profit(totals.profit).map { profit ->
            PeriodMetrics(
                profit = profit,
                salesCount = totals.salesCount,
                averageTicket = averageTicket(profit.netSales, totals.salesCount),
                hasMovements = totals.salesCount > 0 || totals.profit.hasAnySum(),
            )
        }

    /**
     * Lo vendido sin impuesto dividido por el número de ventas, redondeado a la unidad menor más
     * cercana. Sin ventas no hay promedio.
     */
    fun averageTicket(netSales: Money, salesCount: Long): Money? =
        if (salesCount > 0) Money(roundDiv(netSales.minor, salesCount)) else null

    /**
     * Si alguna suma no es cero. Una devolución o un gasto sin ventas también son movimientos del
     * período, y una merma de cero no lo es.
     */
    private fun ProfitTotals.hasAnySum(): Boolean = listOf(
        salesNetTotal,
        returnsNetRefund,
        salesTotalCost,
        returnsCostReturned,
        recordedExpenses,
        netShrinkage,
        paymentFees,
        cashRoundingTotal,
    ).any { it != Money.ZERO }
}
