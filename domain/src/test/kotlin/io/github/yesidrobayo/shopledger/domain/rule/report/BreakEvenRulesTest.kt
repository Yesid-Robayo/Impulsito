package io.github.yesidrobayo.shopledger.domain.rule.report

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Margin
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.ProfitSummary
import io.github.yesidrobayo.shopledger.domain.model.report.BreakEven
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * El punto de equilibrio, con el ejemplo de la regla y cada caso en que no se divide. Montos
 * inventados y valores escritos a mano.
 */
class BreakEvenRulesTest {
    private fun profit(netSales: Long, margin: Margin) = ProfitSummary(
        netSales = Money(netSales),
        cogs = Money.ZERO,
        grossProfit = Money.ZERO,
        grossMargin = margin,
        recordedExpenses = Money.ZERO,
        netShrinkage = Money.ZERO,
        paymentCosts = Money.ZERO,
        totalExpenses = Money.ZERO,
        netProfit = Money.ZERO,
    )

    @Test
    fun `fixed expenses of 640000 with a margin of 3600 basis points need 1777778 in sales`() {
        val result = BreakEvenRules.of(
            Money(640_000),
            profit(1_000_000, Margin.Defined(BasisPoints(3_600))),
        )

        assertEquals(
            BreakEven.Calculated(
                fixedExpenses = Money(640_000),
                margin = BasisPoints(3_600),
                breakEvenSales = Money(1_777_778),
                netSales = Money(1_000_000),
                remaining = Money(777_778),
            ),
            result,
        )
    }

    @Test
    fun `sales above the break-even point leave nothing remaining`() {
        val result = BreakEvenRules.of(
            Money(640_000),
            profit(2_000_000, Margin.Defined(BasisPoints(3_600))),
        )

        assertEquals(Money.ZERO, (result as BreakEven.Calculated).remaining)
    }

    @Test
    fun `selling exactly the break-even point leaves nothing remaining`() {
        val result = BreakEvenRules.of(
            Money(640_000),
            profit(1_777_778, Margin.Defined(BasisPoints(3_600))),
        )

        assertEquals(Money.ZERO, (result as BreakEven.Calculated).remaining)
    }

    @Test
    fun `a zero or negative margin does not divide`() {
        assertEquals(
            BreakEven.NoMargin(Money(640_000)),
            BreakEvenRules.of(Money(640_000), profit(500_000, Margin.Defined(BasisPoints.ZERO))),
        )
        assertEquals(
            BreakEven.NoMargin(Money(640_000)),
            BreakEvenRules.of(Money(640_000), profit(500_000, Margin.Defined(BasisPoints(-500)))),
        )
    }

    @Test
    fun `without sales there is no margin and nothing is divided`() {
        assertEquals(
            BreakEven.NoMargin(Money(640_000)),
            BreakEvenRules.of(Money(640_000), profit(0, Margin.NoData)),
        )
    }

    @Test
    fun `sales without fixed expenses say so, and nothing at all hides the tool`() {
        assertEquals(
            BreakEven.NoFixedExpenses,
            BreakEvenRules.of(Money.ZERO, profit(500_000, Margin.Defined(BasisPoints(3_000)))),
        )
        assertNull(BreakEvenRules.of(Money.ZERO, profit(0, Margin.NoData)))
    }

    @Test
    fun `a sum or a division that does not fit has no value`() {
        assertEquals(BreakEven.Unavailable, BreakEvenRules.of(Money(640_000), null))
        assertEquals(
            BreakEven.Unavailable,
            BreakEvenRules.of(Money(640_000), profit(500_000, Margin.OutOfRange)),
        )
        assertEquals(
            BreakEven.Unavailable,
            BreakEvenRules.of(
                Money(1_000_000_000_000_000_000L),
                profit(500_000, Margin.Defined(BasisPoints(1))),
            ),
        )
    }
}
