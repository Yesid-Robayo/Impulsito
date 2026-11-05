package io.github.yesidrobayo.shopledger.domain.matrix

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendar
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import io.github.yesidrobayo.shopledger.domain.testing.ForeignSystemDefaults
import io.github.yesidrobayo.shopledger.domain.testing.ForeignSystemDefaultsRule
import io.github.yesidrobayo.shopledger.domain.testing.RegionalMatrix
import io.github.yesidrobayo.shopledger.domain.testing.RegionalMatrixRow
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
 * La fecha de negocio, la semana y el mes con la zona y el primer día de cada fila de la matriz:
 * desfases negativos, positivos, de media hora y de tres cuartos, con y sin horario de verano.
 *
 * Corren con zonas e idiomas extraños de la JVM: si el calendario leyera los del equipo, el día
 * cambiaría.
 */
@RunWith(Parameterized::class)
class RegionalMatrixTimeTest(private val row: RegionalMatrixRow, defaults: ForeignSystemDefaults) {
    @get:Rule
    val foreignDefaults = ForeignSystemDefaultsRule(defaults)

    private val settings = row.regionalSettings()
    private val calendar: BusinessCalendar =
        when (val result = BusinessCalendar.of(settings)) {
            is BusinessCalendarResult.Ready -> result.calendar

            is BusinessCalendarResult.UnknownZone ->
                error("La JVM no conoce la zona de la fila $row")
        }

    @Test
    fun `the documented instant falls on the business date of the zone of the row`() {
        // 2026-11-02 a las 04:30 UTC: todavía es el día anterior en las zonas al oeste de −04:30.
        val expected =
            when (settings.businessTimeZoneId) {
                "America/Los_Angeles", "America/Mexico_City", "America/Bogota" -> "2026-11-01"

                "Asia/Tokyo", "Asia/Ho_Chi_Minh", "Europe/Madrid", "America/Sao_Paulo",
                "Asia/Kolkata", "America/St_Johns", "Europe/Zurich", "Asia/Kuwait",
                "America/Santiago", "America/Argentina/Buenos_Aires", "Etc/UTC",
                "Pacific/Chatham",
                -> "2026-11-02"

                else -> error("Falta en esta prueba la fecha de la zona de la fila $row")
            }
        val stamped = calendar.stamp(DOCUMENTED_INSTANT)

        assertEquals(date(expected), stamped.businessDate)
        assertEquals(DOCUMENTED_INSTANT, stamped.occurredAt)
    }

    @Test
    fun `a business day starts and ends at midnight of the zone even when it lasts 25 hours`() {
        val day = calendar.day(date("2026-11-01"))
        val start = calendar.startInstant(day)
        val end = calendar.endInstantExclusive(day)

        assertEquals(date("2026-11-01"), calendar.businessDateOf(start))
        assertEquals(date("2026-10-31"), calendar.businessDateOf(start - 1.milliseconds))
        assertEquals(date("2026-11-01"), calendar.businessDateOf(end - 1.milliseconds))
        assertEquals(date("2026-11-02"), calendar.businessDateOf(end))
        // Ese domingo termina el horario de verano en dos zonas de la matriz.
        val hours =
            when (settings.businessTimeZoneId) {
                "America/Los_Angeles", "America/St_Johns" -> 25
                else -> 24
            }
        assertEquals(hours.hours, end - start)
    }

    @Test
    fun `the week starts on the day of the row`() {
        // Un miércoles y un domingo, con el inicio de semana de la fila.
        val (fromWednesday, fromSunday) =
            when (settings.weekStartDay) {
                DayOfWeek.MONDAY -> "2026-11-02" to "2026-10-26"
                DayOfWeek.SATURDAY -> "2026-10-31" to "2026-10-31"
                DayOfWeek.SUNDAY -> "2026-11-01" to "2026-11-01"
                else -> error("Falta en esta prueba el inicio de semana de la fila $row")
            }

        assertEquals(ReportPeriod.Week(date(fromWednesday)), calendar.week(date("2026-11-04")))
        assertEquals(ReportPeriod.Week(date(fromSunday)), calendar.week(date("2026-11-01")))
        assertEquals(date(fromWednesday).plusDays(6), calendar.week(date("2026-11-04")).end)
    }

    @Test
    fun `every instant of a week and of a month falls inside it, and the end does not`() {
        val periods =
            listOf(
                calendar.week(calendar.businessDateOf(DOCUMENTED_INSTANT)),
                calendar.month(date("2026-11-01")),
            )
        assertEquals(ReportPeriod.Month(YearMonth(2026, 11)), periods.last())
        for (period in periods) {
            val start = calendar.startInstant(period)
            val end = calendar.endInstantExclusive(period)
            var instant = start
            while (instant < end) {
                assertTrue("$row $period $instant", calendar.businessDateOf(instant) in period)
                instant += 30.minutes
            }
            assertTrue(calendar.businessDateOf(start - 1.milliseconds) !in period)
            assertTrue(calendar.businessDateOf(end) !in period)
        }
    }

    private fun date(text: String): BusinessDate = BusinessDate(LocalDate.parse(text))

    companion object {
        /** El instante de la tabla de tiempo de la estrategia de pruebas: 2026-11-02 04:30 UTC. */
        private val DOCUMENTED_INSTANT = Instant.fromEpochMilliseconds(1_793_593_800_000)

        @JvmStatic
        @Parameterized.Parameters(name = "{0} {1}")
        fun parameters(): List<Array<Any>> = RegionalMatrix.withForeignDefaults()
    }
}
