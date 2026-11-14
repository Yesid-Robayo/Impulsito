package io.github.yesidrobayo.shopledger.ui.printer

import io.github.yesidrobayo.shopledger.domain.model.print.PairedPrinter
import io.github.yesidrobayo.shopledger.domain.model.print.PairedPrintersResult
import io.github.yesidrobayo.shopledger.domain.model.print.PaperWidth
import io.github.yesidrobayo.shopledger.domain.model.print.PrintFailure
import io.github.yesidrobayo.shopledger.domain.model.print.PrintResult
import io.github.yesidrobayo.shopledger.domain.model.print.PrinterSetup
import io.github.yesidrobayo.shopledger.domain.model.print.SavedPrinter
import io.github.yesidrobayo.shopledger.domain.usecase.print.PrinterSetupChange
import io.github.yesidrobayo.shopledger.domain.usecase.print.UpdatePrinterSetupResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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

/**
 * La pantalla de impresoras sin Bluetooth: agregar pide el permiso primero y luego el papel, un
 * permiso negado o un Bluetooth apagado se explican, la prueba avisa cómo terminó y un archivo que
 * no se escribe lo dice.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PrintersViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val setup = MutableStateFlow(PrinterSetup.EMPTY)
    private val changes = mutableListOf<PrinterSetupChange>()
    private var updateResult: UpdatePrinterSetupResult? = null
    private var paired: PairedPrintersResult = PairedPrintersResult.Listed(listOf(PAIRED))
    private var printResult: PrintResult = PrintResult.Sent(1_024)
    private val tested = mutableListOf<SavedPrinter>()

    @Before
    fun useTestMain() = Dispatchers.setMain(dispatcher)

    @After
    fun resetTestMain() = Dispatchers.resetMain()

    private fun TestScope.model(): PrintersViewModel {
        val model = PrintersViewModel(
            observeSetup = { setup },
            update = { change ->
                changes += change
                updateResult ?: run {
                    if (change is PrinterSetupChange.Add) {
                        setup.value = setup.value.with(change.printer)
                    }
                    UpdatePrinterSetupResult.Saved(setup.value)
                }
            },
            listPaired = { paired },
            printTest = {
                tested += it
                printResult
            },
        )
        backgroundScope.launch(dispatcher) { model.uiState.collect {} }
        advanceUntilIdle()
        return model
    }

    @Test
    fun `adding asks for the permission, then the paired printer, then its paper`() =
        runTest(dispatcher) {
            val model = model()
            assertFalse(model.uiState.value.loading)

            model.onAction(PrintersAction.Add)
            advanceUntilIdle()
            assertTrue(model.uiState.value.askingPermission)

            model.onAction(PrintersAction.PermissionResult(granted = true))
            advanceUntilIdle()
            assertEquals(PrinterSheet.Paired(listOf(PAIRED)), model.uiState.value.sheet)

            model.onAction(PrintersAction.ChoosePaired(PAIRED))
            advanceUntilIdle()
            assertEquals(PrinterSheet.Paper(PAIRED), model.uiState.value.sheet)

            model.onAction(PrintersAction.ChoosePaper(PaperWidth.MM_80))
            advanceUntilIdle()
            assertNull(model.uiState.value.sheet)
            assertEquals(SAVED.copy(paper = PaperWidth.MM_80), model.uiState.value.setup.default)
        }

    @Test
    fun `a denied permission and Bluetooth turned off are explained`() = runTest(dispatcher) {
        val model = model()

        model.onAction(PrintersAction.Add)
        model.onAction(PrintersAction.PermissionResult(granted = false))
        advanceUntilIdle()
        assertEquals(PrintFailure.PERMISSION_MISSING, model.uiState.value.blocked)
        assertFalse(model.uiState.value.askingPermission)

        paired = PairedPrintersResult.Unavailable(PrintFailure.BLUETOOTH_OFF)
        model.onAction(PrintersAction.Add)
        model.onAction(PrintersAction.PermissionResult(granted = true))
        advanceUntilIdle()
        assertEquals(PrintFailure.BLUETOOTH_OFF, model.uiState.value.blocked)
        assertNull(model.uiState.value.sheet)
    }

    @Test
    fun `a permission answer with nothing pending does nothing`() = runTest(dispatcher) {
        val model = model()

        model.onAction(PrintersAction.PermissionResult(granted = true))
        model.onAction(PrintersAction.ChoosePaper(PaperWidth.MM_58))
        advanceUntilIdle()

        assertNull(model.uiState.value.sheet)
        assertTrue(changes.isEmpty())
    }

    @Test
    fun `the test print says how it ended, one at a time`() = runTest(dispatcher) {
        setup.value = PrinterSetup(listOf(SAVED), SAVED.address)
        val model = model()

        model.onAction(PrintersAction.Test(SAVED))
        model.onAction(PrintersAction.PermissionResult(granted = true))
        advanceUntilIdle()
        assertEquals(PrinterMessage.Sent(SAVED), model.uiState.value.message)
        assertEquals(listOf(SAVED), tested)

        model.onAction(PrintersAction.MessageShown)
        printResult = PrintResult.Failed(PrintFailure.CONNECT_FAILED)
        model.onAction(PrintersAction.Test(SAVED))
        model.onAction(PrintersAction.PermissionResult(granted = true))
        advanceUntilIdle()
        assertEquals(
            PrinterMessage.Failed(SAVED, PrintFailure.CONNECT_FAILED),
            model.uiState.value.message,
        )
        assertNull(model.uiState.value.printing)
    }

    @Test
    fun `default, paper and removal go to the store, and a failed write says so`() =
        runTest(dispatcher) {
            val model = model()

            model.onAction(PrintersAction.MakeDefault(SAVED))
            model.onAction(PrintersAction.ChangePaper(SAVED, PaperWidth.MM_80))
            model.onAction(PrintersAction.Remove(SAVED))
            advanceUntilIdle()
            assertEquals(
                listOf(
                    PrinterSetupChange.MakeDefault(SAVED.address),
                    PrinterSetupChange.ChangePaper(SAVED.address, PaperWidth.MM_80),
                    PrinterSetupChange.Remove(SAVED.address),
                ),
                changes,
            )
            assertNull(model.uiState.value.message)

            updateResult = UpdatePrinterSetupResult.Failed
            model.onAction(PrintersAction.Remove(SAVED))
            advanceUntilIdle()
            assertEquals(PrinterMessage.SaveFailed, model.uiState.value.message)

            updateResult = UpdatePrinterSetupResult.Full
            model.onAction(PrintersAction.MessageShown)
            model.onAction(PrintersAction.DismissSheet)
            model.onAction(PrintersAction.MakeDefault(SAVED))
            advanceUntilIdle()
            assertEquals(PrinterMessage.Full, model.uiState.value.message)
        }

    private companion object {
        val PAIRED = PairedPrinter("Térmica de prueba", "00:11:22:33:44:55")
        val SAVED = SavedPrinter(PAIRED.address, PAIRED.name, PaperWidth.MM_58)
    }
}
