package io.github.yesidrobayo.shopledger.domain.rule.report

import io.github.yesidrobayo.shopledger.domain.model.Checked
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.report.ReplacementProfit
import io.github.yesidrobayo.shopledger.domain.model.report.ReplacementTotals
import io.github.yesidrobayo.shopledger.domain.model.report.RestockedReturnLine
import org.junit.Assert.assertEquals
import org.junit.Test

/** La ganancia si se repone y el valor a reposición, con los ejemplos de las reglas. */
class ReplacementProfitRulesTest {
    private fun returned(cost: Long, quantity: Long, before: Long, now: Long) = RestockedReturnLine(
        Money(cost),
        QuantityMilli(quantity),
        QuantityMilli(before),
        QuantityMilli(now),
    )

    @Test
    fun `eight units sold for 16000 with replacement 12000 leave 4000 if replaced`() {
        // El ejemplo de la regla: con costo promedio 8000 la ganancia en ventas es 8000.
        val profit = ReplacementProfitRules.profit(
            Money(16_000),
            ReplacementTotals(Money(12_000), 0, emptyList()),
        )

        assertEquals(
            Checked.Value(ReplacementProfit(Money(16_000), Money(12_000), Money(4_000), 0)),
            profit,
        )
    }

    @Test
    fun `what comes back to stock does not need replacing`() {
        // Se vendieron 3 por 9000 con reposición 6000, y vuelve 1 al stock: se reembolsan 3000.
        val profit = ReplacementProfitRules.profit(
            Money(6_000),
            ReplacementTotals(Money(6_000), 2, listOf(returned(6_000, 3_000, 0, 1_000))),
        )

        assertEquals(
            Checked.Value(ReplacementProfit(Money(6_000), Money(4_000), Money(2_000), 2)),
            profit,
        )
    }

    @Test
    fun `returning a line one unit at a time adds up to its whole replacement`() {
        val shares = listOf(0L to 1_000L, 1_000L to 1_000L, 2_000L to 1_000L).map { (before, now) ->
            ReplacementProfitRules.returnedShare(returned(10_000, 3_000, before, now))
        }

        assertEquals(
            listOf(Money(3_333), Money(3_334), Money(3_333)).map { Checked.Value(it) },
            shares,
        )
    }

    @Test
    fun `a line without quantity shares nothing`() {
        assertEquals(
            Checked.Value(Money.ZERO),
            ReplacementProfitRules.returnedShare(returned(10_000, 0, 0, 1_000)),
        )
    }

    @Test
    fun `absurd sums give no value instead of failing`() {
        assertEquals(
            Checked.Overflow,
            ReplacementProfitRules.returnedShare(returned(Long.MAX_VALUE, 1, 0, 2)),
        )
        assertEquals(
            Checked.Overflow,
            ReplacementProfitRules.returnedShare(returned(1, 1, Long.MAX_VALUE, 1)),
        )
        assertEquals(
            Checked.Overflow,
            ReplacementProfitRules.profit(
                Money.ZERO,
                ReplacementTotals(Money.ZERO, 0, listOf(returned(Long.MAX_VALUE, 1, 0, 2))),
            ),
        )
        assertEquals(
            Checked.Overflow,
            ReplacementProfitRules.profit(
                Money(Long.MIN_VALUE),
                ReplacementTotals(Money(1), 0, emptyList()),
            ),
        )
    }

    @Test
    fun `the replacement value counts only positive stock`() {
        val value = ReplacementProfitRules.replacementValue(
            listOf(
                QuantityMilli(2_500) to Money(1_000),
                QuantityMilli(0) to Money(5_000),
                QuantityMilli(-1_000) to Money(5_000),
                QuantityMilli(3_000) to Money.ZERO,
            ),
        )

        assertEquals(Checked.Value(Money(2_500)), value)
    }

    @Test
    fun `an absurd replacement value gives no value`() {
        assertEquals(
            Checked.Overflow,
            ReplacementProfitRules.replacementValue(
                listOf(QuantityMilli(Long.MAX_VALUE) to Money(Long.MAX_VALUE)),
            ),
        )
        assertEquals(
            Checked.Overflow,
            ReplacementProfitRules.replacementValue(
                listOf(
                    QuantityMilli(1_000) to Money(Long.MAX_VALUE),
                    QuantityMilli(1_000) to Money(1),
                ),
            ),
        )
    }
}
