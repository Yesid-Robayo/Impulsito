package io.github.yesidrobayo.shopledger.ui.priceindex

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.region.NumberSymbols
import io.github.yesidrobayo.shopledger.domain.model.report.PriceIndexEntry
import io.github.yesidrobayo.shopledger.domain.model.report.PriceIndexView
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.usecase.report.DeletePriceIndexResult
import io.github.yesidrobayo.shopledger.domain.usecase.report.SavePriceIndexResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.YearMonth
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * `Índice de precios` sin base: la hoja abre en el mes que falta, no pasa del mes de hoy, lee el
 * cambio con signo y dos decimales, dice cada rechazo y pide confirmar el borrado. Valores
 * inventados.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PriceIndexViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val symbols = NumberSymbols(decimalSeparator = ',', minusSign = '-', grouping = null)
    private val october = YearMonth(2026, 10)
    private val november = YearMonth(2026, 11)
    private val july = YearMonth(2026, 7)
    private val september = YearMonth(2026, 9)

    private val view =
        MutableStateFlow(
            PriceIndexView(
                entries =
                    listOf(
                        PriceIndexEntry(october, BasisPoints(-25), "Lista propia"),
                        PriceIndexEntry(july, BasisPoints(190), null),
                    ),
                missing = listOf(YearMonth(2026, 8), YearMonth(2026, 9)),
            ),
        )
    private val saved = mutableListOf<List<Any?>>()
    private val deleted = mutableListOf<YearMonth>()
    private var saveResult: SavePriceIndexResult = SavePriceIndexResult.Saved
    private var deleteResult: DeletePriceIndexResult = DeletePriceIndexResult.Deleted

    @Before
    fun useTestMain() = Dispatchers.setMain(dispatcher)

    @After
    fun resetTestMain() = Dispatchers.resetMain()

    private fun TestScope.viewModel(): Pair<PriceIndexViewModel, () -> PriceIndexUiState.Ready> {
        val model =
            PriceIndexViewModel(
                observe = { view },
                currentMonth = flowOf(november),
                save = { month, change, note, editing ->
                    saved += listOf(month, change, note, editing)
                    saveResult
                },
                delete = { month ->
                    deleted += month
                    deleteResult
                },
                symbols = symbols,
            )
        backgroundScope.launch { model.uiState.collect {} }
        advanceUntilIdle()
        return model to { model.uiState.value as PriceIndexUiState.Ready }
    }

    @Test
    fun `adding opens the first missing month and saves what was written`() = runTest(dispatcher) {
        val (model, state) = viewModel()

        model.onAction(PriceIndexAction.Add)
        advanceUntilIdle()
        assertEquals(IndexSheet(YearMonth(2026, 8), editing = false), state().sheet)

        model.onAction(PriceIndexAction.ChangeText("-1,25"))
        model.onAction(PriceIndexAction.SourceText("Lista propia"))
        model.onAction(PriceIndexAction.Save)
        advanceUntilIdle()

        assertEquals(
            listOf(listOf(YearMonth(2026, 8), BasisPoints(-125), "Lista propia", false)),
            saved,
        )
        assertNull(state().sheet)
    }

    @Test
    fun `editing a month brings its value back as it was written`() = runTest(dispatcher) {
        val (model, state) = viewModel()

        model.onAction(PriceIndexAction.Edit(october))
        model.onAction(PriceIndexAction.Edit(october))
        advanceUntilIdle()
        assertEquals(
            IndexSheet(october, editing = true, changeText = "-0,25", source = "Lista propia"),
            state().sheet,
        )

        model.onAction(PriceIndexAction.Edit(july))
        advanceUntilIdle()
        assertEquals("1,9", state().sheet?.changeText)
    }

    @Test
    fun `a new month never goes past this month and an edited one does not move`() =
        runTest(dispatcher) {
            val (model, state) = viewModel()
            model.onAction(PriceIndexAction.AddMonth(september))
            model.onAction(PriceIndexAction.NextMonth)
            model.onAction(PriceIndexAction.NextMonth)
            model.onAction(PriceIndexAction.NextMonth)
            advanceUntilIdle()
            assertEquals(november, state().sheet?.month)

            model.onAction(PriceIndexAction.PreviousMonth)
            advanceUntilIdle()
            assertEquals(october, state().sheet?.month)

            model.onAction(PriceIndexAction.Edit(july))
            model.onAction(PriceIndexAction.PreviousMonth)
            advanceUntilIdle()
            assertEquals(july, state().sheet?.month)
        }

    @Test
    fun `each written problem is said under its field`() = runTest(dispatcher) {
        val (model, state) = viewModel()
        model.onAction(PriceIndexAction.AddMonth(september))
        val cases =
            listOf(
                "" to ChangeProblem.NOT_A_NUMBER,
                "1,234" to ChangeProblem.TOO_MANY_DECIMALS,
                "-100" to ChangeProblem.TOO_LOW,
                "10000,01" to ChangeProblem.TOO_HIGH,
            )

        cases.forEach { (text, problem) ->
            model.onAction(PriceIndexAction.ChangeText(text))
            model.onAction(PriceIndexAction.Save)
            advanceUntilIdle()
            assertEquals(problem, state().sheet?.problem)
        }
        assertEquals(emptyList<List<Any?>>(), saved)
    }

    @Test
    fun `each rejection of the save stays in the sheet`() = runTest(dispatcher) {
        val (model, state) = viewModel()
        val written = IndexSheet(september, editing = false, changeText = "2")
        val cases =
            listOf(
                SavePriceIndexResult.MonthTaken to written.copy(monthTaken = true),
                SavePriceIndexResult.FutureMonth to written.copy(futureMonth = true),
                SavePriceIndexResult.ChangeOutOfRange to
                    written.copy(problem = ChangeProblem.TOO_HIGH),
                SavePriceIndexResult.NoteTooLong to written.copy(sourceTooLong = true),
                SavePriceIndexResult.UnknownTimeZone to written.copy(saveFailed = false),
                SavePriceIndexResult.Failed(StorageFailure.DISK_FULL) to
                    written.copy(saveFailed = true),
            )

        cases.forEach { (result, expected) ->
            model.onAction(PriceIndexAction.Dismiss)
            model.onAction(PriceIndexAction.AddMonth(september))
            model.onAction(PriceIndexAction.ChangeText("2"))
            saveResult = result
            model.onAction(PriceIndexAction.Save)
            advanceUntilIdle()
            assertEquals(expected, state().sheet)
        }
    }

    @Test
    fun `deleting asks first and a failure is said with a message`() = runTest(dispatcher) {
        val (model, state) = viewModel()
        model.onAction(PriceIndexAction.Edit(october))
        model.onAction(PriceIndexAction.AskDelete)
        advanceUntilIdle()
        assertEquals(october, state().confirmingDelete)

        // Atrás cierra primero el diálogo, y la hoja sigue.
        model.onAction(PriceIndexAction.Dismiss)
        advanceUntilIdle()
        assertNull(state().confirmingDelete)
        assertEquals(october, state().sheet?.month)

        deleteResult = DeletePriceIndexResult.Failed(StorageFailure.IO_ERROR)
        model.onAction(PriceIndexAction.AskDelete)
        model.onAction(PriceIndexAction.ConfirmDelete)
        advanceUntilIdle()
        assertEquals(IndexMessage.DeleteFailed(outOfSpace = false), state().message)

        model.onAction(PriceIndexAction.MessageShown)
        deleteResult = DeletePriceIndexResult.Deleted
        model.onAction(PriceIndexAction.AskDelete)
        model.onAction(PriceIndexAction.ConfirmDelete)
        advanceUntilIdle()
        assertEquals(listOf(october, october), deleted)
        assertNull(state().sheet)
        assertNull(state().message)
    }

    @Test
    fun `a new month cannot be deleted`() = runTest(dispatcher) {
        val (model, state) = viewModel()
        model.onAction(PriceIndexAction.AddMonth(september))
        model.onAction(PriceIndexAction.AskDelete)
        advanceUntilIdle()

        assertNull(state().confirmingDelete)
    }
}
