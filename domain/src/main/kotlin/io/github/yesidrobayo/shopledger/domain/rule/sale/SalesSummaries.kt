package io.github.yesidrobayo.shopledger.domain.rule.sale

import io.github.yesidrobayo.shopledger.domain.model.Checked
import io.github.yesidrobayo.shopledger.domain.model.sale.SalesSummary
import io.github.yesidrobayo.shopledger.domain.model.sale.SalesTotals
import io.github.yesidrobayo.shopledger.domain.rule.MarginCalculator

/**
 * Lo vendido y la ganancia en ventas de un período, desde sus sumas.
 *
 * Es la misma cuenta del reporte: ventas menos devoluciones, costo menos lo devuelto, y el margen con
 * esas dos sumas. Así la cifra de Inicio coincide con la de las estadísticas del mismo período.
 */
object SalesSummaries {
    /**
     * El resumen de [totals]. Devuelve [Checked.Overflow] si alguna resta no cabe en `Long`: solo
     * pasa con sumas absurdas, y la pantalla muestra «sin valor» en vez de caerse.
     */
    fun of(totals: SalesTotals): Checked<SalesSummary> = try {
        val netSales = totals.salesNetTotal - totals.returnsNetRefund
        val cogs = totals.salesTotalCost - totals.returnsCostReturned
        Checked.Value(
            SalesSummary(
                netSales = netSales,
                salesCount = totals.salesCount,
                grossProfit = netSales - cogs,
                grossMargin = MarginCalculator.margin(netSales, cogs),
            ),
        )
    } catch (overflow: ArithmeticException) {
        Checked.Overflow
    }
}
