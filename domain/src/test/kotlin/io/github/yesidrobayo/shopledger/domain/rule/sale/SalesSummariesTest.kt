package io.github.yesidrobayo.shopledger.domain.rule.sale

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Checked
import io.github.yesidrobayo.shopledger.domain.model.Margin
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.sale.SalesSummary
import io.github.yesidrobayo.shopledger.domain.model.sale.SalesTotals
import org.junit.Assert.assertEquals
import org.junit.Test

/** Lo vendido y la ganancia en ventas de un período, con los ejemplos escritos de la regla. */
class SalesSummariesTest {
    @Test
    fun `the month without returns gives the written profit and margin`() {
        val totals =
            SalesTotals(
                Money(4_250_000),
                Money.ZERO,
                Money(2_720_000),
                Money.ZERO,
                salesCount = 312,
            )

        assertEquals(
            Checked.Value(
                SalesSummary(
                    netSales = Money(4_250_000),
                    salesCount = 312,
                    grossProfit = Money(1_530_000),
                    grossMargin = Margin.Defined(BasisPoints(3_600)),
                ),
            ),
            SalesSummaries.of(totals),
        )
    }

    @Test
    fun `returns subtract their refund and their cost, but not the number of sales`() {
        val totals = SalesTotals(
            salesNetTotal = Money(4_250_000),
            returnsNetRefund = Money(50_000),
            salesTotalCost = Money(2_720_000),
            returnsCostReturned = Money(30_000),
            salesCount = 312,
        )

        assertEquals(
            Checked.Value(
                SalesSummary(
                    netSales = Money(4_200_000),
                    salesCount = 312,
                    grossProfit = Money(1_510_000),
                    // 35,95 %: el margen sale de las sumas, al punto básico.
                    grossMargin = Margin.Defined(BasisPoints(3_595)),
                ),
            ),
            SalesSummaries.of(totals),
        )
    }

    @Test
    fun `a day without sales sums zero and has no margin`() {
        assertEquals(
            Checked.Value(SalesSummary(Money.ZERO, 0, Money.ZERO, Margin.NoData)),
            SalesSummaries.of(SalesTotals.ZERO),
        )
    }

    @Test
    fun `selling below cost gives a negative profit, not a hidden one`() {
        val totals = SalesTotals(Money(10_000), Money.ZERO, Money(12_000), Money.ZERO, 1)

        val summary = (SalesSummaries.of(totals) as Checked.Value).value

        assertEquals(Money(-2_000), summary.grossProfit)
        assertEquals(Margin.Defined(BasisPoints(-2_000)), summary.grossMargin)
    }

    @Test
    fun `an absurd sum that does not fit comes back as overflow instead of failing`() {
        val totals = SalesTotals(Money(Long.MIN_VALUE), Money(1), Money.ZERO, Money.ZERO, 1)

        assertEquals(Checked.Overflow, SalesSummaries.of(totals))
    }
}
