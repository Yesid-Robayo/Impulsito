package io.github.yesidrobayo.shopledger.ui.customer

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.customer.PointsSummary
import io.github.yesidrobayo.shopledger.domain.model.region.DigitGrouping
import io.github.yesidrobayo.shopledger.domain.model.region.NumberSymbols
import io.github.yesidrobayo.shopledger.domain.model.settings.LoyaltySettings
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.rule.customer.LoyaltyProgramIssue
import io.github.yesidrobayo.shopledger.domain.usecase.customer.LoyaltyProgramOverview
import io.github.yesidrobayo.shopledger.domain.usecase.customer.SaveLoyaltyProgramResult
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** El programa de puntos: abrir, escribir, guardar, apagar y fallar, con el dominio fijo. */
@OptIn(ExperimentalCoroutinesApi::class)
class LoyaltyProgramViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val program = MutableStateFlow(overview(LoyaltySettings(false, null, null)))
    private val saved = mutableListOf<LoyaltySettings>()
    private var result: SaveLoyaltyProgramResult = SaveLoyaltyProgramResult.Saved

    @Before
    fun useTestMain() = Dispatchers.setMain(dispatcher)

    @After
    fun resetTestMain() = Dispatchers.resetMain()

    private fun model() = LoyaltyProgramViewModel(
        observe = { program },
        save = { settings ->
            saved += settings
            if (result == SaveLoyaltyProgramResult.Saved) program.value = overview(settings)
            result
        },
        numbers = LoyaltyNumbers(SYMBOLS, currencyDecimals = 2),
    )

    @Test
    fun `an off program shows only the switch, and turning it on opens the form`() =
        runTest(dispatcher) {
            val model = model()
            observe(model)
            advanceUntilIdle()
            assertFalse(ready(model).form.enabled)

            model.onAction(LoyaltyProgramAction.SetEnabled(true))
            advanceUntilIdle()

            assertTrue(ready(model).form.enabled)
            assertTrue(saved.isEmpty())
        }

    @Test
    fun `saving needs both amounts and then saves them with a live example`() =
        runTest(dispatcher) {
            val model = model()
            observe(model)
            advanceUntilIdle()
            model.onAction(LoyaltyProgramAction.SetEnabled(true))
            model.onAction(LoyaltyProgramAction.Save)
            advanceUntilIdle()
            val form = ready(model).form
            assertEquals(LoyaltyAmountError.REQUIRED, form.amountPerPointError)
            assertEquals(LoyaltyAmountError.REQUIRED, form.pointValueError)
            assertTrue(saved.isEmpty())

            model.onAction(LoyaltyProgramAction.AmountPerPoint("10"))
            model.onAction(LoyaltyProgramAction.PointValue("0,05"))
            advanceUntilIdle()
            assertNull(ready(model).form.amountPerPointError)
            assertEquals(
                LoyaltyExample(Money(10_000), 10, 100, Money(500)),
                ready(model).example,
            )

            model.onAction(LoyaltyProgramAction.Save)
            advanceUntilIdle()

            assertEquals(listOf(LoyaltySettings(true, Money(1_000), Money(5))), saved)
            assertTrue(ready(model).savedMessage)
            assertTrue(ready(model).savedEnabled)
            model.onAction(LoyaltyProgramAction.MessageShown)
            advanceUntilIdle()
            assertFalse(ready(model).savedMessage)
        }

    @Test
    fun `badly written amounts say why`() = runTest(dispatcher) {
        val model = model()
        observe(model)
        advanceUntilIdle()
        model.onAction(LoyaltyProgramAction.SetEnabled(true))
        model.onAction(LoyaltyProgramAction.AmountPerPoint("uno"))
        model.onAction(LoyaltyProgramAction.PointValue("0,001"))
        model.onAction(LoyaltyProgramAction.Save)
        advanceUntilIdle()

        assertEquals(LoyaltyAmountError.NOT_A_NUMBER, ready(model).form.amountPerPointError)
        assertEquals(LoyaltyAmountError.DECIMALS, ready(model).form.pointValueError)
        model.onAction(LoyaltyProgramAction.AmountPerPoint("99999999999999"))
        model.onAction(LoyaltyProgramAction.Save)
        advanceUntilIdle()
        assertEquals(LoyaltyAmountError.TOO_HIGH, ready(model).form.amountPerPointError)
    }

    @Test
    fun `turning off asks first and keeps the amounts`() = runTest(dispatcher) {
        program.value = overview(LoyaltySettings(true, Money(1_000), Money(5)))
        val model = model()
        observe(model)
        advanceUntilIdle()
        assertEquals("10", ready(model).form.amountPerPoint)

        model.onAction(LoyaltyProgramAction.SetEnabled(false))
        advanceUntilIdle()
        assertTrue(ready(model).confirmTurnOff)
        model.onAction(LoyaltyProgramAction.DismissTurnOff)
        advanceUntilIdle()
        assertFalse(ready(model).confirmTurnOff)
        assertTrue(saved.isEmpty())

        model.onAction(LoyaltyProgramAction.SetEnabled(false))
        model.onAction(LoyaltyProgramAction.ConfirmTurnOff)
        advanceUntilIdle()

        assertEquals(listOf(LoyaltySettings(false, Money(1_000), Money(5))), saved)
        assertFalse(ready(model).form.enabled)
    }

    @Test
    fun `changing saved values says it does not touch what was earned`() = runTest(dispatcher) {
        program.value = overview(LoyaltySettings(true, Money(1_000), Money(5)))
        val model = model()
        observe(model)
        advanceUntilIdle()
        assertFalse(ready(model).valuesChanged)

        model.onAction(LoyaltyProgramAction.PointValue("0,1"))
        advanceUntilIdle()

        assertTrue(ready(model).valuesChanged)
    }

    @Test
    fun `a failed save keeps what was written and says it`() = runTest(dispatcher) {
        result = SaveLoyaltyProgramResult.StorageFailed(StorageFailure.DISK_FULL)
        val model = model()
        observe(model)
        advanceUntilIdle()
        model.onAction(LoyaltyProgramAction.SetEnabled(true))
        model.onAction(LoyaltyProgramAction.AmountPerPoint("10"))
        model.onAction(LoyaltyProgramAction.PointValue("1"))
        model.onAction(LoyaltyProgramAction.Save)
        advanceUntilIdle()

        val state = ready(model)
        assertTrue(state.saveFailed)
        assertTrue(state.outOfSpace)
        assertEquals("10", state.form.amountPerPoint)
        assertTrue(state.form.enabled)
    }

    @Test
    fun `a refused save marks the fields`() = runTest(dispatcher) {
        result = SaveLoyaltyProgramResult.Invalid(
            setOf(LoyaltyProgramIssue.POINT_VALUE_OUT_OF_RANGE),
        )
        val model = model()
        observe(model)
        advanceUntilIdle()
        model.onAction(LoyaltyProgramAction.SetEnabled(true))
        model.onAction(LoyaltyProgramAction.AmountPerPoint("10"))
        model.onAction(LoyaltyProgramAction.PointValue("1"))
        model.onAction(LoyaltyProgramAction.Save)
        advanceUntilIdle()

        assertEquals(LoyaltyAmountError.TOO_HIGH, ready(model).form.pointValueError)
        assertNull(ready(model).form.amountPerPointError)
    }

    @Test
    fun `a read failure is shown and retried`() = runTest(dispatcher) {
        val model = LoyaltyProgramViewModel(
            observe = { flow { throw IllegalStateException() } },
            save = { SaveLoyaltyProgramResult.Saved },
            numbers = LoyaltyNumbers(SYMBOLS, 2),
        )
        observe(model)
        advanceUntilIdle()

        assertEquals(LoyaltyProgramUiState.ReadFailed, model.uiState.value)
        model.onAction(LoyaltyProgramAction.Retry)
        advanceUntilIdle()
        assertEquals(LoyaltyProgramUiState.ReadFailed, model.uiState.value)
    }

    private fun TestScope.observe(model: LoyaltyProgramViewModel) {
        backgroundScope.launch(dispatcher) { model.uiState.collect {} }
    }

    private fun ready(model: LoyaltyProgramViewModel): LoyaltyProgramUiState.Ready =
        model.uiState.value as LoyaltyProgramUiState.Ready

    private fun overview(settings: LoyaltySettings) =
        LoyaltyProgramOverview(settings, PointsSummary.NONE, hasCustomers = true)

    private companion object {
        val SYMBOLS = NumberSymbols(
            decimalSeparator = ',',
            minusSign = '-',
            grouping = DigitGrouping('.', 3, 3),
        )
    }
}
