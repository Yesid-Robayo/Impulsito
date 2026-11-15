package io.github.yesidrobayo.shopledger.ui.settings

import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.usecase.report.SetDailySummaryTimeResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalTime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * `Resumen diario` sin base: encenderlo pide la hora antes de guardar nada, apagarlo guarda nulo,
 * una falla se dice y la hoja conserva lo elegido, y una lectura fallida lo dice.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DailySummaryViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val stored = MutableStateFlow<LocalTime?>(null)
    private val saved = mutableListOf<LocalTime?>()
    private var result: SetDailySummaryTimeResult = SetDailySummaryTimeResult.Saved

    @Before
    fun useTestMain() = Dispatchers.setMain(dispatcher)

    @After
    fun resetTestMain() = Dispatchers.resetMain()

    private fun TestScope.viewModel(): Pair<DailySummaryViewModel, () -> DailySummaryUiState> {
        val model =
            DailySummaryViewModel(stored) { time ->
                saved += time
                if (result == SetDailySummaryTimeResult.Saved) stored.value = time
                result
            }
        backgroundScope.launch { model.uiState.collect {} }
        advanceUntilIdle()
        return model to { model.uiState.value }
    }

    @Test
    fun `turning it on asks for the time and saves only what was chosen`() = runTest(dispatcher) {
        val (model, state) = viewModel()
        assertEquals(DailySummaryUiState.Ready(null), state())

        model.onAction(DailySummaryAction.Toggle(true))
        advanceUntilIdle()
        assertEquals(DailySummaryUiState.Ready(null, choosing = true), state())
        assertEquals(emptyList<LocalTime?>(), saved)

        model.onAction(DailySummaryAction.Save(LocalTime(20, 30)))
        advanceUntilIdle()
        assertEquals(DailySummaryUiState.Ready(LocalTime(20, 30)), state())
    }

    @Test
    fun `turning it off saves null, and closing the sheet saves nothing`() = runTest(dispatcher) {
        stored.value = LocalTime(7, 0)
        val (model, state) = viewModel()

        model.onAction(DailySummaryAction.ChooseTime)
        model.onAction(DailySummaryAction.Dismiss)
        model.onAction(DailySummaryAction.Toggle(false))
        advanceUntilIdle()

        assertEquals(listOf<LocalTime?>(null), saved)
        assertEquals(DailySummaryUiState.Ready(null), state())
    }

    @Test
    fun `a failure keeps the sheet open and says why`() = runTest(dispatcher) {
        result = SetDailySummaryTimeResult.Failed(StorageFailure.DISK_FULL)
        val (model, state) = viewModel()

        model.onAction(DailySummaryAction.Toggle(true))
        model.onAction(DailySummaryAction.Save(LocalTime(20, 30)))
        advanceUntilIdle()

        assertEquals(
            DailySummaryUiState.Ready(null, choosing = true, saveFailed = true),
            state(),
        )
    }

    @Test
    fun `a read failure is said in its place`() = runTest(dispatcher) {
        val model = DailySummaryViewModel(flow { error("Sin base") }) { result }
        backgroundScope.launch { model.uiState.collect {} }
        advanceUntilIdle()

        assertEquals(DailySummaryUiState.ReadError, model.uiState.value)
    }
}
