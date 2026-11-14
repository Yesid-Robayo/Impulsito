package io.github.yesidrobayo.shopledger.domain.usecase.print

import io.github.yesidrobayo.shopledger.domain.model.print.PaperWidth
import io.github.yesidrobayo.shopledger.domain.model.print.PrinterSetup
import io.github.yesidrobayo.shopledger.domain.model.print.SavedPrinter
import io.github.yesidrobayo.shopledger.domain.port.PrinterSetupStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Las impresoras guardadas: la primera queda por defecto, guardar otra vez cambia nombre y papel en
 * su lugar, quitar la de por defecto pasa ese lugar a la siguiente, el tope deja fuera una nueva y
 * un archivo que no se escribe no cambia nada.
 */
class PrinterSetupUseCasesTest {
    private val store = MemoryStore()
    private val update = UpdatePrinterSetupUseCase(store)

    @Test
    fun `the first printer becomes the default and the next ones go after it`() = runTest {
        update(PrinterSetupChange.Add(FIRST))
        val result = update(PrinterSetupChange.Add(SECOND))

        val setup = (result as UpdatePrinterSetupResult.Saved).setup
        assertEquals(listOf(FIRST, SECOND), setup.printers)
        assertEquals(FIRST, setup.default)
        assertEquals(setup, ObservePrinterSetupUseCase(store)().first())
    }

    @Test
    fun `saving a known printer again changes it in its place`() = runTest {
        update(PrinterSetupChange.Add(FIRST))
        update(PrinterSetupChange.Add(SECOND))

        update(PrinterSetupChange.Add(FIRST.copy(name = "Caja 1", paper = PaperWidth.MM_80)))

        assertEquals(
            listOf(FIRST.copy(name = "Caja 1", paper = PaperWidth.MM_80), SECOND),
            store.state.value.printers,
        )
    }

    @Test
    fun `the default, the paper and removing one`() = runTest {
        update(PrinterSetupChange.Add(FIRST))
        update(PrinterSetupChange.Add(SECOND))

        update(PrinterSetupChange.MakeDefault(SECOND.address))
        assertEquals(SECOND, store.state.value.default)
        update(PrinterSetupChange.MakeDefault(UNKNOWN))
        assertEquals(SECOND, store.state.value.default)

        update(PrinterSetupChange.ChangePaper(SECOND.address, PaperWidth.MM_80))
        assertEquals(PaperWidth.MM_80, store.state.value.default?.paper)

        update(PrinterSetupChange.Remove(SECOND.address))
        assertEquals(FIRST, store.state.value.default)
        update(PrinterSetupChange.Remove(UNKNOWN))
        assertEquals(listOf(FIRST), store.state.value.printers)
        update(PrinterSetupChange.Remove(FIRST.address))
        assertEquals(PrinterSetup.EMPTY, store.state.value)
        assertNull(store.state.value.default)
    }

    @Test
    fun `a removed default that is not the stored one keeps the stored one`() = runTest {
        update(PrinterSetupChange.Add(FIRST))
        update(PrinterSetupChange.Add(SECOND))

        update(PrinterSetupChange.Remove(SECOND.address))

        assertEquals(FIRST.address, store.state.value.defaultAddress)
    }

    @Test
    fun `a stored default that is gone falls back to the first printer`() {
        val setup = PrinterSetup(listOf(FIRST, SECOND), defaultAddress = UNKNOWN)

        assertEquals(FIRST, setup.default)
    }

    @Test
    fun `past the limit a new printer stays out, but a known one still changes`() = runTest {
        repeat(PrinterSetup.MAX_PRINTERS) { index ->
            update(PrinterSetupChange.Add(printer(index)))
        }
        assertTrue(store.state.value.isFull)

        assertEquals(UpdatePrinterSetupResult.Full, update(PrinterSetupChange.Add(SECOND)))
        val known = printer(0).copy(paper = PaperWidth.MM_80)
        assertTrue(update(PrinterSetupChange.Add(known)) is UpdatePrinterSetupResult.Saved)
        assertEquals(PrinterSetup.MAX_PRINTERS, store.state.value.printers.size)
        assertEquals(store.state.value, store.state.value.with(SECOND))
    }

    @Test
    fun `a file that is not written changes nothing`() = runTest {
        update(PrinterSetupChange.Add(FIRST))
        store.failWrites = true

        val result = update(PrinterSetupChange.Add(SECOND))

        assertEquals(UpdatePrinterSetupResult.Failed, result)
        assertEquals(listOf(FIRST), store.state.value.printers)
    }

    private fun printer(index: Int): SavedPrinter {
        val last = (index + FIRST_GENERATED).toString(HEX).uppercase().padStart(2, '0')
        return SavedPrinter("00:11:22:33:44:$last", "Térmica $index", PaperWidth.MM_58)
    }

    private class MemoryStore : PrinterSetupStore {
        val state = MutableStateFlow(PrinterSetup.EMPTY)
        var failWrites = false

        override fun observe(): Flow<PrinterSetup> = state

        override suspend fun save(setup: PrinterSetup): Boolean {
            if (failWrites) return false
            state.value = setup
            return true
        }
    }

    private companion object {
        val FIRST = SavedPrinter("00:11:22:33:44:01", "Térmica de mostrador", PaperWidth.MM_58)
        val SECOND = SavedPrinter("00:11:22:33:44:02", null, PaperWidth.MM_58)
        const val UNKNOWN = "00:11:22:33:44:FF"
        const val FIRST_GENERATED = 0x10
        const val HEX = 16
    }
}
