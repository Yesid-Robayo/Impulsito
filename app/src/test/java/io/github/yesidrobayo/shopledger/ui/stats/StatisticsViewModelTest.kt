package io.github.yesidrobayo.shopledger.ui.stats

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.ProfitTotals
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.report.PeriodKind
import io.github.yesidrobayo.shopledger.domain.model.report.PeriodTotals
import io.github.yesidrobayo.shopledger.domain.model.tax.TaxEstimate
import io.github.yesidrobayo.shopledger.domain.model.valueOrNull
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendar
import io.github.yesidrobayo.shopledger.domain.rule.report.DailySeries
import io.github.yesidrobayo.shopledger.domain.rule.report.PeriodMetricsCalculator
import io.github.yesidrobayo.shopledger.domain.usecase.report.PeriodReport
import io.github.yesidrobayo.shopledger.domain.usecase.report.ReportCalendar
import io.github.yesidrobayo.shopledger.domain.usecase.tax.TaxEstimateState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * `Números` sin base: abre en el mes en curso, recorre los períodos sin pasar del límite, cambia de
 * tamaño sin perder el lugar, y resuelve los estados sin datos, sin zona y con error. Las cifras
 * salen de las reglas de `:domain` sobre sumas inventadas.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StatisticsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val zone = requireNotNull(BusinessTimeZone.of("Etc/GMT+5"))
    private val calendar = BusinessCalendar(zone, DayOfWeek.MONDAY)

    /** Miércoles 2026-11-04. */
    private val today = date("2026-11-04")

    private val calendars =
        MutableStateFlow<ReportCalendar>(ReportCalendar.Ready(calendar, today, today))
    private val lastActive = MutableStateFlow<BusinessDate?>(today)
    private val totals = MutableStateFlow(emptyMap<ReportPeriod, PeriodTotals>())
    private val taxed = MutableStateFlow(false)
    private var failing = false

    /** Los períodos cuya serie se pidió, en orden. */
    private val seriesAsked = mutableListOf<ReportPeriod>()

    @Before
    fun useTestMain() = Dispatchers.setMain(dispatcher)

    @After
    fun resetTestMain() = Dispatchers.resetMain()

    private fun TestScope.viewModel(): StatisticsViewModel {
        val model =
            StatisticsViewModel(
                calendar = { checks -> combine(checks, calendars) { _, it -> it } },
                lastActiveDate = lastActive,
                report = { period ->
                    flow {
                        if (failing) error("Falla inventada")
                        totals.collect { emit(it[period] ?: PeriodTotals.ZERO) }
                    }.map { PeriodReport(period, PeriodMetricsCalculator.of(it).valueOrNull()) }
                },
                series = { period ->
                    seriesAsked += period
                    totals.map { DailySeries.of(period, emptyList()).valueOrNull() }
                },
                tax = { _ ->
                    taxed.map {
                        if (it) {
                            TaxEstimateState.Estimated(TaxEstimate(Money(50_400), Money(21_000)))
                        } else {
                            TaxEstimateState.Hidden
                        }
                    }
                },
            )
        backgroundScope.launch { model.uiState.collect {} }
        advanceUntilIdle()
        return model
    }

    private fun StatisticsViewModel.content() = uiState.value as StatisticsUiState.Content

    @Test
    fun `it opens on the current month with its sums`() = runTest(dispatcher) {
        val november = ReportPeriod.Month(YearMonth(2026, 11))
        totals.value = mapOf(november to totals(net = 4_250_000, count = 312))

        val content = viewModel().content()

        assertEquals(PeriodKind.MONTH, content.kind)
        assertEquals(november, content.period)
        assertEquals(Money(4_250_000), content.metrics?.profit?.netSales)
        assertEquals(Money(13_622), content.metrics?.averageTicket)
        assertEquals(30, content.series?.size)
        assertTrue(content.canGoPrevious)
        assertFalse(content.canGoNext)
        assertNull(content.tax)
    }

    @Test
    fun `an empty period shows zeros and says it had no movements`() = runTest(dispatcher) {
        val metrics = requireNotNull(viewModel().content().metrics)

        assertFalse(metrics.hasMovements)
        assertEquals(Money.ZERO, metrics.profit.netProfit)
        assertNull(metrics.averageTicket)
    }

    @Test
    fun `going back and forward never passes the current period`() = runTest(dispatcher) {
        val model = viewModel()

        model.onAction(StatisticsAction.Previous)
        advanceUntilIdle()
        assertEquals(ReportPeriod.Month(YearMonth(2026, 10)), model.content().period)
        assertTrue(model.content().canGoNext)

        model.onAction(StatisticsAction.Next)
        advanceUntilIdle()
        assertEquals(ReportPeriod.Month(YearMonth(2026, 11)), model.content().period)
        assertFalse(model.content().canGoNext)
    }

    @Test
    fun `two quick taps move two periods and next stops at the limit`() = runTest(dispatcher) {
        val model = viewModel()

        model.onAction(StatisticsAction.Previous)
        model.onAction(StatisticsAction.Previous)
        advanceUntilIdle()
        assertEquals(ReportPeriod.Month(YearMonth(2026, 9)), model.content().period)

        repeat(3) { model.onAction(StatisticsAction.Next) }
        advanceUntilIdle()
        assertEquals(ReportPeriod.Month(YearMonth(2026, 11)), model.content().period)
    }

    @Test
    fun `data dated after today can be reached`() = runTest(dispatcher) {
        calendars.value = ReportCalendar.Ready(calendar, today, date("2026-12-15"))

        val model = viewModel()

        assertTrue(model.content().canGoNext)
        model.onAction(StatisticsAction.Next)
        advanceUntilIdle()
        assertEquals(ReportPeriod.Month(YearMonth(2026, 12)), model.content().period)
        assertFalse(model.content().canGoNext)
    }

    @Test
    fun `changing size keeps the current period or the start of the one shown`() =
        runTest(dispatcher) {
            val model = viewModel()

            model.onAction(StatisticsAction.SelectKind(PeriodKind.WEEK))
            advanceUntilIdle()
            assertEquals(ReportPeriod.Week(date("2026-11-02")), model.content().period)
            assertEquals(7, model.content().series?.size)

            model.onAction(StatisticsAction.Previous)
            model.onAction(StatisticsAction.SelectKind(PeriodKind.DAY))
            advanceUntilIdle()
            assertEquals(ReportPeriod.Day(date("2026-10-26")), model.content().period)
            // Un día es una cifra: no lleva gráfica.
            assertNull(model.content().series)
        }

    @Test
    fun `a tapped bar is selected, released by tapping it again and dropped on move`() =
        runTest(dispatcher) {
            val model = viewModel()
            val day = date("2026-11-03")

            model.onAction(StatisticsAction.SelectDay(day))
            advanceUntilIdle()
            assertEquals(day, model.content().selectedDay)
            model.onAction(StatisticsAction.SelectDay(day))
            advanceUntilIdle()
            assertNull(model.content().selectedDay)

            model.onAction(StatisticsAction.SelectDay(day))
            model.onAction(StatisticsAction.Previous)
            advanceUntilIdle()
            assertNull(model.content().selectedDay)
        }

    @Test
    fun `the metric and the list change without asking the series again`() = runTest(dispatcher) {
        val model = viewModel()

        model.onAction(StatisticsAction.SelectMetric(ChartMetric.PROFIT))
        model.onAction(StatisticsAction.ToggleList)
        advanceUntilIdle()

        assertEquals(ChartMetric.PROFIT, model.content().metric)
        assertTrue(model.content().showList)
        assertEquals(1, seriesAsked.size)
    }

    @Test
    fun `a business with tax shows its estimate`() = runTest(dispatcher) {
        taxed.value = true

        assertEquals(TaxEstimate(Money(50_400), Money(21_000)), viewModel().content().tax)
    }

    @Test
    fun `without sales or expenses the numbers wait for the first sale`() = runTest(dispatcher) {
        lastActive.value = null

        assertEquals(StatisticsUiState.NoData, viewModel().uiState.value)
    }

    @Test
    fun `an unknown zone never falls back to another one`() = runTest(dispatcher) {
        calendars.value = ReportCalendar.UnknownZone

        assertEquals(StatisticsUiState.UnknownZone, viewModel().uiState.value)
    }

    @Test
    fun `a read error offers a retry that reads again`() = runTest(dispatcher) {
        failing = true
        val model = viewModel()
        assertEquals(StatisticsUiState.ReadError, model.uiState.value)

        failing = false
        model.onAction(StatisticsAction.Retry)
        advanceUntilIdle()

        assertTrue(model.uiState.value is StatisticsUiState.Content)
    }

    @Test
    fun `a new day moves the current period when the screen looks again`() = runTest(dispatcher) {
        val model = viewModel()

        val december = date("2026-12-01")
        calendars.value = ReportCalendar.Ready(calendar, december, december)
        model.onReturnToForeground()
        advanceUntilIdle()

        assertEquals(ReportPeriod.Month(YearMonth(2026, 12)), model.content().period)
    }

    private fun totals(net: Long = 0, count: Long = 0) = PeriodTotals(
        ProfitTotals(
            salesNetTotal = Money(net),
            returnsNetRefund = Money.ZERO,
            salesTotalCost = Money.ZERO,
            returnsCostReturned = Money.ZERO,
            recordedExpenses = Money.ZERO,
            netShrinkage = Money.ZERO,
            paymentFees = Money.ZERO,
            cashRoundingTotal = Money.ZERO,
        ),
        salesCount = count,
    )

    private fun date(text: String) = BusinessDate(LocalDate.parse(text))
}
