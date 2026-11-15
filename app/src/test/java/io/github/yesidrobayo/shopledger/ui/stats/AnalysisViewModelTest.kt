package io.github.yesidrobayo.shopledger.ui.stats

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.report.CustomerSales
import io.github.yesidrobayo.shopledger.domain.model.report.IdleProducts
import io.github.yesidrobayo.shopledger.domain.model.report.PeriodAnalysis
import io.github.yesidrobayo.shopledger.domain.model.report.ProfitConcentration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * `Análisis` sin base: espera el período de la pantalla, lee el de cada cambio, se oculta sin nada
 * que decir, y dice un error sin cerrar la pantalla. Montos inventados.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AnalysisViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val today = ReportPeriod.Day(BusinessDate(LocalDate(2026, 11, 4)))
    private val yesterday = ReportPeriod.Day(BusinessDate(LocalDate(2026, 11, 3)))
    private val asked = mutableListOf<ReportPeriod>()
    private var failing = false

    @Before
    fun useTestMain() = Dispatchers.setMain(dispatcher)

    @After
    fun resetTestMain() = Dispatchers.resetMain()

    private fun read(period: ReportPeriod): Flow<PeriodAnalysis> {
        asked += period
        if (failing) return flow { error("Sin base") }
        return flowOf(if (period == today) WITH_BUYER else EMPTY)
    }

    private fun TestScope.viewModel(): Pair<AnalysisViewModel, () -> AnalysisUiState> {
        val model = AnalysisViewModel(::read)
        backgroundScope.launch { model.uiState.collect {} }
        return model to { model.uiState.value }
    }

    @Test
    fun `it waits for the period and then reads that one`() = runTest(dispatcher) {
        val (model, state) = viewModel()
        advanceUntilIdle()
        assertEquals(AnalysisUiState.Loading, state())
        assertEquals(emptyList<ReportPeriod>(), asked)

        model.show(today)
        advanceUntilIdle()

        assertEquals(AnalysisUiState.Ready(WITH_BUYER), state())
        assertEquals(listOf<ReportPeriod>(today), asked)
    }

    @Test
    fun `a period with nothing to say hides the tool`() = runTest(dispatcher) {
        val (model, state) = viewModel()
        model.show(yesterday)
        advanceUntilIdle()

        assertEquals(AnalysisUiState.Hidden, state())
    }

    @Test
    fun `a read failure says so and retrying reads again`() = runTest(dispatcher) {
        failing = true
        val (model, state) = viewModel()
        model.show(today)
        advanceUntilIdle()
        assertEquals(AnalysisUiState.ReadError, state())

        failing = false
        model.retry()
        advanceUntilIdle()

        assertEquals(AnalysisUiState.Ready(WITH_BUYER), state())
        assertEquals(listOf<ReportPeriod>(today, today), asked)
    }

    private companion object {
        val EMPTY = PeriodAnalysis(ProfitConcentration.NoSales, IdleProducts.NONE, emptyList())
        val WITH_BUYER =
            EMPTY.copy(customers = listOf(CustomerSales(1, "Cliente de prueba", 1, Money(500))))
    }
}
