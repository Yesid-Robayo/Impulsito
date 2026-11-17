package io.github.yesidrobayo.shopledger.domain.rule.report

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Checked
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.ProfitTotals
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.report.DayExtremes
import io.github.yesidrobayo.shopledger.domain.model.report.DayPoint
import io.github.yesidrobayo.shopledger.domain.model.report.DayTotals
import io.github.yesidrobayo.shopledger.domain.model.report.PeriodTotals
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Test

/** La serie por día de la gráfica, con valores escritos a mano. */
class DailySeriesTest {
    @Test
    fun `a week has seven points and days without sales are zero`() {
        val week = ReportPeriod.Week(date("2026-11-02"))
        val days = listOf(
            day("2026-11-03", net = 10_000, cost = 6_000),
            // Un día con solo un gasto: ventas y ganancia en ventas valen cero.
            DayTotals(date("2026-11-06"), totals(expenses = 7_000)),
        )

        assertEquals(
            listOf(
                DayPoint(date("2026-11-02"), Money.ZERO, Money.ZERO),
                DayPoint(date("2026-11-03"), Money(10_000), Money(4_000)),
                DayPoint(date("2026-11-04"), Money.ZERO, Money.ZERO),
                DayPoint(date("2026-11-05"), Money.ZERO, Money.ZERO),
                DayPoint(date("2026-11-06"), Money.ZERO, Money.ZERO),
                DayPoint(date("2026-11-07"), Money.ZERO, Money.ZERO),
                DayPoint(date("2026-11-08"), Money.ZERO, Money.ZERO),
            ),
            seriesOf(week, days),
        )
    }

    @Test
    fun `a day with more returns than sales goes below zero`() {
        // Ventas de 3.000 que costaron 2.000, y una devolución de 5.000 que devolvió 3.500.
        val points =
            seriesOf(
                ReportPeriod.Day(date("2026-11-05")),
                listOf(
                    DayTotals(
                        date("2026-11-05"),
                        totals(net = 3_000, cost = 2_000, refund = 5_000, costReturned = 3_500),
                    ),
                ),
            )

        assertEquals(listOf(DayPoint(date("2026-11-05"), Money(-2_000), Money(-500))), points)
    }

    @Test
    fun `february of 2027 has twenty eight points`() {
        val points = seriesOf(ReportPeriod.Month(YearMonth(2027, 2)), emptyList())

        assertEquals(28, points.size)
        assertEquals(date("2027-02-01"), points.first().date)
        assertEquals(date("2027-02-28"), points.last().date)
    }

    @Test
    fun `a day outside the period does not enter`() {
        val points =
            seriesOf(ReportPeriod.Day(date("2026-11-05")), listOf(day("2026-11-06", 99, 0)))

        assertEquals(listOf(DayPoint(date("2026-11-05"), Money.ZERO, Money.ZERO)), points)
    }

    @Test
    fun `a sum that does not fit is an overflow`() {
        val days =
            listOf(DayTotals(date("2026-11-05"), totals(net = Long.MIN_VALUE, refund = 1)))

        assertEquals(Checked.Overflow, DailySeries.of(ReportPeriod.Day(date("2026-11-05")), days))
    }

    @Test
    fun `a day after today is never the lowest or the highest`() {
        // Semana del lunes 2026-11-02, vista el jueves 5: del viernes al domingo todavía no pasan.
        val points = week(10_000, 4_000, 7_000, 6_000, 0, 0, 0)

        assertEquals(
            DayExtremes(
                highest = point("2026-11-02", 10_000),
                lowest = point("2026-11-03", 4_000),
            ),
            DailySeries.extremesOf(points, date("2026-11-05"), DayPoint::netSales),
        )
    }

    @Test
    fun `today is a candidate and a later day with records does not win`() {
        // Con el reloj del teléfono adelantado quedó una venta el sábado 7, después de hoy.
        val points = week(10_000, 4_000, 7_000, 1_000, 0, 50_000, 0)

        assertEquals(
            DayExtremes(
                highest = point("2026-11-02", 10_000),
                lowest = point("2026-11-05", 1_000),
            ),
            DailySeries.extremesOf(points, date("2026-11-05"), DayPoint::netSales),
        )
    }

    @Test
    fun `a past day without sales is still the lowest`() {
        val points = week(10_000, 0, 7_000, 6_000, 0, 0, 0)

        assertEquals(
            DayExtremes(
                highest = point("2026-11-02", 10_000),
                lowest = point("2026-11-03", 0),
            ),
            DailySeries.extremesOf(points, date("2026-11-05"), DayPoint::netSales),
        )
    }

    @Test
    fun `a period that has not started yet has no lowest nor highest day`() {
        val points = week(0, 0, 3_000, 0, 0, 0, 0)

        assertEquals(null, DailySeries.extremesOf(points, date("2026-11-01"), DayPoint::netSales))
        assertEquals(
            null,
            DailySeries.extremesOf(emptyList(), date("2026-11-05"), DayPoint::netSales),
        )
    }

    @Test
    fun `a period already over uses every day`() {
        val points = week(10_000, 4_000, 7_000, 6_000, 2_000, 12_000, 3_000)

        assertEquals(
            DayExtremes(
                highest = point("2026-11-07", 12_000),
                lowest = point("2026-11-06", 2_000),
            ),
            DailySeries.extremesOf(points, date("2026-11-20"), DayPoint::netSales),
        )
    }

    /** La semana del lunes 2026-11-02 con lo vendido de cada día, sin ganancia. */
    private fun week(vararg sales: Long): List<DayPoint> = sales.mapIndexed { index, net ->
        DayPoint(date("2026-11-02").plusDays(index), Money(net), Money.ZERO)
    }

    private fun point(text: String, net: Long) = DayPoint(date(text), Money(net), Money.ZERO)

    private fun seriesOf(period: ReportPeriod, days: List<DayTotals>): List<DayPoint> =
        (DailySeries.of(period, days) as Checked.Value).value

    private fun day(text: String, net: Long, cost: Long) =
        DayTotals(date(text), totals(net = net, cost = cost))

    private fun totals(
        net: Long = 0,
        cost: Long = 0,
        refund: Long = 0,
        costReturned: Long = 0,
        expenses: Long = 0,
    ) = PeriodTotals(
        ProfitTotals(
            salesNetTotal = Money(net),
            returnsNetRefund = Money(refund),
            salesTotalCost = Money(cost),
            returnsCostReturned = Money(costReturned),
            recordedExpenses = Money(expenses),
            netShrinkage = Money.ZERO,
            paymentFees = Money.ZERO,
            cashRoundingTotal = Money.ZERO,
        ),
        salesCount = if (net != 0L) 1 else 0,
    )

    private fun date(text: String) = BusinessDate(LocalDate.parse(text))
}
