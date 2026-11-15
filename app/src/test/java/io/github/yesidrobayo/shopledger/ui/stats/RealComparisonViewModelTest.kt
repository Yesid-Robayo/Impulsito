package io.github.yesidrobayo.shopledger.ui.stats

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.report.RealComparison
import io.github.yesidrobayo.shopledger.domain.model.report.RealValue
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
import kotlinx.datetime.YearMonth
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * `Comparar` sin base: abre en `Normal`, conserva el modo al cambiar de mes, se oculta sin nada
 * que comparar y dice un error sin cerrar la pantalla. Montos de la regla, escritos a mano.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RealComparisonViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val today = BusinessDate(LocalDate(2026, 11, 2))
    private val october = ReportPeriod.Month(YearMonth(2026, 10))
    private val september = ReportPeriod.Month(YearMonth(2026, 9))
    private var failing = false

    @Before
    fun useTestMain() = Dispatchers.setMain(dispatcher)

    @After
    fun resetTestMain() = Dispatchers.resetMain()

    private fun read(period: ReportPeriod, day: BusinessDate): Flow<RealComparison?> {
        if (failing) return flow { error("Sin base") }
        // El comparable depende de hoy: con otro día no hay nada que mostrar.
        if (day != today) return flowOf(null)
        return flowOf(
            when (period) {
                october -> SOLD
                september -> SOLD.copy(base = Money.ZERO, nominal = Money.ZERO)
                else -> null
            },
        )
    }

    private fun TestScope.viewModel(): Pair<RealComparisonViewModel, () -> ComparisonUiState> {
        val model = RealComparisonViewModel(::read)
        backgroundScope.launch { model.uiState.collect {} }
        return model to { model.uiState.value }
    }

    @Test
    fun `it opens in normal and keeps real across months`() = runTest(dispatcher) {
        val (model, state) = viewModel()
        model.show(october, today)
        advanceUntilIdle()
        assertEquals(ComparisonUiState.Ready(SOLD, CompareMode.NOMINAL), state())

        model.select(CompareMode.REAL)
        model.show(ReportPeriod.Day(today), today)
        advanceUntilIdle()
        assertEquals(ComparisonUiState.Hidden, state())

        model.show(october, today)
        advanceUntilIdle()
        assertEquals(ComparisonUiState.Ready(SOLD, CompareMode.REAL), state())
    }

    @Test
    fun `two months without sales have nothing to compare`() = runTest(dispatcher) {
        val (model, state) = viewModel()
        advanceUntilIdle()
        assertEquals(ComparisonUiState.Loading, state())

        model.show(september, today)
        advanceUntilIdle()

        assertEquals(ComparisonUiState.Hidden, state())
    }

    @Test
    fun `a read failure says so and retrying reads again`() = runTest(dispatcher) {
        failing = true
        val (model, state) = viewModel()
        model.show(october, today)
        advanceUntilIdle()
        assertEquals(ComparisonUiState.ReadError, state())

        failing = false
        model.retry()
        advanceUntilIdle()

        assertEquals(ComparisonUiState.Ready(SOLD, CompareMode.NOMINAL), state())
    }

    private companion object {
        val SOLD =
            RealComparison(
                period = ReportPeriod.Month(YearMonth(2026, 10)),
                comparedTo = ReportPeriod.Month(YearMonth(2026, 9)),
                base = Money(1_000_000),
                nominal = Money(1_100_000),
                nominalVariation = BasisPoints(1_000),
                real = RealValue.Estimated(Money(1_067_961), BasisPoints(680)),
            )
    }
}
