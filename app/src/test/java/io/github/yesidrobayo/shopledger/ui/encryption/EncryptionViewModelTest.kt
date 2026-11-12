package io.github.yesidrobayo.shopledger.ui.encryption

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.backup.ActivationOutcome
import io.github.yesidrobayo.shopledger.domain.model.backup.EncryptionStatus
import io.github.yesidrobayo.shopledger.domain.model.backup.RecoveryCodeDraft
import io.github.yesidrobayo.shopledger.domain.model.backup.RecoveryCodeView
import io.github.yesidrobayo.shopledger.domain.model.backup.ResealOutcome
import io.github.yesidrobayo.shopledger.domain.model.backup.TypedCodeCheck
import io.github.yesidrobayo.shopledger.domain.port.EncryptionPort
import io.github.yesidrobayo.shopledger.domain.usecase.backup.EncryptionUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * La activación del cifrado sin disco ni Keystore: cada paso exige lo suyo, un error de escritura
 * marca su grupo sin borrar nada, la prueba fallida o el sello fallido dejan el cifrado apagado, y
 * salir descarta el código. El código es el sintético de la especificación.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EncryptionViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val groups = listOf("G7K2", "M9PQ", "R4ST", "V6WX", "Y8Z3", "ABCD", "EF51")
    private val port = FakePort()
    private val kits = mutableListOf<Pair<String, BusinessDate>>()
    private var kitWritten = true

    @Before
    fun useTestMain() = Dispatchers.setMain(dispatcher)

    @After
    fun resetTestMain() = Dispatchers.resetMain()

    private fun TestScope.viewModel(): EncryptionViewModel {
        val model =
            EncryptionViewModel(
                port = EncryptionUseCase(port),
                writeKit = { uri, written, date ->
                    assertEquals(groups, written)
                    kits += uri to date
                    kitWritten
                },
                today = { BusinessDate(LocalDate(2026, 11, 12)) },
                computation = dispatcher,
            )
        advanceUntilIdle()
        return model
    }

    private fun TestScope.toRetype(model: EncryptionViewModel) {
        model.onAction(EncryptionAction.Start)
        model.onAction(EncryptionAction.Accept(true))
        model.onAction(EncryptionAction.ContinueRisk)
        advanceUntilIdle()
        model.onAction(EncryptionAction.OnPaper)
        model.onAction(EncryptionAction.ContinueCode)
    }

    @Test
    fun `it starts off and shows the status`() = runTest(dispatcher) {
        val model = viewModel()
        assertEquals(EncryptionStatusUi.Off(wasOn = false), model.uiState.value.status)
        assertNull(model.uiState.value.step)
    }

    @Test
    fun `the risk must be accepted before a code exists`() = runTest(dispatcher) {
        val model = viewModel()
        model.onAction(EncryptionAction.Start)
        model.onAction(EncryptionAction.ContinueRisk)
        assertEquals(ActivationStepUi.Risk(false), model.uiState.value.step)
        assertEquals(0, port.codesCreated)
        model.onAction(EncryptionAction.Accept(true))
        model.onAction(EncryptionAction.ContinueRisk)
        val step = model.uiState.value.step as ActivationStepUi.ShowCode
        assertEquals(groups, step.groups)
        assertEquals(1, port.codesCreated)
        assertFalse(step.toString().contains("G7K2"))
    }

    @Test
    fun `the code step needs the kit saved or the paper`() = runTest(dispatcher) {
        val model = viewModel()
        model.onAction(EncryptionAction.Start)
        model.onAction(EncryptionAction.Accept(true))
        model.onAction(EncryptionAction.ContinueRisk)
        advanceUntilIdle()
        model.onAction(EncryptionAction.ContinueCode)
        assertTrue(model.uiState.value.step is ActivationStepUi.ShowCode)

        kitWritten = false
        model.onAction(EncryptionAction.SaveKit)
        assertEquals("recovery-kit-20261112.pdf", model.kitFileName())
        model.onAction(EncryptionAction.KitFileCreated("content://kit"))
        advanceUntilIdle()
        assertEquals(KitState.FAILED, (model.uiState.value.step as ActivationStepUi.ShowCode).kit)
        model.onAction(EncryptionAction.ContinueCode)
        assertTrue(model.uiState.value.step is ActivationStepUi.ShowCode)

        kitWritten = true
        model.onAction(EncryptionAction.SaveKit)
        model.kitCancelled()
        assertEquals(KitState.NONE, (model.uiState.value.step as ActivationStepUi.ShowCode).kit)
        model.onAction(EncryptionAction.KitFileCreated("content://kit2"))
        advanceUntilIdle()
        assertEquals(KitState.SAVED, (model.uiState.value.step as ActivationStepUi.ShowCode).kit)
        assertEquals(listOf("content://kit", "content://kit2"), kits.map { it.first })
        model.onAction(EncryptionAction.ContinueCode)
        assertTrue(model.uiState.value.step is ActivationStepUi.Retype)
    }

    @Test
    fun `a typo marks its group and keeps what was typed`() = runTest(dispatcher) {
        val model = viewModel()
        toRetype(model)
        val typed = groups.toMutableList().also { it[3] = "V6WY" }
        model.onAction(EncryptionAction.Typed(typed))
        model.onAction(EncryptionAction.CheckCode)
        val step = model.uiState.value.step as ActivationStepUi.Retype
        assertEquals(3, step.errorGroup)
        assertEquals(typed, step.typed)
        assertEquals(0, port.activations)

        model.onAction(EncryptionAction.Typed(groups.dropLast(1) + "EF"))
        model.onAction(EncryptionAction.CheckCode)
        assertEquals(6, (model.uiState.value.step as ActivationStepUi.Retype).errorGroup)
    }

    @Test
    fun `a failed round trip leaves encryption off and can be retried`() = runTest(dispatcher) {
        val model = viewModel()
        toRetype(model)
        port.roundTripPasses = false
        model.onAction(EncryptionAction.Typed(groups))
        model.onAction(EncryptionAction.CheckCode)
        advanceUntilIdle()
        assertEquals(
            ActivationStepUi.Testing(ActivationFailure.ROUND_TRIP),
            model.uiState.value.step,
        )
        assertEquals(0, port.activations)

        port.roundTripPasses = true
        model.onAction(EncryptionAction.Retry)
        advanceUntilIdle()
        assertEquals(ActivationStepUi.Done(), model.uiState.value.step)
        assertEquals(1, port.activations)
        assertEquals(EncryptionStatusUi.On, model.uiState.value.status)
    }

    @Test
    fun `a failed seal says so and nothing is on`() = runTest(dispatcher) {
        val model = viewModel()
        toRetype(model)
        port.outcome = ActivationOutcome.SAVE_FAILED
        model.onAction(EncryptionAction.Typed(groups))
        model.onAction(EncryptionAction.CheckCode)
        advanceUntilIdle()
        assertEquals(ActivationStepUi.Testing(ActivationFailure.SAVE), model.uiState.value.step)
        model.onAction(EncryptionAction.Exit)
        advanceUntilIdle()
        assertNull(model.uiState.value.step)
        assertEquals(EncryptionStatusUi.Off(wasOn = false), model.uiState.value.status)
    }

    @Test
    fun `leaving the activation discards the code`() = runTest(dispatcher) {
        val model = viewModel()
        toRetype(model)
        model.onAction(EncryptionAction.Exit)
        advanceUntilIdle()
        assertNull(model.uiState.value.step)
        // Sin código, comprobar o activar no hace nada.
        model.onAction(EncryptionAction.CheckCode)
        model.onAction(EncryptionAction.Retry)
        advanceUntilIdle()
        assertEquals(0, port.activations)
        assertNull(model.kitFileName())
    }

    @Test
    fun `with encryption already on or unreadable it does not start again`() = runTest(dispatcher) {
        port.status = EncryptionStatus.On(1L)
        val on = viewModel()
        on.onAction(EncryptionAction.Start)
        assertNull(on.uiState.value.step)
        port.status = EncryptionStatus.KeyUnavailable
        assertEquals(EncryptionStatusUi.KeyUnavailable, viewModel().uiState.value.status)
        port.failStatus = true
        val unknown = viewModel()
        assertEquals(EncryptionStatusUi.Unknown, unknown.uiState.value.status)
        unknown.onAction(EncryptionAction.Start)
        assertNull(unknown.uiState.value.step)
    }

    @Test
    fun `off on purpose says that encrypted copies remain`() = runTest(dispatcher) {
        port.status = EncryptionStatus.Off(wasOn = true)
        assertEquals(EncryptionStatusUi.Off(wasOn = true), viewModel().uiState.value.status)
    }

    @Test
    fun `changing the code confirms first and repeats the code steps`() = runTest(dispatcher) {
        port.status = EncryptionStatus.On(1L)
        val model = viewModel()
        model.onAction(EncryptionAction.AskChange)
        assertTrue(model.uiState.value.confirmChange)
        model.onAction(EncryptionAction.CloseChange)
        assertFalse(model.uiState.value.confirmChange)
        assertEquals(0, port.codesCreated)

        model.onAction(EncryptionAction.AskChange)
        model.onAction(EncryptionAction.ConfirmChange)
        advanceUntilIdle()
        assertFalse(model.uiState.value.confirmChange)
        assertTrue(model.uiState.value.step is ActivationStepUi.ShowCode)
        model.onAction(EncryptionAction.OnPaper)
        model.onAction(EncryptionAction.ContinueCode)
        model.onAction(EncryptionAction.Typed(groups))
        model.onAction(EncryptionAction.CheckCode)
        advanceUntilIdle()
        assertEquals(ActivationStepUi.Done(changed = true), model.uiState.value.step)
        assertEquals(1, port.activations)
    }

    @Test
    fun `with the code unavailable another one can be created without the old one`() =
        runTest(dispatcher) {
            port.status = EncryptionStatus.KeyUnavailable
            val model = viewModel()
            model.onAction(EncryptionAction.AskChange)
            assertFalse(model.uiState.value.confirmChange)
            model.onAction(EncryptionAction.CreateNewCode)
            assertTrue(model.uiState.value.step is ActivationStepUi.ShowCode)
        }

    @Test
    fun `typing the code again reseals it and a mismatch keeps what was typed`() =
        runTest(dispatcher) {
            port.status = EncryptionStatus.KeyUnavailable
            val model = viewModel()
            model.onAction(EncryptionAction.TypeExistingCode)
            val typed = groups.toMutableList().also { it[0] = "G7K3" }
            model.onAction(EncryptionAction.TypedExisting(typed))
            port.reseal = ResealOutcome.TYPO
            model.onAction(EncryptionAction.Reseal)
            advanceUntilIdle()
            var step = model.uiState.value.step as ActivationStepUi.TypeExisting
            assertEquals(ResealProblem.MISMATCH, step.problem)
            assertEquals(typed, step.typed)

            port.reseal = ResealOutcome.SAVE_FAILED
            model.onAction(EncryptionAction.Reseal)
            advanceUntilIdle()
            step = model.uiState.value.step as ActivationStepUi.TypeExisting
            assertEquals(ResealProblem.SAVE, step.problem)

            port.reseal = ResealOutcome.SEALED
            port.status = EncryptionStatus.On(3L)
            model.onAction(EncryptionAction.Reseal)
            advanceUntilIdle()
            assertNull(model.uiState.value.step)
            assertEquals(EncryptionStatusUi.On, model.uiState.value.status)
            assertEquals(3, port.reseals)
        }

    @Test
    fun `nothing is resealed or changed with encryption off`() = runTest(dispatcher) {
        val model = viewModel()
        model.onAction(EncryptionAction.TypeExistingCode)
        model.onAction(EncryptionAction.CreateNewCode)
        model.onAction(EncryptionAction.ConfirmChange)
        assertNull(model.uiState.value.step)
        assertEquals(0, port.codesCreated)
    }

    private inner class FakePort : EncryptionPort {
        var status: EncryptionStatus = EncryptionStatus.Off(wasOn = false)
        var failStatus = false
        var roundTripPasses = true
        var outcome = ActivationOutcome.ACTIVATED
        var codesCreated = 0
        var activations = 0
        var reseal = ResealOutcome.SEALED
        var reseals = 0

        override suspend fun status(): EncryptionStatus {
            if (failStatus) error("Falla inventada")
            return status
        }

        override fun newCode(): RecoveryCodeDraft {
            codesCreated++
            return RecoveryCodeDraft(groups)
        }

        override fun checkTyped(draft: RecoveryCodeDraft, typed: String): TypedCodeCheck {
            val expected = draft.groups.joinToString("")
            if (typed.length != expected.length) return TypedCodeCheck.Incomplete
            val group =
                (0 until 7).firstOrNull {
                    typed.substring(it * 4, it * 4 + 4) != expected.substring(it * 4, it * 4 + 4)
                } ?: return TypedCodeCheck.Matches
            return TypedCodeCheck.GroupMismatch(group + 1)
        }

        override suspend fun roundTrip(draft: RecoveryCodeDraft): Boolean = roundTripPasses

        override suspend fun reseal(typed: String): ResealOutcome {
            reseals++
            return reseal
        }

        override suspend fun activate(draft: RecoveryCodeDraft): ActivationOutcome {
            activations++
            if (outcome == ActivationOutcome.ACTIVATED) status = EncryptionStatus.On(2L)
            return outcome
        }

        var view: RecoveryCodeView = RecoveryCodeView.Shown(RecoveryCodeDraft(groups))
        var failView = false
        var views = 0

        override suspend fun viewCode(): RecoveryCodeView {
            views++
            if (failView) error("Falla inventada")
            return view
        }
    }

    @Test
    fun `the code is shown only after the phone check and closing drops it`() =
        runTest(dispatcher) {
            port.status = EncryptionStatus.On(2L)
            val model = viewModel()
            model.onAction(EncryptionAction.ViewCode)
            advanceUntilIdle()
            assertNull(model.uiState.value.codeView)
            assertEquals(0, port.views)

            model.onAction(EncryptionAction.ViewCodeAuthorized)
            assertEquals(CodeViewUi.Opening, model.uiState.value.codeView)
            advanceUntilIdle()
            val shown = model.uiState.value.codeView as CodeViewUi.Shown
            assertEquals(groups, shown.groups)
            assertFalse(model.uiState.value.toString().contains("G7K2"))

            model.onAction(EncryptionAction.CloseCode)
            assertNull(model.uiState.value.codeView)
            assertEquals(EncryptionStatusUi.On, model.uiState.value.status)
        }

    @Test
    fun `without a screen lock it asks to confirm and can be dismissed`() = runTest(dispatcher) {
        port.status = EncryptionStatus.On(2L)
        val model = viewModel()
        model.onAction(EncryptionAction.ViewCodeWithoutScreenLock)
        assertTrue(model.uiState.value.confirmViewWithoutScreenLock)
        model.onAction(EncryptionAction.DismissViewWithoutScreenLock)
        assertFalse(model.uiState.value.confirmViewWithoutScreenLock)
        assertEquals(0, port.views)

        model.onAction(EncryptionAction.ViewCodeWithoutScreenLock)
        model.onAction(EncryptionAction.ViewCodeAuthorized)
        advanceUntilIdle()
        assertFalse(model.uiState.value.confirmViewWithoutScreenLock)
        assertTrue(model.uiState.value.codeView is CodeViewUi.Shown)
    }

    @Test
    fun `a code that does not open or a failure shows that it is unavailable`() =
        runTest(dispatcher) {
            port.status = EncryptionStatus.On(2L)
            port.view = RecoveryCodeView.Unavailable
            val model = viewModel()
            model.onAction(EncryptionAction.ViewCodeAuthorized)
            advanceUntilIdle()
            assertEquals(CodeViewUi.Unavailable, model.uiState.value.codeView)

            model.onAction(EncryptionAction.CloseCode)
            port.failView = true
            model.onAction(EncryptionAction.ViewCodeAuthorized)
            advanceUntilIdle()
            assertEquals(CodeViewUi.Unavailable, model.uiState.value.codeView)
        }

    @Test
    fun `closing before the code opens leaves nothing on screen`() = runTest(dispatcher) {
        port.status = EncryptionStatus.On(2L)
        val model = viewModel()
        model.onAction(EncryptionAction.ViewCodeAuthorized)
        model.onAction(EncryptionAction.CloseCode)
        advanceUntilIdle()
        assertNull(model.uiState.value.codeView)
    }

    @Test
    fun `viewing is ignored in the middle of an activation`() = runTest(dispatcher) {
        val model = viewModel()
        toRetype(model)
        model.onAction(EncryptionAction.ViewCodeAuthorized)
        advanceUntilIdle()
        assertNull(model.uiState.value.codeView)
        assertEquals(0, port.views)
    }
}
