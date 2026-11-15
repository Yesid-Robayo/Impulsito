package io.github.yesidrobayo.shopledger.ui.stats

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.ProfitTotals
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.report.BreakEven
import io.github.yesidrobayo.shopledger.domain.model.report.PeriodKind
import io.github.yesidrobayo.shopledger.domain.model.report.PeriodNoticeCounts
import io.github.yesidrobayo.shopledger.domain.model.report.PeriodTotals
import io.github.yesidrobayo.shopledger.domain.model.report.ReplacementProfit
import io.github.yesidrobayo.shopledger.domain.model.report.SoldAndCollected
import io.github.yesidrobayo.shopledger.domain.model.tax.TaxEstimate
import io.github.yesidrobayo.shopledger.domain.model.valueOrNull
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendar
import io.github.yesidrobayo.shopledger.domain.rule.report.DailySeries
import io.github.yesidrobayo.shopledger.domain.rule.report.PeriodMetricsCalculator
import io.github.yesidrobayo.shopledger.domain.usecase.report.ComparedPeriod
import io.github.yesidrobayo.shopledger.domain.usecase.report.PeriodBreakdown
import io.github.yesidrobayo.shopledger.domain.usecase.report.PeriodReport
import io.github.yesidrobayo.shopledger.domain.usecase.report.ReportCalendar
import io.github.yesidrobayo.shopledger.domain.usecase.report.SoldAndCollectedView
import io.github.yesidrobayo.shopledger.domain.usecase.tax.TaxEstimateState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
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

    private fun TestScope.viewModel(
        comparison: (
            ReportPeriod,
            BusinessDate,
        ) -> Flow<ComparedPeriod?> = { _, _ -> flowOf(null) },
        breakdown: (
            ReportPeriod,
            BusinessCalendar,
        ) -> Flow<PeriodBreakdown?> = { _, _ -> flowOf(null) },
        otherIncome: (ReportPeriod) -> Flow<Money> = { flowOf(Money.ZERO) },
        collection: (ReportPeriod) -> Flow<SoldAndCollectedView?> = { flowOf(null) },
        replacement: (ReportPeriod) -> Flow<ReplacementProfit?> = { flowOf(null) },
        breakEven: (ReportPeriod) -> Flow<BreakEven?> = { flowOf(null) },
    ): StatisticsViewModel {
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
                comparison = comparison,
                breakdown = breakdown,
                otherIncome = otherIncome,
                collection = collection,
                replacement = replacement,
                breakEven = breakEven,
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
    fun `the replacement profit shows apart, only with sales, and leaves the sales profit alone`() =
        runTest(dispatcher) {
            val november = ReportPeriod.Month(YearMonth(2026, 11))
            val replaced = MutableStateFlow<ReplacementProfit?>(
                ReplacementProfit(Money.ZERO, Money.ZERO, Money.ZERO, 0),
            )
            val model = viewModel(replacement = { replaced })
            assertNull(model.content().replacement)

            totals.value = mapOf(november to totals(net = 16_000, count = 1))
            val profit = ReplacementProfit(Money(16_000), Money(12_000), Money(4_000), 1)
            replaced.value = profit
            advanceUntilIdle()

            assertEquals(profit, model.content().replacement)
            assertEquals(Money(16_000), model.content().metrics?.profit?.grossProfit)

            replaced.value = null
            advanceUntilIdle()
            assertNull(model.content().replacement)
        }

    @Test
    fun `the break-even point of the period shown reaches the screen`() = runTest(dispatcher) {
        val november = ReportPeriod.Month(YearMonth(2026, 11))
        totals.value = mapOf(november to totals(net = 16_000, count = 1))
        val asked = mutableListOf<ReportPeriod>()
        val model = viewModel(
            breakEven = { period ->
                asked += period
                flowOf(BreakEven.NoMargin(Money(640_000)))
            },
        )

        assertEquals(BreakEven.NoMargin(Money(640_000)), model.content().breakEven)
        assertEquals(november, asked.last())
    }

    @Test
    fun `other income shows apart only when the period has some`() = runTest(dispatcher) {
        val income = MutableStateFlow(Money.ZERO)
        val model = viewModel(otherIncome = { income })
        assertEquals(null, model.content().otherIncome)

        income.value = Money(12_000)
        advanceUntilIdle()

        assertEquals(Money(12_000), model.content().otherIncome)
        // Aparte: la ganancia del período no la incluye.
        assertEquals(Money.ZERO, model.content().metrics?.profit?.netSales)
    }

    @Test
    fun `collected is offered apart only with credit sales and never added to sold`() =
        runTest(dispatcher) {
            val withoutCredit = viewModel()
            assertFalse(ChartMetric.COLLECTED in withoutCredit.content().offeredMetrics)
            // Elegirlo sin ventas a crédito deja las ventas.
            withoutCredit.onAction(StatisticsAction.SelectMetric(ChartMetric.COLLECTED))
            advanceUntilIdle()
            assertEquals(ChartMetric.SALES, withoutCredit.content().metric)

            val view = SoldAndCollectedView(
                SoldAndCollected(Money(10_000), Money(3_000), Money(4_000)),
                emptyList(),
            )
            val model = viewModel(collection = { flowOf(view) })
            assertTrue(ChartMetric.COLLECTED in model.content().offeredMetrics)
            model.onAction(StatisticsAction.SelectMetric(ChartMetric.COLLECTED))
            advanceUntilIdle()
            val content = model.content()
            assertEquals(ChartMetric.COLLECTED, content.metric)
            assertEquals(Money(7_000), content.collection?.totals?.collected)
            assertEquals(Money(10_000), content.collection?.totals?.sold)
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
    fun `a tapped bar is selected, released when the chart lets go and dropped on move`() =
        runTest(dispatcher) {
            val model = viewModel()
            val day = date("2026-11-03")

            model.onAction(StatisticsAction.SelectDay(day))
            advanceUntilIdle()
            assertEquals(day, model.content().selectedDay)
            // La gráfica repite el mismo día en cada redibujo: eso no suelta la barra, o parpadea.
            model.onAction(StatisticsAction.SelectDay(day))
            advanceUntilIdle()
            assertEquals(day, model.content().selectedDay)
            model.onAction(StatisticsAction.SelectDay(null))
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

    @Test
    fun `a range of its own shows its figures without arrows, chart or comparison`() =
        runTest(dispatcher) {
            val asked = mutableListOf<ReportPeriod>()
            val model =
                viewModel(comparison = { period, _ ->
                    asked += period
                    flowOf(null)
                })

            model.onAction(StatisticsAction.ChooseRange)
            advanceUntilIdle()
            assertTrue(model.content().choosingRange)
            // Las fechas al revés se ordenan solas.
            model.onAction(StatisticsAction.ShowRange(date("2026-10-31"), date("2026-08-01")))
            advanceUntilIdle()

            val range = ReportPeriod.Range(date("2026-08-01"), date("2026-10-31"))
            val content = model.content()
            assertEquals(range, content.period)
            assertNull(content.kind)
            assertFalse(content.canGoPrevious)
            assertFalse(content.canGoNext)
            assertNull(content.series)
            assertFalse(content.choosingRange)
            assertEquals(range, asked.last())

            // Las flechas no lo mueven, y elegir un mes vuelve a los períodos.
            model.onAction(StatisticsAction.Previous)
            advanceUntilIdle()
            assertEquals(range, model.content().period)
            model.onAction(StatisticsAction.SelectKind(PeriodKind.MONTH))
            advanceUntilIdle()
            assertEquals(ReportPeriod.Month(YearMonth(2026, 8)), model.content().period)
        }

    @Test
    fun `closing the range picker changes nothing`() = runTest(dispatcher) {
        val model = viewModel()

        model.onAction(StatisticsAction.ChooseRange)
        model.onAction(StatisticsAction.DismissRange)
        advanceUntilIdle()

        assertFalse(model.content().choosingRange)
        assertEquals(ReportPeriod.Month(YearMonth(2026, 11)), model.content().period)
    }

    @Test
    fun `the ranking changes and the breakdown and units arrive`() = runTest(dispatcher) {
        val breakdown =
            PeriodBreakdown(
                top = null,
                methods = emptyList(),
                expenses = null,
                notices = PeriodNoticeCounts.NONE,
            )
        val model = viewModel(breakdown = { _, _ -> flowOf(breakdown) })

        model.onAction(StatisticsAction.SelectRanking(RankingKind.QUANTITY))
        advanceUntilIdle()

        assertEquals(RankingKind.QUANTITY, model.content().ranking)
        assertEquals(breakdown, model.content().breakdown)
        assertEquals(DayOfWeek.MONDAY, model.content().weekStartDay)
    }

    @Test
    fun `a product shows and hides its variants`() = runTest(dispatcher) {
        val model = viewModel()
        advanceUntilIdle()

        model.onAction(StatisticsAction.ToggleVariants(7L))
        model.onAction(StatisticsAction.ToggleVariants(9L))
        advanceUntilIdle()
        assertEquals(setOf(7L, 9L), model.content().expandedProducts)

        model.onAction(StatisticsAction.ToggleVariants(7L))
        advanceUntilIdle()
        assertEquals(setOf(9L), model.content().expandedProducts)
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
