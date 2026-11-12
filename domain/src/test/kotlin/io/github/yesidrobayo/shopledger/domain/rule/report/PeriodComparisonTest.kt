package io.github.yesidrobayo.shopledger.domain.rule.report

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Checked
import io.github.yesidrobayo.shopledger.domain.model.Margin
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.ProfitTotals
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.report.PeriodMetrics
import io.github.yesidrobayo.shopledger.domain.model.report.PeriodTotals
import io.github.yesidrobayo.shopledger.domain.model.report.PeriodVariation
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** Con qué se compara cada período y cuánto cambió, con los ejemplos de las reglas a mano. */
class PeriodComparisonTest {
    @Test
    fun `a day is compared with the same weekday of the week before`() {
        assertEquals(
            ReportPeriod.Day(date("2026-10-28")),
            PeriodComparison.comparableOf(ReportPeriod.Day(date("2026-11-04")), date("2026-11-04")),
        )
    }

    @Test
    fun `the current week is compared with the same days of the week before`() {
        // Miércoles 2026-11-04, semanas desde el lunes: del 26 al 28 de octubre.
        assertEquals(
            ReportPeriod.Range(date("2026-10-26"), date("2026-10-28")),
            PeriodComparison.comparableOf(
                ReportPeriod.Week(date("2026-11-02")),
                date("2026-11-04"),
            ),
        )
    }

    @Test
    fun `a closed week is compared with the whole week before`() {
        assertEquals(
            ReportPeriod.Week(date("2026-10-19")),
            PeriodComparison.comparableOf(
                ReportPeriod.Week(date("2026-10-26")),
                date("2026-11-04"),
            ),
        )
    }

    @Test
    fun `the current month stops at the last day of a shorter month before`() {
        // El 2027-03-31 se compara con todo febrero, del 1 al 28.
        assertEquals(
            ReportPeriod.Range(date("2027-02-01"), date("2027-02-28")),
            PeriodComparison.comparableOf(
                ReportPeriod.Month(YearMonth(2027, 3)),
                date("2027-03-31"),
            ),
        )
        // El 2026-11-14 se compara con el 1 al 14 de octubre.
        assertEquals(
            ReportPeriod.Range(date("2026-10-01"), date("2026-10-14")),
            PeriodComparison.comparableOf(
                ReportPeriod.Month(YearMonth(2026, 11)),
                date("2026-11-14"),
            ),
        )
    }

    @Test
    fun `a closed month is compared with the whole month before`() {
        assertEquals(
            ReportPeriod.Month(YearMonth(2026, 12)),
            PeriodComparison.comparableOf(
                ReportPeriod.Month(YearMonth(2027, 1)),
                date("2027-03-31"),
            ),
        )
    }

    @Test
    fun `a range of its own is not compared`() {
        val range = ReportPeriod.Range(date("2026-01-01"), date("2026-03-31"))
        assertNull(PeriodComparison.comparableOf(range, date("2026-11-04")))
    }

    @Test
    fun `money changes are measured over the previous amount`() {
        // De 4.000.000 a 4.250.000: +6,25 %. De 4.000.000 a 3.900.000: −2,5 %.
        assertEquals(
            BasisPoints(625),
            PeriodComparison.variation(Money(4_250_000), Money(4_000_000)),
        )
        assertEquals(
            BasisPoints(-250),
            PeriodComparison.variation(Money(3_900_000), Money(4_000_000)),
        )
        // Exacto 3,333…: al punto básico más cercano.
        assertEquals(BasisPoints(333), PeriodComparison.variation(Money(31_000), Money(30_000)))
    }

    @Test
    fun `a previous amount of zero has no change`() {
        assertNull(PeriodComparison.variation(Money(10_000), Money.ZERO))
        assertNull(PeriodComparison.variation(Money.ZERO, Money.ZERO))
    }

    @Test
    fun `a negative previous amount is measured over its size`() {
        // De −10.000 a −5.000 la ganancia mejoró la mitad: +50 %.
        assertEquals(BasisPoints(5_000), PeriodComparison.variation(Money(-5_000), Money(-10_000)))
    }

    @Test
    fun `a change that does not fit in a long has no value`() {
        assertNull(PeriodComparison.variation(Money(Long.MAX_VALUE), Money(-1)))
        assertNull(PeriodComparison.variation(Money(Long.MAX_VALUE), Money(1)))
    }

    @Test
    fun `the margin changes in percentage points and needs both margins`() {
        // De 36,0 % a 35,8 %: −0,2 puntos, no −0,6 %.
        assertEquals(
            BasisPoints(-16),
            PeriodComparison.marginChange(
                Margin.Defined(BasisPoints(3_584)),
                Margin.Defined(BasisPoints(3_600)),
            ),
        )
        assertNull(PeriodComparison.marginChange(Margin.NoData, Margin.Defined(BasisPoints(3_600))))
        assertNull(
            PeriodComparison.marginChange(Margin.Defined(BasisPoints(3_600)), Margin.OutOfRange),
        )
    }

    @Test
    fun `the variation of a period covers every figure`() {
        val october = metrics(net = 4_250_000, cost = 2_720_000, expenses = 725_000)
        val after = metrics(net = 4_130_000, cost = 2_650_000, expenses = 725_000)

        assertEquals(
            PeriodVariation(
                // −120.000 sobre 4.250.000: −2,82 %.
                netSales = BasisPoints(-282),
                // −50.000 sobre 1.530.000: −3,27 %.
                grossProfit = BasisPoints(-327),
                // −50.000 sobre 805.000: −6,21 %.
                netProfit = BasisPoints(-621),
                grossMargin = BasisPoints(-16),
            ),
            PeriodComparison.of(after, october),
        )
    }

    @Test
    fun `a range must start before it ends`() {
        assertThrows(IllegalArgumentException::class.java) {
            ReportPeriod.Range(date("2026-11-02"), date("2026-11-01"))
        }
    }

    private fun metrics(net: Long, cost: Long, expenses: Long): PeriodMetrics {
        val totals =
            PeriodTotals(
                ProfitTotals(
                    salesNetTotal = Money(net),
                    returnsNetRefund = Money.ZERO,
                    salesTotalCost = Money(cost),
                    returnsCostReturned = Money.ZERO,
                    recordedExpenses = Money(expenses),
                    netShrinkage = Money.ZERO,
                    paymentFees = Money.ZERO,
                    cashRoundingTotal = Money.ZERO,
                ),
                salesCount = 1,
            )
        return (PeriodMetricsCalculator.of(totals) as Checked.Value).value
    }

    private fun date(text: String) = BusinessDate(LocalDate.parse(text))
}
