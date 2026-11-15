package io.github.yesidrobayo.shopledger.ui.goal

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.region.NumberSymbols
import io.github.yesidrobayo.shopledger.domain.model.report.GoalAdvance
import io.github.yesidrobayo.shopledger.domain.model.report.MonthGoals
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.usecase.report.MonthGoalView
import io.github.yesidrobayo.shopledger.domain.usecase.report.SaveGoalsResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
 * `Metas y límites` en la JVM: abre en el mes en curso, avanza hasta un año, la hoja lee los
 * montos con los decimales de la moneda, vacía quita las metas, y una falla conserva lo escrito.
 * Montos inventados.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GoalsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val symbols = NumberSymbols(decimalSeparator = ',', minusSign = '-', grouping = null)
    private val today = BusinessDate(LocalDate(2026, 11, 14))
    private val november = YearMonth(2026, 11)
    private val goals = MutableStateFlow(emptyMap<YearMonth, MonthGoals>())
    private val saved = mutableListOf<Triple<YearMonth, Money?, Money?>>()
    private var result: SaveGoalsResult = SaveGoalsResult.Saved
    private var failing = false
    private var zone: BusinessDate? = today

    @Before
    fun useTestMain() = Dispatchers.setMain(dispatcher)

    @After
    fun resetTestMain() = Dispatchers.resetMain()

    private fun TestScope.model(): GoalsViewModel {
        val model = GoalsViewModel(
            today = flowOf(zone),
            observe = { month ->
                flow {
                    if (failing) error("Falla inventada")
                    goals.map { all -> viewOf(month, all[month]) }.collect { emit(it) }
                }
            },
            save = { month, sales, profit ->
                saved += Triple(month, sales, profit)
                result
            },
            numbers = GoalNumbers(symbols, currencyDecimals = 2),
        )
        backgroundScope.launch { model.uiState.collect {} }
        advanceUntilIdle()
        return model
    }

    private fun viewOf(month: YearMonth, saved: MonthGoals?) = MonthGoalView(
        month = month,
        goals = saved,
        sales = saved?.sales?.let { GoalAdvance(Money(85_000_000), it, BasisPoints(8_500)) },
        profit = null,
    )

    private fun GoalsViewModel.ready() = uiState.value as GoalsUiState.Ready

    @Test
    fun `it opens on the month in progress without goals`() = runTest(dispatcher) {
        val model = model()

        assertEquals(november, model.ready().month)
        assertNull(model.ready().goals.goals)
        assertTrue(model.ready().canGoNext)
    }

    @Test
    fun `without a known zone it does not guess a month`() = runTest(dispatcher) {
        zone = null

        assertEquals(GoalsUiState.UnknownZone, model().uiState.value)
    }

    @Test
    fun `a read failure offers to retry`() = runTest(dispatcher) {
        failing = true
        val model = model()
        assertEquals(GoalsUiState.ReadFailed, model.uiState.value)

        failing = false
        model.onAction(GoalsAction.Retry)
        advanceUntilIdle()

        assertEquals(november, model.ready().month)
    }

    @Test
    fun `months go back freely and forward up to a year`() = runTest(dispatcher) {
        val model = model()

        model.onAction(GoalsAction.PreviousMonth)
        advanceUntilIdle()
        assertEquals(YearMonth(2026, 10), model.ready().month)

        repeat(13) {
            model.onAction(GoalsAction.NextMonth)
            advanceUntilIdle()
        }
        assertEquals(YearMonth(2027, 11), model.ready().month)
        assertFalse(model.ready().canGoNext)

        model.onAction(GoalsAction.NextMonth)
        advanceUntilIdle()
        assertEquals(YearMonth(2027, 11), model.ready().month)
    }

    @Test
    fun `the sheet opens with the saved goals and saves what was typed`() =
        runTest(dispatcher) {
            goals.value = mapOf(november to MonthGoals(november, Money(100_000_000), null))
            val model = model()

            model.onAction(GoalsAction.StartGoals)
            advanceUntilIdle()
            val sheet = requireNotNull(model.ready().sheet)
            assertTrue(sheet.editing)
            assertEquals("1000000", sheet.salesText)
            assertEquals("", sheet.profitText)

            model.onAction(GoalsAction.ProfitText("3000,5"))
            model.onAction(GoalsAction.SaveGoals)
            advanceUntilIdle()

            assertEquals(listOf(Triple(november, Money(100_000_000), Money(300_050))), saved)
            assertNull(model.ready().sheet)
            assertEquals(GoalMessage.Saved, model.ready().message)
        }

    @Test
    fun `a zero or a malformed amount stays in the sheet with its problem`() =
        runTest(dispatcher) {
            val model = model()
            model.onAction(GoalsAction.StartGoals)
            model.onAction(GoalsAction.SalesText("0"))
            model.onAction(GoalsAction.ProfitText("1,2,3"))
            model.onAction(GoalsAction.SaveGoals)
            advanceUntilIdle()

            val sheet = requireNotNull(model.ready().sheet)
            assertEquals(GoalFieldProblem.NOT_POSITIVE, sheet.salesProblem)
            assertEquals(GoalFieldProblem.NOT_A_NUMBER, sheet.profitProblem)
            assertTrue(saved.isEmpty())
        }

    @Test
    fun `both fields empty remove the goals of the month`() = runTest(dispatcher) {
        result = SaveGoalsResult.Removed
        val model = model()
        model.onAction(GoalsAction.StartGoals)
        model.onAction(GoalsAction.SaveGoals)
        advanceUntilIdle()

        assertEquals(listOf(Triple(november, null as Money?, null as Money?)), saved)
        assertEquals(GoalMessage.Removed, model.ready().message)
    }

    @Test
    fun `a failure keeps what was typed and says it`() = runTest(dispatcher) {
        result = SaveGoalsResult.Failed(StorageFailure.DISK_FULL)
        val model = model()
        model.onAction(GoalsAction.StartGoals)
        model.onAction(GoalsAction.SalesText("500"))
        model.onAction(GoalsAction.SaveGoals)
        advanceUntilIdle()

        val sheet = requireNotNull(model.ready().sheet)
        assertEquals("500", sheet.salesText)
        assertEquals(GoalSaveFailure(outOfSpace = true), sheet.saveFailed)
        assertFalse(sheet.saving)
    }
}
