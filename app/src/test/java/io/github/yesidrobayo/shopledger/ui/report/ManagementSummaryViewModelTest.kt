package io.github.yesidrobayo.shopledger.ui.report

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.ProfitTotals
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.report.ManagementSummary
import io.github.yesidrobayo.shopledger.domain.model.report.SummaryContent
import io.github.yesidrobayo.shopledger.domain.model.valueOrNull
import io.github.yesidrobayo.shopledger.domain.rule.MarginCalculator
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendar
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
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
 * El resumen de gestión sin base ni disco: abre en el último mes completo, avanza hasta el mes en
 * curso, conserva las cifras al cambiar de período, enciende ventas y compras al abrir y recuerda
 * lo que la persona cambia, y genera de a uno. Montos inventados.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ManagementSummaryViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val calendar =
        BusinessCalendar(requireNotNull(BusinessTimeZone.of("Etc/GMT+5")), DayOfWeek.MONDAY)

    /** 2026-11-02 15:00 UTC: lunes 2026-11-02 en la zona del negocio. */
    private val now = Instant.fromEpochMilliseconds(1_793_631_600_000L)
    private val october = ReportPeriod.Month(YearMonth(2026, 10))
    private val november = ReportPeriod.Month(YearMonth(2026, 11))
    private val reads = mutableListOf<ReportPeriod>()
    private val summaries = MutableStateFlow<Map<ReportPeriod, ManagementSummary?>>(emptyMap())

    @Before
    fun useTestMain() = Dispatchers.setMain(dispatcher)

    @After
    fun resetTestMain() = Dispatchers.resetMain()

    private fun model(
        result: BusinessCalendarResult = BusinessCalendarResult.Ready(calendar),
        observe: (ReportPeriod, BusinessCalendar) -> Flow<ManagementSummary?> = { period, _ ->
            reads += period
            summaries.map { it[period] ?: summary(period, hasMovements = false) }
        },
    ) = ManagementSummaryViewModel(
        calendar = flowOf(result),
        now = { now },
        observe = observe,
    )

    @Test
    fun `it opens on the last full month and can go forward to the month in progress`() =
        runTest(dispatcher) {
            summaries.value = mapOf(october to summary(october))
            val model = model()
            advanceUntilIdle()

            val opened = model.uiState.value
            assertEquals(october, opened.period)
            assertEquals(date(2026, 11, 2), opened.today)
            assertEquals(DayOfWeek.MONDAY, opened.weekStartDay)
            assertTrue(opened.canGoNext)
            assertEquals(october, opened.summary?.period)

            model.onAction(SummaryAction.NextPeriod)
            advanceUntilIdle()
            assertEquals(november, model.uiState.value.period)
            assertFalse(model.uiState.value.canGoNext)
            // Sin un mes siguiente que mirar, la flecha no hace nada.
            model.onAction(SummaryAction.NextPeriod)
            advanceUntilIdle()
            assertEquals(november, model.uiState.value.period)

            model.onAction(SummaryAction.PreviousPeriod)
            model.onAction(SummaryAction.PreviousPeriod)
            advanceUntilIdle()
            assertEquals(ReportPeriod.Month(YearMonth(2026, 9)), model.uiState.value.period)
            assertEquals(
                listOf(october, november, ReportPeriod.Month(YearMonth(2026, 9))),
                reads.distinct(),
            )
        }

    @Test
    fun `while the next period loads the figures of the previous one stay`() =
        runTest(dispatcher) {
            summaries.value = mapOf(october to summary(october))
            val slow = MutableStateFlow<ManagementSummary?>(null)
            val model = model(
                observe = { period, _ ->
                    if (period == october) {
                        summaries.map { it[period] }
                    } else {
                        flow { slow.collect { if (it != null) emit(it) } }
                    }
                },
            )
            advanceUntilIdle()

            model.onAction(SummaryAction.PreviousPeriod)
            advanceUntilIdle()
            assertEquals(october, model.uiState.value.summary?.period)

            val september = ReportPeriod.Month(YearMonth(2026, 9))
            slow.value = summary(september)
            advanceUntilIdle()
            assertEquals(september, model.uiState.value.summary?.period)
        }

    @Test
    fun `a chosen range replaces the month, in any order`() = runTest(dispatcher) {
        val model = model()
        advanceUntilIdle()

        model.onAction(SummaryAction.ChooseRange)
        assertTrue(model.uiState.value.choosingRange)
        model.onAction(SummaryAction.DismissRange)
        assertFalse(model.uiState.value.choosingRange)
        model.onAction(SummaryAction.ChooseRange)
        model.onAction(SummaryAction.ShowRange(date(2026, 10, 15), date(2026, 10, 1)))
        advanceUntilIdle()

        val range = ReportPeriod.Range(date(2026, 10, 1), date(2026, 10, 15))
        assertEquals(range, model.uiState.value.period)
        assertFalse(model.uiState.value.choosingRange)
        assertTrue(model.uiState.value.canGoNext)
    }

    @Test
    fun `sales and purchases start on and what the person changes is remembered`() =
        runTest(dispatcher) {
            summaries.value = mapOf(october to summary(october), november to summary(november))
            val model = model()
            advanceUntilIdle()
            assertEquals(
                setOf(SummaryContent.SALES_AND_PAYMENTS, SummaryContent.PURCHASES_AND_EXPENSES),
                model.uiState.value.chosen,
            )

            model.onAction(SummaryAction.Include(SummaryContent.CLOSING_INVENTORY, on = true))
            model.onAction(SummaryAction.Include(SummaryContent.SALES_AND_PAYMENTS, on = false))
            assertEquals(
                setOf(SummaryContent.PURCHASES_AND_EXPENSES, SummaryContent.CLOSING_INVENTORY),
                model.uiState.value.chosen,
            )

            model.onAction(SummaryAction.NextPeriod)
            advanceUntilIdle()
            assertEquals(
                setOf(SummaryContent.PURCHASES_AND_EXPENSES, SummaryContent.CLOSING_INVENTORY),
                model.uiState.value.chosen,
            )
        }

    @Test
    fun `generating is one at a time and ends with the ready sheet or the error`() =
        runTest(dispatcher) {
            summaries.value = mapOf(october to summary(october))
            val model = model()
            advanceUntilIdle()
            assertTrue(model.uiState.value.canGenerate)

            model.onAction(SummaryAction.Generate)
            assertNull(model.job.value)
            model.onAction(SummaryAction.Confirmed)
            val job = requireNotNull(model.job.value)
            assertEquals(october, job.summary.period)
            assertEquals(date(2026, 11, 2), job.today)
            assertEquals(model.uiState.value.chosen, job.chosen)
            assertEquals(SummaryGeneration.Working, model.uiState.value.generation)
            assertFalse(model.uiState.value.canGenerate)
            // Un segundo toque mientras escribe no pide otro paquete.
            model.onAction(SummaryAction.Confirmed)
            assertEquals(job, model.job.value)

            model.onAction(SummaryAction.Finished(SummaryOutcome.Saved("resumen.zip", 2_048L)))
            assertNull(model.job.value)
            assertEquals(
                SummaryGeneration.Ready("resumen.zip", 2_048L),
                model.uiState.value.generation,
            )
            model.onAction(SummaryAction.DismissResult)
            assertEquals(SummaryGeneration.Idle, model.uiState.value.generation)

            model.onAction(SummaryAction.Confirmed)
            assertEquals(2L, model.job.value?.attempt)
            model.onAction(SummaryAction.Finished(SummaryOutcome.NoSpace))
            assertEquals(SummaryGeneration.NoSpace, model.uiState.value.generation)
            model.onAction(SummaryAction.Confirmed)
            model.onAction(SummaryAction.Finished(SummaryOutcome.Failed))
            assertEquals(SummaryGeneration.Failed, model.uiState.value.generation)
            assertTrue(model.uiState.value.canGenerate)
        }

    @Test
    fun `a period without movements cannot be generated`() = runTest(dispatcher) {
        val model = model()
        advanceUntilIdle()

        assertEquals(false, model.uiState.value.summary?.hasMovements)
        assertFalse(model.uiState.value.canGenerate)
        model.onAction(SummaryAction.Confirmed)
        assertNull(model.job.value)
    }

    @Test
    fun `a failed read or a sum that does not fit shows the error and can retry`() =
        runTest(dispatcher) {
            var fail = true
            val model = model(
                observe = { period, _ ->
                    if (fail) flow { error("Falla inventada") } else flowOf(summary(period))
                },
            )
            advanceUntilIdle()
            assertEquals(SummaryScreenContent.ReadError, model.uiState.value.content)

            fail = false
            model.onAction(SummaryAction.Retry)
            assertEquals(SummaryScreenContent.Loading, model.uiState.value.content)
            advanceUntilIdle()
            assertEquals(october, model.uiState.value.summary?.period)

            val overflow = model(observe = { _, _ -> flowOf(null) })
            advanceUntilIdle()
            assertEquals(SummaryScreenContent.ReadError, overflow.uiState.value.content)
        }

    @Test
    fun `an unknown zone has no period to show`() = runTest(dispatcher) {
        val model = model(result = BusinessCalendarResult.UnknownZone("Nowhere/Unknown"))
        advanceUntilIdle()

        assertEquals(SummaryScreenContent.UnknownZone, model.uiState.value.content)
        assertNull(model.uiState.value.period)
        model.onAction(SummaryAction.PreviousPeriod)
        model.onAction(SummaryAction.Back)
        model.onAction(SummaryAction.Share)
        assertNull(model.uiState.value.period)
    }

    private fun summary(period: ReportPeriod, hasMovements: Boolean = true) = ManagementSummary(
        period = period,
        profit = requireNotNull(MarginCalculator.profit(TOTALS).valueOrNull()),
        tax = null,
        methods = emptyList(),
        salesWithoutCost = 0,
        hasMovements = hasMovements,
        contents = SummaryContent.entries,
    )

    private fun date(year: Int, month: Int, day: Int) = BusinessDate(LocalDate(year, month, day))

    private companion object {
        val TOTALS = ProfitTotals(
            salesNetTotal = Money(4_250_000),
            returnsNetRefund = Money.ZERO,
            salesTotalCost = Money(2_720_000),
            returnsCostReturned = Money.ZERO,
            recordedExpenses = Money(640_000),
            netShrinkage = Money.ZERO,
            paymentFees = Money.ZERO,
            cashRoundingTotal = Money.ZERO,
        )
    }
}
