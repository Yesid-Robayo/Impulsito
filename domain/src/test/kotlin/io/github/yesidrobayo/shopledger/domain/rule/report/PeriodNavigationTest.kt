package io.github.yesidrobayo.shopledger.domain.rule.report

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.report.PeriodKind
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendar
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cómo se eligen y recorren los períodos de las estadísticas, con fechas escritas a mano. */
class PeriodNavigationTest {
    private val zone = requireNotNull(BusinessTimeZone.of("Etc/GMT+5"))
    private val mondays = BusinessCalendar(zone, DayOfWeek.MONDAY)
    private val sundays = BusinessCalendar(zone, DayOfWeek.SUNDAY)

    @Test
    fun `the period of each kind that contains a date follows the week of the business`() {
        val sunday = date("2026-11-01")

        assertEquals(
            ReportPeriod.Day(sunday),
            PeriodNavigation.containing(PeriodKind.DAY, sunday, mondays),
        )
        assertEquals(
            ReportPeriod.Week(date("2026-10-26")),
            PeriodNavigation.containing(PeriodKind.WEEK, sunday, mondays),
        )
        assertEquals(
            ReportPeriod.Week(sunday),
            PeriodNavigation.containing(PeriodKind.WEEK, sunday, sundays),
        )
        assertEquals(
            ReportPeriod.Month(YearMonth(2026, 11)),
            PeriodNavigation.containing(PeriodKind.MONTH, sunday, mondays),
        )
    }

    @Test
    fun `each period knows its kind`() {
        assertEquals(PeriodKind.DAY, PeriodNavigation.kindOf(ReportPeriod.Day(date("2026-11-01"))))
        assertEquals(
            PeriodKind.WEEK,
            PeriodNavigation.kindOf(ReportPeriod.Week(date("2026-11-01"))),
        )
        assertEquals(
            PeriodKind.MONTH,
            PeriodNavigation.kindOf(ReportPeriod.Month(YearMonth(2026, 11))),
        )
    }

    @Test
    fun `previous and next move by one day, seven days or one month`() {
        assertEquals(
            ReportPeriod.Day(date("2026-02-28")),
            PeriodNavigation.previous(ReportPeriod.Day(date("2026-03-01"))),
        )
        assertEquals(
            ReportPeriod.Day(date("2027-01-01")),
            PeriodNavigation.next(ReportPeriod.Day(date("2026-12-31"))),
        )
        assertEquals(
            ReportPeriod.Week(date("2026-12-28")),
            PeriodNavigation.previous(ReportPeriod.Week(date("2027-01-04"))),
        )
        assertEquals(
            ReportPeriod.Week(date("2027-01-04")),
            PeriodNavigation.next(ReportPeriod.Week(date("2026-12-28"))),
        )
        assertEquals(
            ReportPeriod.Month(YearMonth(2026, 12)),
            PeriodNavigation.previous(ReportPeriod.Month(YearMonth(2027, 1))),
        )
        assertEquals(
            ReportPeriod.Month(YearMonth(2027, 2)),
            PeriodNavigation.next(ReportPeriod.Month(YearMonth(2027, 1))),
        )
    }

    @Test
    fun `the month after january of 2027 is february with 28 days`() {
        val february = PeriodNavigation.next(ReportPeriod.Month(YearMonth(2027, 1)))

        assertEquals(date("2027-02-01"), february.start)
        assertEquals(date("2027-02-28"), february.end)
    }

    @Test
    fun `the forward limit is today unless there is data after today`() {
        val today = date("2026-11-04")

        assertEquals(today, PeriodNavigation.forwardLimit(today, null))
        assertEquals(today, PeriodNavigation.forwardLimit(today, date("2026-11-01")))
        assertEquals(today, PeriodNavigation.forwardLimit(today, today))
        assertEquals(date("2026-12-15"), PeriodNavigation.forwardLimit(today, date("2026-12-15")))
    }

    @Test
    fun `the current period is the last one`() {
        val today = date("2026-11-04")
        val month = ReportPeriod.Month(YearMonth(2026, 11))

        assertFalse(PeriodNavigation.hasNext(month, today))
        assertTrue(PeriodNavigation.hasNext(ReportPeriod.Month(YearMonth(2026, 10)), today))
        assertFalse(PeriodNavigation.hasNext(ReportPeriod.Day(today), today))
        assertTrue(PeriodNavigation.hasNext(ReportPeriod.Day(date("2026-11-03")), today))
        assertFalse(PeriodNavigation.hasNext(ReportPeriod.Week(date("2026-11-02")), today))
    }

    @Test
    fun `data dated after today can still be reached`() {
        // Un reloj adelantado dejó una venta el 2026-12-15: se llega hasta diciembre y no más.
        val limit = PeriodNavigation.forwardLimit(date("2026-11-04"), date("2026-12-15"))

        assertTrue(PeriodNavigation.hasNext(ReportPeriod.Month(YearMonth(2026, 11)), limit))
        assertFalse(PeriodNavigation.hasNext(ReportPeriod.Month(YearMonth(2026, 12)), limit))
    }

    @Test
    fun `there is no limit backwards until the first date the database can store`() {
        // Tres años atrás se sigue pudiendo retroceder.
        assertTrue(PeriodNavigation.hasPrevious(ReportPeriod.Month(YearMonth(2023, 11))))
        assertTrue(PeriodNavigation.hasPrevious(ReportPeriod.Day(date("0000-01-02"))))
        assertFalse(PeriodNavigation.hasPrevious(ReportPeriod.Day(PeriodNavigation.EARLIEST_DATE)))
        assertFalse(PeriodNavigation.hasPrevious(ReportPeriod.Month(YearMonth(0, 1))))
    }

    @Test
    fun `the current period is the one that contains today`() {
        val today = date("2026-11-04")

        assertTrue(PeriodNavigation.isCurrent(ReportPeriod.Week(date("2026-11-02")), today))
        assertFalse(PeriodNavigation.isCurrent(ReportPeriod.Week(date("2026-10-26")), today))
    }

    private fun date(text: String) = BusinessDate(LocalDate.parse(text))
}
