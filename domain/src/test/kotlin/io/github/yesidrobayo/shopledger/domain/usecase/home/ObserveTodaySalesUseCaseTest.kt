package io.github.yesidrobayo.shopledger.domain.usecase.home

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.Margin
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.sale.SalesSummary
import io.github.yesidrobayo.shopledger.domain.model.sale.SalesTotals
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.SalesReportRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakeAppSettingsRepository
import io.github.yesidrobayo.shopledger.domain.testing.appSettings
import io.github.yesidrobayo.shopledger.domain.testing.regionalSettings
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import kotlin.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Lo vendido hoy en Inicio: el día sale de la zona del negocio, las cifras siguen a cada cambio
 * confirmado, y el día solo cambia cuando la pantalla vuelve a mirar la fecha. Datos inventados.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ObserveTodaySalesUseCaseTest {
    private val zoneId = "Etc/GMT+5"

    /** 2026-11-02 03:00 UTC: todavía domingo 2026-11-01, a las 22:00, en la zona del negocio. */
    private var now = Instant.parse("2026-11-02T03:00:00Z")
    private val clock = Clock { now }

    private val settings =
        FakeAppSettingsRepository(appSettings(regionalSettings(zoneId, DayOfWeek.MONDAY)))

    /** Las sumas de cada período, como las daría la base. */
    private val totals = MutableStateFlow(emptyMap<ReportPeriod, SalesTotals>())

    /** Los períodos que se pidieron, en orden. */
    private val asked = mutableListOf<ReportPeriod>()

    private val reports = object : SalesReportRepository {
        override fun observeTotals(period: ReportPeriod): Flow<SalesTotals> {
            asked += period
            return totals.map { it[period] ?: SalesTotals.ZERO }
        }
    }

    private val observe = ObserveTodaySalesUseCase(BusinessCalendarSource(settings), reports, clock)

    private val sunday = BusinessDate(LocalDate(2026, 11, 1))
    private val monday = BusinessDate(LocalDate(2026, 11, 2))
    private val zone = requireNotNull(BusinessTimeZone.of(zoneId))

    @Test
    fun `today is the business date in the business zone, not in UTC`() = runTest {
        totals.value = mapOf(
            ReportPeriod.Day(sunday) to
                SalesTotals(Money(84_000), Money.ZERO, Money(60_000), Money.ZERO, 3),
        )

        val state = observe(flowOf(Unit)).first()

        assertEquals(
            TodaySales.Ready(
                sunday,
                zone,
                SalesSummary(
                    netSales = Money(84_000),
                    salesCount = 3,
                    grossProfit = Money(24_000),
                    grossMargin = Margin.Defined(BasisPoints(2_857)),
                ),
            ),
            state,
        )
        assertEquals(listOf<ReportPeriod>(ReportPeriod.Day(sunday)), asked)
    }

    @Test
    fun `a confirmed sale arrives without asking again`() = runTest {
        val seen = mutableListOf<TodaySales>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            observe(flowOf(Unit)).collect { seen += it }
        }

        totals.value = mapOf(
            ReportPeriod.Day(sunday) to
                SalesTotals(Money(10_000), Money.ZERO, Money(7_000), Money.ZERO, 1),
        )
        job.cancel()

        assertEquals(2, seen.size)
        assertEquals(1L, (seen.last() as TodaySales.Ready).summary?.salesCount)
        assertEquals(1, asked.size)
    }

    @Test
    fun `after midnight the figures restart when the screen looks at the date again`() = runTest {
        val checks = MutableSharedFlow<Unit>(replay = 1)
        checks.tryEmit(Unit)
        totals.value = mapOf(
            ReportPeriod.Day(sunday) to
                SalesTotals(Money(10_000), Money.ZERO, Money(7_000), Money.ZERO, 1),
        )
        val seen = mutableListOf<TodaySales>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            observe(checks).collect { seen += it }
        }

        // Pasa la medianoche del negocio: sin una nueva mirada, nada cambia.
        now = Instant.parse("2026-11-02T05:30:00Z")
        assertEquals(sunday, (seen.last() as TodaySales.Ready).date)
        // Al volver a primer plano, el día es otro y sus cifras empiezan en cero.
        checks.emit(Unit)
        job.cancel()

        val last = seen.last() as TodaySales.Ready
        assertEquals(monday, last.date)
        assertEquals(0L, last.summary?.salesCount)
        assertEquals(
            listOf<ReportPeriod>(ReportPeriod.Day(sunday), ReportPeriod.Day(monday)),
            asked,
        )
    }

    @Test
    fun `looking again on the same day does not ask the sums again`() = runTest {
        val checks = MutableSharedFlow<Unit>(replay = 1)
        checks.tryEmit(Unit)
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { observe(checks).collect {} }

        checks.emit(Unit)
        checks.emit(Unit)
        job.cancel()

        assertEquals(listOf<ReportPeriod>(ReportPeriod.Day(sunday)), asked)
    }

    @Test
    fun `an unknown zone never falls back to another one`() = runTest {
        settings.settings.value =
            appSettings(regionalSettings("Nowhere/Invented", DayOfWeek.MONDAY))

        assertEquals(TodaySales.UnknownZone, observe(flowOf(Unit)).first())
        assertEquals(emptyList<ReportPeriod>(), asked)
    }

    @Test
    fun `a sum that does not fit shows no value instead of failing`() = runTest {
        totals.value = mapOf(
            ReportPeriod.Day(sunday) to
                SalesTotals(Money(Long.MIN_VALUE), Money(1), Money.ZERO, Money.ZERO, 1),
        )

        assertEquals(TodaySales.Ready(sunday, zone, null), observe(flowOf(Unit)).first())
    }
}
