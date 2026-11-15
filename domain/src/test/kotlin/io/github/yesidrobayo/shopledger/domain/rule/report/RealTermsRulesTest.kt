package io.github.yesidrobayo.shopledger.domain.rule.report

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.report.RealValue
import kotlinx.datetime.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** La comparación en términos reales, con la tabla de la regla escrita a mano. */
class RealTermsRulesTest {
    private val base = YearMonth(2026, 1)
    private val february = YearMonth(2026, 2)
    private val march = YearMonth(2026, 3)

    @Test
    fun `one month of 3 percent turns 1100000 into 1067961 and 1000 into 680 basis points`() {
        assertEquals(
            RealValue.Estimated(Money(1_067_961), BasisPoints(680)),
            compare(1_100_000, february, mapOf(february to 300L)),
        )
        assertEquals(
            BasisPoints(1_000),
            PeriodComparison.variation(Money(1_100_000), Money(1_000_000)),
        )
    }

    @Test
    fun `two months are applied in order`() {
        // 1200000 / 1,03 = 1165049, y / 1,04 = 1120239: 1202 bp frente a 2000 nominales.
        assertEquals(
            RealValue.Estimated(Money(1_120_239), BasisPoints(1_202)),
            compare(1_200_000, march, mapOf(february to 300L, march to 400L)),
        )
    }

    @Test
    fun `selling 30 percent more with 33 percent of rise is selling less`() {
        assertEquals(
            RealValue.Estimated(Money(977_444), BasisPoints(-226)),
            compare(1_300_000, february, mapOf(february to 3_300L)),
        )
    }

    @Test
    fun `a missing or unusable month is named and nothing is supposed`() {
        assertEquals(
            RealValue.MissingMonths(listOf(march)),
            compare(1_200_000, march, mapOf(february to 300L)),
        )
        // −100 % dejaría los precios en cero: no sirve para dividir.
        assertEquals(
            RealValue.MissingMonths(listOf(february)),
            compare(1_100_000, february, mapOf(february to -10_000L)),
        )
    }

    @Test
    fun `the same month needs no index and a zero base has no variation`() {
        assertEquals(
            RealValue.Estimated(Money(500), null),
            RealTermsRules.compare(Money.ZERO, Money(500), base, base, emptyMap()),
        )
    }

    @Test
    fun `an absurd figure gives no value instead of a wrong one`() {
        // −99,99 % multiplica por diez mil.
        assertEquals(
            RealValue.OutOfRange,
            compare(Long.MAX_VALUE / 2, february, mapOf(february to -9_999L)),
        )
    }

    @Test
    fun `the change goes above minus 100 percent and up to the top`() {
        assertFalse(RealTermsRules.isValidChange(BasisPoints(-10_000)))
        assertTrue(RealTermsRules.isValidChange(BasisPoints(-9_999)))
        assertTrue(RealTermsRules.isValidChange(BasisPoints(1_000_000)))
        assertFalse(RealTermsRules.isValidChange(BasisPoints(1_000_001)))
    }

    @Test
    fun `missing months are only those between the first and the last`() {
        assertEquals(emptyList<YearMonth>(), RealTermsRules.missingBetween(emptyList()))
        assertEquals(
            listOf(YearMonth(2026, 9), YearMonth(2026, 10)),
            RealTermsRules.missingBetween(
                listOf(YearMonth(2026, 11), YearMonth(2026, 8), YearMonth(2026, 7)),
            ),
        )
        // Cruza el año.
        assertEquals(
            listOf(YearMonth(2026, 1)),
            RealTermsRules.missingBetween(listOf(YearMonth(2025, 12), YearMonth(2026, 2))),
        )
    }

    private fun compare(nominal: Long, month: YearMonth, index: Map<YearMonth, Long>) =
        RealTermsRules.compare(
            baseValue = Money(1_000_000),
            nominal = Money(nominal),
            base = base,
            month = month,
            index = index.mapValues { BasisPoints(it.value) },
        )
}
