package io.github.yesidrobayo.shopledger.data.print

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.domain.model.print.PaperWidth
import io.github.yesidrobayo.shopledger.domain.model.print.PrinterSetup
import io.github.yesidrobayo.shopledger.domain.model.print.SavedPrinter
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `printers.json` en el disco del teléfono: lo guardado se vuelve a leer igual en un proceso nuevo,
 * sin temporal suelto, y un archivo dañado, enorme o con una dirección que no es de Bluetooth se
 * lee como vacío. Las impresoras son inventadas.
 */
@RunWith(AndroidJUnit4::class)
class PrinterSetupFileTest {
    private val folder =
        File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "printer-setup")
    private val file = File(folder, PrinterSetupFile.FILE_NAME)

    @Before
    fun clean() {
        folder.deleteRecursively()
    }

    @After
    fun tidy() {
        folder.deleteRecursively()
    }

    private fun store() = PrinterSetupFile({ file }, Dispatchers.IO)

    @Test
    fun withoutAFileThereAreNoPrinters() = runBlocking {
        assertEquals(PrinterSetup.EMPTY, store().observe().first())
    }

    @Test
    fun whatIsSavedComesBackTheSameInANewProcess() = runBlocking {
        val setup = PrinterSetup(listOf(FIRST, SECOND), SECOND.address)

        assertTrue(store().save(setup))

        assertEquals(setup, store().observe().first())
        assertFalse(File(folder, PrinterSetupFile.FILE_NAME + ".tmp").exists())
    }

    @Test
    fun aDamagedOrStrangeFileReadsAsEmpty() = runBlocking {
        val cases = listOf(
            "{",
            "[]",
            """{"count":1,"p0_address":"no-es-una-direccion","p0_paper":"MM_58"}""",
            """{"count":1,"p0_address":"00:11:22:33:44:55","p0_paper":"MM_99"}""",
            """{"count":2,"p0_address":"00:11:22:33:44:55","p0_paper":"MM_58"}""",
            """{"count":1,"p0_address":"00:11:22:33:44:55","p0_paper":"MM_58","otra":1}""",
            """{"count":1,"default":"00:11:22:33:44:66","p0_address":"00:11:22:33:44:55",""" +
                """"p0_paper":"MM_58"}""",
            """{"count":99}""",
        )
        folder.mkdirs()
        for (case in cases) {
            file.writeText(case)
            assertEquals(case, PrinterSetup.EMPTY, store().observe().first())
        }
        file.writeBytes(ByteArray(64 * 1_024) { ' '.code.toByte() })
        assertEquals(PrinterSetup.EMPTY, store().observe().first())
    }

    @Test
    fun aPrinterWithoutNameIsKept() = runBlocking {
        val setup = PrinterSetup(listOf(SECOND), null)

        store().save(setup)

        assertEquals(setup, store().observe().first())
    }

    private companion object {
        val FIRST = SavedPrinter("00:11:22:33:44:01", "Térmica \"de\" mostrador", PaperWidth.MM_58)
        val SECOND = SavedPrinter("00:11:22:33:44:02", null, PaperWidth.MM_80)
    }
}
