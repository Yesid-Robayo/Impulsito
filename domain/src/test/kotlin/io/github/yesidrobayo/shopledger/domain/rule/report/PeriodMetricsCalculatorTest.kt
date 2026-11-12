package io.github.yesidrobayo.shopledger.domain.rule.report

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Checked
import io.github.yesidrobayo.shopledger.domain.model.Margin
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.ProfitSummary
import io.github.yesidrobayo.shopledger.domain.model.ProfitTotals
import io.github.yesidrobayo.shopledger.domain.model.report.PeriodMetrics
import io.github.yesidrobayo.shopledger.domain.model.report.PeriodTotals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Las métricas de un período desde sus sumas, con los ejemplos de las reglas escritos a mano. */
class PeriodMetricsCalculatorTest {
    @Test
    fun `a period without data shows zeros, no margin and no ticket`() {
        assertEquals(
            PeriodMetrics(
                profit = summary(),
                salesCount = 0,
                averageTicket = null,
                hasMovements = false,
            ),
            metricsOf(PeriodTotals.ZERO),
        )
    }

    @Test
    fun `a period with expenses and no sales has a negative final profit`() {
        val metrics = metricsOf(totals(expenses = 45_000))

        assertEquals(Money(-45_000), metrics.profit.netProfit)
        assertEquals(Money(45_000), metrics.profit.totalExpenses)
        assertEquals(Margin.NoData, metrics.profit.grossMargin)
        assertNull(metrics.averageTicket)
        assertTrue(metrics.hasMovements)
    }

    @Test
    fun `the month of the rule example gives its profit`() {
        // Ventas por 4.250.000 que costaron 2.720.000, gastos por 640.000 y daños por 85.000.
        assertEquals(
            ProfitSummary(
                netSales = Money(4_250_000),
                cogs = Money(2_720_000),
                grossProfit = Money(1_530_000),
                grossMargin = Margin.Defined(BasisPoints(3_600)),
                recordedExpenses = Money(640_000),
                netShrinkage = Money(85_000),
                paymentCosts = Money.ZERO,
                totalExpenses = Money(725_000),
                netProfit = Money(805_000),
            ),
            metricsOf(
                totals(
                    net = 4_250_000,
                    cost = 2_720_000,
                    count = 312,
                    expenses = 640_000,
                    shrinkage = 85_000,
                ),
            ).profit,
        )
    }

    @Test
    fun `the average ticket of the rule example rounds to the nearest minor unit`() {
        // 4.250.000 en 312 ventas: exacto 13.621,79.
        val metrics = metricsOf(totals(net = 4_250_000, count = 312))

        assertEquals(Money(13_622), metrics.averageTicket)
        assertEquals(312L, metrics.salesCount)
    }

    @Test
    fun `the average ticket is measured after returns`() {
        // 30.000 vendidos en 4 ventas, menos 10.000 devueltos: 20.000 / 4.
        assertEquals(
            Money(5_000),
            metricsOf(totals(net = 30_000, count = 4, refund = 10_000)).averageTicket,
        )
    }

    @Test
    fun `more returns than sales leave negative sales without margin`() {
        // Una venta de 1.000 y una devolución de 10.000 de una venta anterior: −9.000 en una venta.
        val metrics = metricsOf(totals(net = 1_000, count = 1, refund = 10_000))

        assertEquals(Money(-9_000), metrics.profit.netSales)
        assertEquals(Money(-9_000), metrics.averageTicket)
        assertEquals(Margin.NoData, metrics.profit.grossMargin)
    }

    @Test
    fun `a sale of zero is still a movement and has a ticket of zero`() {
        val metrics = metricsOf(totals(count = 1))

        assertTrue(metrics.hasMovements)
        assertEquals(Money.ZERO, metrics.averageTicket)
    }

    @Test
    fun `each sum alone is a movement of the period`() {
        val alone = listOf(
            totals(net = 1),
            totals(refund = 1),
            totals(cost = 1),
            totals(costReturned = 1),
            totals(expenses = 1),
            totals(shrinkage = -1),
            totals(fees = 1),
            totals(rounding = -1),
        )
        for (sums in alone) assertTrue("$sums", metricsOf(sums).hasMovements)
        assertFalse(metricsOf(totals()).hasMovements)
    }

    @Test
    fun `a sum that does not fit in a long is an overflow and not a crash`() {
        assertEquals(
            Checked.Overflow,
            PeriodMetricsCalculator.of(totals(net = Long.MAX_VALUE, refund = -1, count = 1)),
        )
    }

    private fun metricsOf(totals: PeriodTotals): PeriodMetrics =
        (PeriodMetricsCalculator.of(totals) as Checked.Value).value

    private fun summary() = ProfitSummary(
        netSales = Money.ZERO,
        cogs = Money.ZERO,
        grossProfit = Money.ZERO,
        grossMargin = Margin.NoData,
        recordedExpenses = Money.ZERO,
        netShrinkage = Money.ZERO,
        paymentCosts = Money.ZERO,
        totalExpenses = Money.ZERO,
        netProfit = Money.ZERO,
    )

    private fun totals(
        net: Long = 0,
        cost: Long = 0,
        count: Long = 0,
        refund: Long = 0,
        costReturned: Long = 0,
        expenses: Long = 0,
        shrinkage: Long = 0,
        fees: Long = 0,
        rounding: Long = 0,
    ) = PeriodTotals(
        ProfitTotals(
            salesNetTotal = Money(net),
            returnsNetRefund = Money(refund),
            salesTotalCost = Money(cost),
            returnsCostReturned = Money(costReturned),
            recordedExpenses = Money(expenses),
            netShrinkage = Money(shrinkage),
            paymentFees = Money(fees),
            cashRoundingTotal = Money(rounding),
        ),
        salesCount = count,
    )
}
