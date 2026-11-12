package io.github.yesidrobayo.shopledger.ui.localcopies

import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.backup.FullCopyExport
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * `Copias locales` sin disco ni selector: el aviso de en claro o cifrada, el nombre sugerido con la
 * hora del negocio, una exportación a la vez, y cada resultado con lo que queda a la vista.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocalCopiesViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val now = Instant.parse("2026-11-12T03:07:00Z")
    private val exported = mutableListOf<String>()
    private var result: FullCopyExport = FullCopyExport.Saved(
        restorable = true,
        sizeBytes = 2_400_000,
    )
    private var encrypted: Boolean? = false
    private var gate: CompletableDeferred<Unit>? = null

    @Before
    fun useTestMain() = Dispatchers.setMain(dispatcher)

    @After
    fun resetTestMain() = Dispatchers.resetMain()

    private fun TestScope.viewModel(): LocalCopiesViewModel {
        val model =
            LocalCopiesViewModel(
                export = { uri ->
                    exported += uri
                    gate?.await()
                    result
                },
                encrypted = { encrypted ?: error("Falla inventada") },
                zone = { BusinessTimeZone.of("Etc/GMT+5") },
                now = { now },
                computation = dispatcher,
            )
        advanceUntilIdle()
        return model
    }

    @Test
    fun `in clear the warning is shown, and unknown counts as in clear`() = runTest(dispatcher) {
        assertEquals(false, viewModel().uiState.value.encrypted)

        encrypted = true
        assertEquals(true, viewModel().uiState.value.encrypted)

        encrypted = null
        assertEquals(false, viewModel().uiState.value.encrypted)
    }

    @Test
    fun `the suggested name carries the business time`() = runTest(dispatcher) {
        assertEquals("shopledger-20261111-2207.db.gz", viewModel().suggestedName())
    }

    @Test
    fun `a saved copy says so briefly`() = runTest(dispatcher) {
        val model = viewModel()

        model.onAction(LocalCopiesAction.FileCreated("content://prueba/documento/1"))
        advanceUntilIdle()

        assertEquals(listOf("content://prueba/documento/1"), exported)
        assertEquals(ExportUi.Idle, model.uiState.value.export)
        assertEquals(LocalCopiesMessage.SAVED, model.uiState.value.message)

        model.onAction(LocalCopiesAction.MessageShown)
        assertNull(model.uiState.value.message)
    }

    @Test
    fun `one export at a time`() = runTest(dispatcher) {
        gate = CompletableDeferred()
        val model = viewModel()

        model.onAction(LocalCopiesAction.FileCreated("content://prueba/documento/1"))
        advanceUntilIdle()
        assertEquals(ExportUi.Working, model.uiState.value.export)
        model.onAction(LocalCopiesAction.FileCreated("content://prueba/documento/2"))
        // Mientras trabaja, cerrar el resultado no cambia nada.
        model.onAction(LocalCopiesAction.DismissResult)
        advanceUntilIdle()
        assertEquals(ExportUi.Working, model.uiState.value.export)

        gate?.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("content://prueba/documento/1"), exported)
        assertEquals(ExportUi.Idle, model.uiState.value.export)
    }

    @Test
    fun `each failure stays in view until closed`() = runTest(dispatcher) {
        val model = viewModel()
        val cases =
            listOf(
                FullCopyExport.NoSpace to ExportUi.Failed(ExportFailure.NO_SPACE),
                FullCopyExport.Damaged to ExportUi.Failed(ExportFailure.DAMAGED),
                FullCopyExport.Failed to ExportUi.Failed(ExportFailure.OTHER),
                FullCopyExport.Saved(restorable = false, sizeBytes = 10) to
                    ExportUi.SavedNotRestorable,
            )
        for ((outcome, shown) in cases) {
            result = outcome
            model.onAction(LocalCopiesAction.FileCreated("content://prueba/documento/1"))
            advanceUntilIdle()
            assertEquals(shown, model.uiState.value.export)
            assertNull(model.uiState.value.message)

            model.onAction(LocalCopiesAction.DismissResult)
            assertEquals(ExportUi.Idle, model.uiState.value.export)
        }
    }

    @Test
    fun `an export that throws counts as failed`() = runTest(dispatcher) {
        val model =
            LocalCopiesViewModel(
                export = { error("Falla inventada") },
                encrypted = { false },
                zone = { null },
                now = { now },
                computation = dispatcher,
            )
        advanceUntilIdle()

        model.onAction(LocalCopiesAction.FileCreated("content://prueba/documento/1"))
        advanceUntilIdle()

        assertEquals(ExportUi.Failed(ExportFailure.OTHER), model.uiState.value.export)
        assertEquals("shopledger-20261112-0307.db.gz", model.suggestedName())
    }

    @Test
    fun `a tap through another app on top saves nothing and says so`() = runTest(dispatcher) {
        val model = viewModel()

        model.onAction(LocalCopiesAction.ObscuredTouch)

        assertEquals(LocalCopiesMessage.OBSCURED_TOUCH, model.uiState.value.message)
        assertEquals(emptyList<String>(), exported)
        assertEquals(ExportUi.Idle, model.uiState.value.export)
    }
}
