package io.github.yesidrobayo.shopledger.domain.rule.report

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.report.LimitLevel
import io.github.yesidrobayo.shopledger.domain.model.report.LimitNoticeMark
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El límite de ingresos que escribe la persona: la ventana de meses, cuánto se usó, el nivel de
 * aviso y cuándo avisar, con el ejemplo de la regla. Montos inventados.
 */
class RevenueLimitRulesTest {
    private fun date(y: Int, m: Int, d: Int) = BusinessDate(LocalDate(y, m, d))

    @Test
    fun `twelve months in November 2026 go from December 2025 to the end of November`() {
        assertEquals(
            ReportPeriod.Range(date(2025, 12, 1), date(2026, 11, 30)),
            RevenueLimitRules.window(YearMonth(2026, 11), 12),
        )
    }

    @Test
    fun `one month is only the month in progress, with its real length`() {
        assertEquals(
            ReportPeriod.Range(date(2028, 2, 1), date(2028, 2, 29)),
            RevenueLimitRules.window(YearMonth(2028, 2), 1),
        )
    }

    @Test
    fun `a window without months is a programming error`() {
        assertThrows(IllegalArgumentException::class.java) {
            RevenueLimitRules.window(YearMonth(2026, 11), 0)
        }
    }

    @Test
    fun `8200000 against 10000000 is 8200 basis points and the first notice`() {
        val used = RevenueLimitRules.used(Money(8_200_000), Money(10_000_000))

        assertEquals(BasisPoints(8_200), used)
        assertEquals(LimitLevel.FIRST_NOTICE, RevenueLimitRules.level(used))
    }

    @Test
    fun `used has no value without the sum, with a limit that is not positive or on overflow`() {
        assertNull(RevenueLimitRules.used(null, Money(10_000_000)))
        assertNull(RevenueLimitRules.used(Money(5), Money.ZERO))
        assertNull(RevenueLimitRules.used(Money(9_000_000_000_000_000L), Money(1)))
    }

    @Test
    fun `each level starts at its own threshold`() {
        assertEquals(LimitLevel.BELOW, RevenueLimitRules.level(null))
        assertEquals(LimitLevel.BELOW, RevenueLimitRules.level(BasisPoints(7_999)))
        assertEquals(LimitLevel.FIRST_NOTICE, RevenueLimitRules.level(BasisPoints(8_000)))
        assertEquals(LimitLevel.FIRST_NOTICE, RevenueLimitRules.level(BasisPoints(9_499)))
        assertEquals(LimitLevel.SECOND_NOTICE, RevenueLimitRules.level(BasisPoints(9_500)))
        assertEquals(LimitLevel.SECOND_NOTICE, RevenueLimitRules.level(BasisPoints(10_000)))
        assertEquals(LimitLevel.EXCEEDED, RevenueLimitRules.level(BasisPoints(10_001)))
    }

    @Test
    fun `each level notifies once per window and a lower one never does`() {
        val start = date(2025, 12, 1)
        fun mark(level: LimitLevel, start: BusinessDate = date(2025, 12, 1)) =
            LimitNoticeMark(start, Money(10_000_000), 12, level)

        assertFalse(RevenueLimitRules.shouldNotify(mark(LimitLevel.BELOW), null))
        assertTrue(RevenueLimitRules.shouldNotify(mark(LimitLevel.FIRST_NOTICE), null))
        assertFalse(
            RevenueLimitRules.shouldNotify(
                mark(LimitLevel.FIRST_NOTICE),
                mark(LimitLevel.FIRST_NOTICE),
            ),
        )
        assertTrue(
            RevenueLimitRules.shouldNotify(
                mark(LimitLevel.SECOND_NOTICE),
                mark(LimitLevel.FIRST_NOTICE),
            ),
        )
        assertFalse(
            RevenueLimitRules.shouldNotify(
                mark(LimitLevel.FIRST_NOTICE),
                mark(LimitLevel.EXCEEDED),
            ),
        )
        // Otra ventana empieza de cero.
        assertTrue(
            RevenueLimitRules.shouldNotify(
                mark(LimitLevel.FIRST_NOTICE, date(2026, 1, 1)),
                mark(LimitLevel.EXCEEDED, start),
            ),
        )
    }

    @Test
    fun `another amount or another number of months is another window`() {
        val last = LimitNoticeMark(date(2025, 12, 1), Money(10_000_000), 12, LimitLevel.EXCEEDED)

        assertTrue(
            RevenueLimitRules.shouldNotify(
                last.copy(amount = Money(12_000_000), level = LimitLevel.FIRST_NOTICE),
                last,
            ),
        )
        assertTrue(
            RevenueLimitRules.shouldNotify(
                last.copy(months = 6, level = LimitLevel.FIRST_NOTICE),
                last,
            ),
        )
    }

    @Test
    fun `only an amount above zero and within the ceiling is valid`() {
        assertFalse(RevenueLimitRules.isValidAmount(Money.ZERO))
        assertTrue(RevenueLimitRules.isValidAmount(Money(1)))
        assertTrue(RevenueLimitRules.isValidAmount(RevenueLimitRules.MAX_AMOUNT))
        assertFalse(RevenueLimitRules.isValidAmount(Money(9_000_000_000_000_001L)))
    }

    @Test
    fun `months go from 1 to 60`() {
        assertFalse(RevenueLimitRules.isValidMonths(0))
        assertTrue(RevenueLimitRules.isValidMonths(1))
        assertTrue(RevenueLimitRules.isValidMonths(60))
        assertFalse(RevenueLimitRules.isValidMonths(61))
    }
}
