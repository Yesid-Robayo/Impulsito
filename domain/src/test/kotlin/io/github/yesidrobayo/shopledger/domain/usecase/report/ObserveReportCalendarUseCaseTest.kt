package io.github.yesidrobayo.shopledger.domain.usecase.report

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendar
import io.github.yesidrobayo.shopledger.domain.testing.FakeAppSettingsRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakePeriodReportRepository
import io.github.yesidrobayo.shopledger.domain.testing.appSettings
import io.github.yesidrobayo.shopledger.domain.testing.regionalSettings
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import kotlin.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * El calendario de las estadísticas: hoy sale de la zona del negocio, el límite hacia adelante
 * llega hasta los datos con fecha futura, y el día solo cambia cuando la pantalla vuelve a mirar
 * la fecha. Datos inventados.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ObserveReportCalendarUseCaseTest {
    private val zoneId = "Etc/GMT+5"

    /** 2026-11-02 03:00 UTC: todavía domingo 2026-11-01, a las 22:00, en la zona del negocio. */
    private var now = Instant.parse("2026-11-02T03:00:00Z")
    private val clock = Clock { now }

    private val settings =
        FakeAppSettingsRepository(appSettings(regionalSettings(zoneId, DayOfWeek.SUNDAY)))
    private val reports = FakePeriodReportRepository()
    private val observe =
        ObserveReportCalendarUseCase(BusinessCalendarSource(settings), reports, clock)

    private val calendar =
        BusinessCalendar(requireNotNull(BusinessTimeZone.of(zoneId)), DayOfWeek.SUNDAY)
    private val sunday = BusinessDate(LocalDate(2026, 11, 1))
    private val monday = BusinessDate(LocalDate(2026, 11, 2))

    @Test
    fun `today is the business date in the business zone and the limit is today`() = runTest {
        assertEquals(ReportCalendar.Ready(calendar, sunday, sunday), observe(flowOf(Unit)).first())
    }

    @Test
    fun `older data keeps the limit at today`() = runTest {
        reports.lastActiveDate.value = BusinessDate(LocalDate(2026, 10, 30))

        assertEquals(ReportCalendar.Ready(calendar, sunday, sunday), observe(flowOf(Unit)).first())
    }

    @Test
    fun `data dated after today moves the limit forward`() = runTest {
        // Con el reloj adelantado quedó una venta el 2026-12-15.
        val future = BusinessDate(LocalDate(2026, 12, 15))
        reports.lastActiveDate.value = future

        assertEquals(ReportCalendar.Ready(calendar, sunday, future), observe(flowOf(Unit)).first())
    }

    @Test
    fun `after midnight today changes when the screen looks at the date again`() = runTest {
        val checks = MutableSharedFlow<Unit>(replay = 1)
        checks.tryEmit(Unit)
        val seen = mutableListOf<ReportCalendar>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            observe(checks).collect { seen += it }
        }

        now = Instant.parse("2026-11-02T05:30:00Z")
        assertEquals(sunday, (seen.last() as ReportCalendar.Ready).today)
        checks.emit(Unit)
        // Mirar otra vez el mismo día no repite el calendario.
        checks.emit(Unit)
        job.cancel()

        assertEquals(
            listOf(
                ReportCalendar.Ready(calendar, sunday, sunday),
                ReportCalendar.Ready(calendar, monday, monday),
            ),
            seen,
        )
    }

    @Test
    fun `a new first day of the week arrives as a new calendar`() = runTest {
        val seen = mutableListOf<ReportCalendar>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            observe(flowOf(Unit)).collect { seen += it }
        }

        settings.settings.value = appSettings(regionalSettings(zoneId, DayOfWeek.MONDAY))
        job.cancel()

        assertEquals(
            ReportCalendar.Ready(calendar.copy(weekStartDay = DayOfWeek.MONDAY), sunday, sunday),
            seen.last(),
        )
    }

    @Test
    fun `an unknown zone never falls back to another one`() = runTest {
        settings.settings.value =
            appSettings(regionalSettings("Nowhere/Invented", DayOfWeek.MONDAY))

        assertEquals(ReportCalendar.UnknownZone, observe(flowOf(Unit)).first())
    }
}
