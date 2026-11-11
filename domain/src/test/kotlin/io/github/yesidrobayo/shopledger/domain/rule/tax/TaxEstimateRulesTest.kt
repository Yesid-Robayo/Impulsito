package io.github.yesidrobayo.shopledger.domain.rule.tax

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.tax.TaxEstimate
import io.github.yesidrobayo.shopledger.domain.model.tax.TaxTotals
import org.junit.Assert.assertEquals
import org.junit.Test

class TaxEstimateRulesTest {
    @Test
    fun `the worked example subtracts the refunds from the collected tax`() {
        // 52500 cobrados, 2100 devueltos y 21000 pagados.
        val totals = TaxTotals(Money(52_500), Money(2_100), Money(21_000))

        assertEquals(
            TaxEstimate(collected = Money(50_400), paid = Money(21_000)),
            TaxEstimateRules.estimate(totals),
        )
    }

    @Test
    fun `a period without documents estimates zero`() {
        assertEquals(TaxEstimate(Money.ZERO, Money.ZERO), TaxEstimateRules.estimate(TaxTotals.ZERO))
    }

    @Test
    fun `more refunded than collected in the period leaves the collected tax negative`() {
        val totals = TaxTotals(Money(1_000), Money(2_100), Money.ZERO)

        assertEquals(Money(-1_100), TaxEstimateRules.estimate(totals).collected)
    }

    @Test
    fun `the largest sums still subtract without overflow`() {
        val totals = TaxTotals(Money(Long.MAX_VALUE), Money(Long.MAX_VALUE), Money(Long.MAX_VALUE))

        assertEquals(
            TaxEstimate(Money.ZERO, Money(Long.MAX_VALUE)),
            TaxEstimateRules.estimate(totals),
        )
    }
}
