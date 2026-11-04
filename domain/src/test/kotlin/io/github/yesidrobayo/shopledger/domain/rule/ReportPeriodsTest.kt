package io.github.yesidrobayo.shopledger.domain.rule

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.testing.ForeignSystemDefaults
import io.github.yesidrobayo.shopledger.domain.testing.ForeignSystemDefaultsRule
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import kotlinx.datetime.daysUntil
import kotlinx.datetime.plus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Día, semana y mes de reporte, con el inicio de semana y la zona del negocio por parámetro. Cada
 * prueba corre con zonas e idiomas por defecto de la JVM que cambiarían el resultado si la regla
 * los leyera.
 */
@RunWith(Parameterized::class)
class ReportPeriodsTest(defaults: ForeignSystemDefaults) {
    @get:Rule
    val foreignDefaults = ForeignSystemDefaultsRule(defaults)

    @Test
    fun `day, week from monday and month of the documented dates`() {
        val sunday = date("2026-11-01")
        assertEquals(ReportPeriod.Day(sunday), ReportPeriods.day(sunday))
        assertWeek("2026-10-26", "2026-11-01", ReportPeriods.week(sunday, DayOfWeek.MONDAY))
        assertEquals("2026-11", ReportPeriods.month(sunday).yearMonth.toString())

        val lastMonday = date("2026-11-30")
        assertWeek("2026-11-30", "2026-12-06", ReportPeriods.week(lastMonday, DayOfWeek.MONDAY))
        assertEquals("2026-11", ReportPeriods.month(lastMonday).yearMonth.toString())

        val firstSunday = date("2027-01-03")
        assertWeek("2026-12-28", "2027-01-03", ReportPeriods.week(firstSunday, DayOfWeek.MONDAY))
        assertEquals("2027-01", ReportPeriods.month(firstSunday).yearMonth.toString())
    }

    @Test
    fun `the week of a sunday starts on the configured day`() {
        val sunday = date("2026-11-01")

        assertEquals(date("2026-10-26"), ReportPeriods.weekStart(sunday, DayOfWeek.MONDAY))
        assertEquals(date("2026-11-01"), ReportPeriods.weekStart(sunday, DayOfWeek.SUNDAY))
        assertEquals(date("2026-10-31"), ReportPeriods.weekStart(sunday, DayOfWeek.SATURDAY))
    }

    @Test
    fun `the week of a wednesday with monday, saturday and sunday as first day`() {
        val wednesday = date("2026-11-04")

        assertWeek("2026-11-02", "2026-11-08", ReportPeriods.week(wednesday, DayOfWeek.MONDAY))
        assertWeek("2026-10-31", "2026-11-06", ReportPeriods.week(wednesday, DayOfWeek.SATURDAY))
        assertWeek("2026-11-01", "2026-11-07", ReportPeriods.week(wednesday, DayOfWeek.SUNDAY))
    }

    @Test
    fun `a week can split two years and is identified by its start`() {
        val week = ReportPeriods.week(date("2026-12-31"), DayOfWeek.MONDAY)

        assertWeek("2026-12-28", "2027-01-03", week)
        assertEquals(ReportPeriod.Week(date("2026-12-28")), week)
        assertEquals(week, ReportPeriods.week(date("2027-01-03"), DayOfWeek.MONDAY))
    }

    @Test
    fun `a day period starts and ends on its own date`() {
        val day = ReportPeriods.day(date("2026-11-30"))

        assertEquals(date("2026-11-30"), day.start)
        assertEquals(date("2026-11-30"), day.end)
        assertEquals(date("2026-11-30"), day.date)
    }

    @Test
    fun `a month runs from day one to its real last day`() {
        assertMonth("2026-11", "2026-11-01", "2026-11-30", ReportPeriods.month(date("2026-11-17")))
        assertMonth("2027-01", "2027-01-01", "2027-01-31", ReportPeriods.month(date("2027-01-31")))
        assertMonth("2027-02", "2027-02-01", "2027-02-28", ReportPeriods.month(date("2027-02-01")))
        assertMonth("2028-02", "2028-02-01", "2028-02-29", ReportPeriods.month(date("2028-02-29")))
        assertEquals(
            ReportPeriod.Month(YearMonth(2026, 4)),
            ReportPeriods.month(date("2026-04-30")),
        )
    }

    @Test
    fun `a period contains both ends and nothing outside them`() {
        val week = ReportPeriods.week(date("2026-11-04"), DayOfWeek.MONDAY)

        assertFalse(date("2026-11-01") in week)
        assertTrue(date("2026-11-02") in week)
        assertTrue(date("2026-11-05") in week)
        assertTrue(date("2026-11-08") in week)
        assertFalse(date("2026-11-09") in week)
    }

    @Test
    fun `every date of three years lands in a week that starts on the configured day`() {
        // La misma comprobación que se pide a la consulta de semanas: los siete inicios posibles,
        // en todas las fechas, incluido un año bisiesto.
        val first = LocalDate.parse("2026-01-01")
        val last = LocalDate.parse("2028-12-31")
        for (weekStartDay in DayOfWeek.entries) {
            var day = first
            while (day <= last) {
                val date = BusinessDate(day)
                val week = ReportPeriods.week(date, weekStartDay)
                val daysBack = week.start.date.daysUntil(day)

                assertEquals("$day, $weekStartDay", weekStartDay, week.start.date.dayOfWeek)
                assertTrue("$day, $weekStartDay", daysBack in 0..6)
                assertEquals("$day, $weekStartDay", 6, week.start.date.daysUntil(week.end.date))
                assertTrue("$day, $weekStartDay", date in week)
                assertTrue("$day", date in ReportPeriods.month(date))
                assertTrue("$day", date in ReportPeriods.day(date))
                day = day.plus(1, kotlinx.datetime.DateTimeUnit.DAY)
            }
        }
    }

    @Test
    fun `a period ends when the next business day starts in the business zone`() {
        val behind = zone("Etc/GMT+5")
        val day = ReportPeriods.day(date("2026-11-01"))
        assertInstants(1_793_509_200_000, 1_793_595_600_000, day, behind)

        val week = ReportPeriods.week(date("2026-11-01"), DayOfWeek.MONDAY)
        assertInstants(1_792_990_800_000, 1_793_595_600_000, week, behind)

        val month = ReportPeriods.month(date("2026-11-15"))
        assertInstants(1_793_471_400_000, 1_796_063_400_000, month, zone("Asia/Kolkata"))
    }

    @Test
    fun `days of 25 and 23 hours when daylight saving time ends and starts`() {
        val pacific = zone("America/Los_Angeles")

        val longDay = ReportPeriods.day(date("2026-11-01"))
        assertInstants(1_793_516_400_000, 1_793_606_400_000, longDay, pacific)
        assertEquals(25.hours, span(longDay, pacific))

        val shortDay = ReportPeriods.day(date("2026-03-08"))
        assertInstants(1_772_956_800_000, 1_773_039_600_000, shortDay, pacific)
        assertEquals(23.hours, span(shortDay, pacific))
    }

    @Test
    fun `a day without midnight starts at the first hour that exists`() {
        // En esa zona, el horario de verano de 2018 empezó saltando de las 00:00 a la 01:00.
        val zone = zone("America/Sao_Paulo")
        val dayBefore = ReportPeriods.day(date("2018-11-03"))
        val dayWithoutMidnight = ReportPeriods.day(date("2018-11-04"))

        assertInstants(1_541_214_000_000, 1_541_300_400_000, dayBefore, zone)
        assertInstants(1_541_300_400_000, 1_541_383_200_000, dayWithoutMidnight, zone)
        assertEquals(23.hours, span(dayWithoutMidnight, zone))
    }

    @Test
    fun `the instants of a period agree with the business date of each record`() {
        val zones =
            listOf(
                "Etc/GMT+5",
                "America/Los_Angeles",
                "America/St_Johns",
                "Asia/Kolkata",
                "Pacific/Auckland",
                "America/Sao_Paulo",
            ).map(::zone)
        val first = LocalDate.parse("2018-01-01")
        val last = LocalDate.parse("2018-12-31")
        for (zone in zones) {
            var day = first
            while (day <= last) {
                val period = ReportPeriods.day(BusinessDate(day))
                val start = ReportPeriods.startInstant(period, zone)
                val end = ReportPeriods.endInstantExclusive(period, zone)

                assertEquals("$day $zone", period.start, BusinessDate.of(start, zone))
                assertEquals("$day $zone", period.end, BusinessDate.of(end - 1.milliseconds, zone))
                assertEquals("$day $zone", period.end.plusDays(1), BusinessDate.of(end, zone))
                day = day.plus(1, kotlinx.datetime.DateTimeUnit.DAY)
            }
        }
    }

    private fun assertWeek(start: String, end: String, week: ReportPeriod.Week) {
        assertEquals(date(start), week.start)
        assertEquals(date(end), week.end)
    }

    private fun assertMonth(id: String, start: String, end: String, month: ReportPeriod.Month) {
        assertEquals(id, month.yearMonth.toString())
        assertEquals(date(start), month.start)
        assertEquals(date(end), month.end)
    }

    private fun assertInstants(
        startMillis: Long,
        endMillis: Long,
        period: ReportPeriod,
        zone: BusinessTimeZone,
    ) {
        assertEquals(
            Instant.fromEpochMilliseconds(startMillis),
            ReportPeriods.startInstant(period, zone),
        )
        assertEquals(
            Instant.fromEpochMilliseconds(endMillis),
            ReportPeriods.endInstantExclusive(period, zone),
        )
    }

    private fun span(period: ReportPeriod, zone: BusinessTimeZone) =
        ReportPeriods.endInstantExclusive(period, zone) - ReportPeriods.startInstant(period, zone)

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun defaults(): List<ForeignSystemDefaults> = ForeignSystemDefaults.ALL

        /** Fecha escrita a mano, leída con la biblioteca de fechas, no con el código probado. */
        fun date(text: String): BusinessDate = BusinessDate(LocalDate.parse(text))

        fun zone(id: String): BusinessTimeZone = requireNotNull(BusinessTimeZone.of(id)) { id }
    }
}
