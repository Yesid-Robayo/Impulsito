package io.github.yesidrobayo.shopledger.domain.rule.region

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.region.RecordTime
import io.github.yesidrobayo.shopledger.domain.model.settings.RegionalSettings
import io.github.yesidrobayo.shopledger.domain.testing.FixedClock
import io.github.yesidrobayo.shopledger.domain.testing.ForeignSystemDefaults
import io.github.yesidrobayo.shopledger.domain.testing.ForeignSystemDefaultsRule
import io.github.yesidrobayo.shopledger.domain.testing.regionalSettings
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * El calendario del negocio armado desde sus ajustes regionales: la zona y el primer día de la
 * semana salen de ahí y de ningún otro lugar. Cada prueba corre con zonas e idiomas por defecto de
 * la JVM que cambiarían el resultado si el calendario leyera los del equipo.
 */
@RunWith(Parameterized::class)
class BusinessCalendarTest(defaults: ForeignSystemDefaults) {
    @get:Rule
    val foreignDefaults = ForeignSystemDefaultsRule(defaults)

    @Test
    fun `the business date of each documented instant comes from the zone in the settings`() {
        // Desfase negativo, negativo de media hora, positivo de media hora, horario de verano y
        // cambios de día, de mes y de año.
        val cases =
            listOf(
                Triple(1_793_593_800_000, "America/Los_Angeles", "2026-11-01"),
                Triple(1_793_593_800_000, "America/St_Johns", "2026-11-02"),
                Triple(1_793_593_800_000, "Asia/Kolkata", "2026-11-02"),
                Triple(1_793_557_799_000, "Asia/Kolkata", "2026-11-01"),
                Triple(1_793_557_800_000, "Asia/Kolkata", "2026-11-02"),
                Triple(1_796_095_799_000, "America/St_Johns", "2026-11-30"),
                Triple(1_796_095_800_000, "America/St_Johns", "2026-12-01"),
                Triple(1_798_774_199_000, "America/St_Johns", "2026-12-31"),
                Triple(1_798_741_800_000, "Asia/Kolkata", "2027-01-01"),
                Triple(1_793_530_799_000, "Pacific/Auckland", "2026-11-01"),
                Triple(1_793_530_800_000, "Pacific/Auckland", "2026-11-02"),
                Triple(1_793_593_800_000, "Etc/GMT+5", "2026-11-01"),
                Triple(1_793_595_600_000, "Etc/GMT+5", "2026-11-02"),
                Triple(1_793_593_800_000, "Etc/GMT-9", "2026-11-02"),
            )

        for ((epochMillis, zoneId, expected) in cases) {
            val calendar = ready(regionalSettings(zoneId, DayOfWeek.MONDAY))

            assertEquals(
                "$epochMillis en $zoneId",
                date(expected),
                calendar.businessDateOf(instant(epochMillis)),
            )
        }
    }

    @Test
    fun `the calendar keeps the zone and the first day of the week of the settings`() {
        val calendar = ready(regionalSettings("America/St_Johns", DayOfWeek.SATURDAY))

        assertEquals(BusinessTimeZone.of("America/St_Johns"), calendar.zone)
        assertEquals(DayOfWeek.SATURDAY, calendar.weekStartDay)
    }

    @Test
    fun `a record keeps the instant and the business date of the moment it was written`() {
        val clock = FixedClock.atEpochMillis(1_793_593_800_000)
        val calendar = ready(regionalSettings("Etc/GMT+5", DayOfWeek.MONDAY))

        val stamped = calendar.stamp(clock.now())

        assertEquals(RecordTime(clock.now(), date("2026-11-01")), stamped)
    }

    @Test
    fun `changing the zone only dates new records and never the ones already written`() {
        val occurredAt = instant(1_793_593_800_000)
        val before = regionalSettings("Etc/GMT+5", DayOfWeek.MONDAY)
        val written = ready(before).stamp(occurredAt)

        // La persona cambia la zona: el registro escrito es un valor guardado y no se recalcula, y
        // uno nuevo en el mismo instante cae en el día de la zona nueva.
        val after = before.copy(businessTimeZoneId = "Etc/GMT-9")
        val writtenAfterChange = ready(after).stamp(occurredAt)

        assertEquals(RecordTime(occurredAt, date("2026-11-01")), written)
        assertEquals(RecordTime(occurredAt, date("2026-11-02")), writtenAfterChange)
    }

    @Test
    fun `the week of a sunday starts on the first day read from the settings`() {
        val sunday = date("2026-11-01")

        assertEquals(weekFrom("2026-10-26"), weekOf(sunday, DayOfWeek.MONDAY))
        assertEquals(weekFrom("2026-10-31"), weekOf(sunday, DayOfWeek.SATURDAY))
        assertEquals(weekFrom("2026-11-01"), weekOf(sunday, DayOfWeek.SUNDAY))
    }

    @Test
    fun `the week of a wednesday with monday, saturday and sunday as first day`() {
        val wednesday = date("2026-11-04")

        assertWeek("2026-11-02", "2026-11-08", weekOf(wednesday, DayOfWeek.MONDAY))
        assertWeek("2026-10-31", "2026-11-06", weekOf(wednesday, DayOfWeek.SATURDAY))
        assertWeek("2026-11-01", "2026-11-07", weekOf(wednesday, DayOfWeek.SUNDAY))
    }

    @Test
    fun `with each first day a week holds seven days that include the date`() {
        val firstDays = listOf(DayOfWeek.MONDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
        var day = date("2026-12-20")
        while (day <= date("2027-01-10")) {
            for (firstDay in firstDays) {
                val week = weekOf(day, firstDay)
                assertEquals(firstDay, week.start.date.dayOfWeek)
                assertTrue("$day con $firstDay", day in week)
                assertEquals(week.start.plusDays(6), week.end)
            }
            day = day.plusDays(1)
        }
        // Una semana que parte dos años, con inicio en lunes.
        assertWeek("2026-12-28", "2027-01-03", weekOf(date("2026-12-31"), DayOfWeek.MONDAY))
    }

    @Test
    fun `changing the first day regroups the weeks and leaves the business date as it was`() {
        val before = regionalSettings("Asia/Kolkata", DayOfWeek.MONDAY)
        val written = ready(before).stamp(instant(1_793_593_800_000))

        val after = before.copy(weekStartDay = DayOfWeek.SUNDAY)

        assertWeek("2026-11-02", "2026-11-08", ready(before).week(written.businessDate))
        assertWeek("2026-11-01", "2026-11-07", ready(after).week(written.businessDate))
        assertEquals(date("2026-11-02"), written.businessDate)
    }

    @Test
    fun `a week range with a negative half hour offset and saturday as first day`() {
        val calendar = ready(regionalSettings("America/St_Johns", DayOfWeek.SATURDAY))
        val week = calendar.week(date("2026-11-04"))

        // El horario de verano termina el domingo 2026-11-01 a las 02:00: el sábado empieza a las
        // 00:00 con −02:30 y la semana termina con −03:30, una hora más larga.
        assertWeek("2026-10-31", "2026-11-06", week)
        assertEquals(Instant.parse("2026-10-31T02:30:00Z"), calendar.startInstant(week))
        assertEquals(Instant.parse("2026-11-07T03:30:00Z"), calendar.endInstantExclusive(week))
        assertRangeHoldsItsDates(calendar, week)
    }

    @Test
    fun `a week range with a negative offset and sunday as first day`() {
        val calendar = ready(regionalSettings("America/Los_Angeles", DayOfWeek.SUNDAY))
        val week = calendar.week(date("2026-11-04"))

        assertWeek("2026-11-01", "2026-11-07", week)
        assertEquals(Instant.parse("2026-11-01T07:00:00Z"), calendar.startInstant(week))
        assertEquals(Instant.parse("2026-11-08T08:00:00Z"), calendar.endInstantExclusive(week))
        assertEquals(
            7.days + 1.hours,
            calendar.endInstantExclusive(week) - calendar.startInstant(week),
        )
        assertRangeHoldsItsDates(calendar, week)
    }

    @Test
    fun `a week range with a positive half hour offset and monday as first day`() {
        val calendar = ready(regionalSettings("Asia/Kolkata", DayOfWeek.MONDAY))
        val week = calendar.week(date("2026-11-04"))

        assertWeek("2026-11-02", "2026-11-08", week)
        assertEquals(Instant.parse("2026-11-01T18:30:00Z"), calendar.startInstant(week))
        assertEquals(Instant.parse("2026-11-08T18:30:00Z"), calendar.endInstantExclusive(week))
        assertRangeHoldsItsDates(calendar, week)
    }

    @Test
    fun `a fixed negative offset week that splits two years`() {
        val calendar = ready(regionalSettings("Etc/GMT+5", DayOfWeek.MONDAY))
        val week = calendar.week(date("2026-12-31"))

        assertWeek("2026-12-28", "2027-01-03", week)
        assertEquals(Instant.parse("2026-12-28T05:00:00Z"), calendar.startInstant(week))
        assertEquals(Instant.parse("2027-01-04T05:00:00Z"), calendar.endInstantExclusive(week))
        assertRangeHoldsItsDates(calendar, week)
    }

    @Test
    fun `day and month ranges in the zone of the settings`() {
        val calendar = ready(regionalSettings("America/St_Johns", DayOfWeek.SUNDAY))
        val lastDay = date("2026-11-30")

        val day = calendar.day(lastDay)
        assertEquals(ReportPeriod.Day(lastDay), day)
        assertEquals(Instant.parse("2026-11-30T03:30:00Z"), calendar.startInstant(day))
        assertEquals(Instant.parse("2026-12-01T03:30:00Z"), calendar.endInstantExclusive(day))
        assertRangeHoldsItsDates(calendar, day)

        val month = calendar.month(lastDay)
        assertEquals(ReportPeriod.Month(YearMonth(2026, 11)), month)
        // Noviembre empieza con horario de verano y termina sin él.
        assertEquals(Instant.parse("2026-11-01T02:30:00Z"), calendar.startInstant(month))
        assertEquals(Instant.parse("2026-12-01T03:30:00Z"), calendar.endInstantExclusive(month))
        assertRangeHoldsItsDates(calendar, month)
    }

    @Test
    fun `a zone this phone does not know is reported and never replaced by another`() {
        // Ni por la zona del equipo, que en esta prueba es otra a propósito, ni por UTC.
        val unknownIds =
            listOf(
                "America/Ciudad_Inventada",
                "Mars/Olympus_Mons",
                "Not a zone",
                "",
                "Etc/" + "X".repeat(BusinessTimeZone.MAX_ID_LENGTH),
            )

        for (zoneId in unknownIds) {
            val result = BusinessCalendar.of(regionalSettings(zoneId, DayOfWeek.MONDAY))

            assertEquals(BusinessCalendarResult.UnknownZone(zoneId), result)
        }
    }

    private fun weekOf(date: BusinessDate, firstDay: DayOfWeek): ReportPeriod.Week =
        ready(regionalSettings("Etc/GMT+5", firstDay)).week(date)

    /**
     * Cada instante del rango, hora a hora y con el milisegundo de cada borde, cae en una fecha del
     * período; el instante anterior al inicio y el fin exclusivo caen fuera.
     */
    private fun assertRangeHoldsItsDates(calendar: BusinessCalendar, period: ReportPeriod) {
        val start = calendar.startInstant(period)
        val end = calendar.endInstantExclusive(period)
        assertEquals(period.start, calendar.businessDateOf(start))
        assertEquals(period.end, calendar.businessDateOf(end - 1.milliseconds))
        assertEquals(period.start.plusDays(-1), calendar.businessDateOf(start - 1.milliseconds))
        assertEquals(period.end.plusDays(1), calendar.businessDateOf(end))
        var at = start
        while (at < end) {
            assertTrue("$at en $period", calendar.businessDateOf(at) in period)
            at += 30.minutes
        }
    }

    private companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun defaults(): List<ForeignSystemDefaults> = ForeignSystemDefaults.ALL

        fun ready(regional: RegionalSettings): BusinessCalendar =
            when (val result = BusinessCalendar.of(regional)) {
                is BusinessCalendarResult.Ready -> result.calendar
                is BusinessCalendarResult.UnknownZone -> error("Zona desconocida en la prueba")
            }

        fun instant(epochMillis: Long): Instant = Instant.fromEpochMilliseconds(epochMillis)

        /** Fecha escrita a mano, leída con la biblioteca de fechas, no con el código probado. */
        fun date(text: String): BusinessDate = BusinessDate(LocalDate.parse(text))

        fun weekFrom(start: String): ReportPeriod.Week = ReportPeriod.Week(date(start))

        fun assertWeek(start: String, end: String, week: ReportPeriod.Week) {
            assertEquals(date(start), week.start)
            assertEquals(date(end), week.end)
        }
    }
}
