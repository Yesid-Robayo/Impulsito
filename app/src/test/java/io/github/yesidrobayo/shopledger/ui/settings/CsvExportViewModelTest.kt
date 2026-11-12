package io.github.yesidrobayo.shopledger.ui.settings

import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.export.CsvExport
import io.github.yesidrobayo.shopledger.domain.model.export.CsvExportProgress
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * `Exportar a CSV` sin disco ni selector: el nombre sugerido con la hora del negocio, el avance por
 * archivo, una exportación a la vez y el aviso de cada resultado, que se muestra una sola vez.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CsvExportViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val now = Instant.parse("2026-11-12T03:07:00Z")
    private val exported = mutableListOf<String>()
    private var result: CsvExport = CsvExport.Saved(files = 9, rows = 120L)
    private var gate: CompletableDeferred<Unit>? = null
    private var zone: BusinessTimeZone? = BusinessTimeZone.of("Etc/GMT+5")

    @Before
    fun useTestMain() = Dispatchers.setMain(dispatcher)

    @After
    fun resetTestMain() = Dispatchers.resetMain()

    private fun TestScope.viewModel(): CsvExportViewModel {
        val model =
            CsvExportViewModel(
                export = { uri, progress ->
                    exported += uri
                    progress(CsvExportProgress(3, 9))
                    gate?.await()
                    result
                },
                zone = { zone ?: error("Falla inventada") },
                now = { now },
                appName = "App",
                computation = dispatcher,
            )
        advanceUntilIdle()
        return model
    }

    @Test
    fun `the suggested name carries the business time`() = runTest(dispatcher) {
        assertEquals("App-export-20261111-2207.zip", viewModel().suggestedName())
    }

    @Test
    fun `without the business zone the suggested name uses utc`() = runTest(dispatcher) {
        zone = null
        assertEquals("App-export-20261112-0307.zip", viewModel().suggestedName())
    }

    @Test
    fun `while exporting the row shows its progress and ignores another document`() =
        runTest(dispatcher) {
            gate = CompletableDeferred()
            val model = viewModel()

            model.onAction(CsvExportAction.DocumentCreated("content://prueba/1"))
            advanceUntilIdle()
            assertEquals(CsvExportProgress(3, 9), model.uiState.value.progress)
            assertTrue(model.uiState.value.working)

            model.onAction(CsvExportAction.DocumentCreated("content://prueba/2"))
            advanceUntilIdle()
            assertEquals(listOf("content://prueba/1"), exported)

            requireNotNull(gate).complete(Unit)
            advanceUntilIdle()
            assertFalse(model.uiState.value.working)
            assertEquals(CsvExportMessage.SAVED, model.uiState.value.message)
        }

    @Test
    fun `each result has its message, shown once`() = runTest(dispatcher) {
        val model = viewModel()
        listOf(
            CsvExport.NoSpace to CsvExportMessage.NO_SPACE,
            CsvExport.Failed to CsvExportMessage.FAILED,
        ).forEach { (outcome, message) ->
            result = outcome
            model.onAction(
                CsvExportAction.DocumentCreated("content://prueba/${outcome.hashCode()}"),
            )
            advanceUntilIdle()
            assertEquals(message, model.uiState.value.message)
            assertNull(model.uiState.value.progress)

            model.onAction(CsvExportAction.MessageShown)
            assertNull(model.uiState.value.message)
        }
    }

    @Test
    fun `an unexpected failure is a failed export, never a crash`() = runTest(dispatcher) {
        val model =
            CsvExportViewModel(
                export = { _, _ -> error("Falla inventada") },
                zone = { null },
                now = { now },
                appName = "App",
                computation = dispatcher,
            )

        model.onAction(CsvExportAction.DocumentCreated("content://prueba/1"))
        advanceUntilIdle()

        assertEquals(CsvExportMessage.FAILED, model.uiState.value.message)
        assertFalse(model.uiState.value.working)
    }

    @Test
    fun `starting again clears the old message and an obscured touch is said`() =
        runTest(dispatcher) {
            val model = viewModel()
            model.onAction(CsvExportAction.ObscuredTouch)
            assertEquals(CsvExportMessage.OBSCURED_TOUCH, model.uiState.value.message)

            model.onAction(CsvExportAction.Export)
            assertNull(model.uiState.value.message)
            assertTrue(exported.isEmpty())
        }
}
