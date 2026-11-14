package io.github.yesidrobayo.shopledger.ui.document

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.di.ThermalReceiptPrinting
import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.print.PaperWidth
import io.github.yesidrobayo.shopledger.domain.model.print.PrintFailure
import io.github.yesidrobayo.shopledger.domain.model.print.PrintResult
import io.github.yesidrobayo.shopledger.domain.model.print.SavedPrinter
import io.github.yesidrobayo.shopledger.domain.model.settings.ColorPalette
import io.github.yesidrobayo.shopledger.domain.rule.document.SaleReceiptDocument
import io.github.yesidrobayo.shopledger.domain.testing.FakeThermalPrinter
import io.github.yesidrobayo.shopledger.domain.testing.PrintedImage
import io.github.yesidrobayo.shopledger.ui.theme.previewFormats
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * El comprobante contra la impresora falsa: el mismo motor del PDF lo dibuja al ancho exacto de
 * cada papel, en blanco y negro puros, y sale como un solo trabajo con su corte; si la impresora
 * se desconecta a mitad o le falta papel, el envío falla con su motivo y la venta no cambia. Los
 * datos son inventados; los nombres llevan `ñ`, `ç` y `ã`, que van dibujados.
 */
@RunWith(AndroidJUnit4::class)
class ReceiptPrintingDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val renderer = ReceiptRenderer(context, previewFormats(), ColorPalette.INDIGO_MUSTARD)
    private val printer = FakeThermalPrinter()
    private val printing = ThermalReceiptPrinting({ printer }, Dispatchers.Default)
    private val assets = ReceiptAssets(null, emptyMap(), BusinessTimeZone.of("UTC"))
    private val document = SaleReceiptDocument.of(
        sampleSale(
            lines = listOf(
                sampleLine(1L, "Piña con açaí y limón", 1_000L, 12_500L),
                sampleLine(2L, "Pão de queijo", 2_000L, 3_000L),
            ),
        ),
        SAMPLE_TEMPLATE,
        "Tienda Central",
        null,
        Money.ZERO,
    )

    @Test
    fun bothPaperWidthsPrintTheReceiptAtTheirExactWidthWithOneCut() = runBlocking {
        for (paper in PaperWidth.entries) {
            val saved = SavedPrinter("00:11:22:33:44:55", "Térmica de prueba", paper)

            val result = printing.printReceipt(saved, renderer, document, assets)

            assertTrue("$paper se envió", result is PrintResult.Sent)
            val printed = printer.printedImages()
            assertEquals("$paper: un solo corte", 1, printed.size)
            assertEquals("$paper: ancho exacto del papel", paper.dots, printed.single().width)
            assertTrue("$paper: con tinta", printed.single().hasInk())
            save(printed.single(), "receipt-thermal-${paper.millimeters}")
        }
    }

    @Test
    fun theThermalDrawingIsPureBlackAndWhite() {
        val bitmap = renderer.renderThermal(document, assets, PaperWidth.MM_58.dots)

        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        assertTrue(pixels.all { it == Color.BLACK || it == Color.WHITE })
        assertEquals(PaperWidth.MM_58.dots, bitmap.width)
    }

    @Test
    fun withoutPaperOrDisconnectedNothingIsRecordedAndTheReasonComesBack() = runBlocking {
        val saved = SavedPrinter("00:11:22:33:44:55", null, PaperWidth.MM_80)
        for (reason in listOf(PrintFailure.WRITE_FAILED, PrintFailure.CONNECT_FAILED)) {
            printer.failure = reason

            val result = printing.printReceipt(saved, renderer, document, assets)

            assertEquals(PrintResult.Failed(reason), result)
            assertTrue(printer.jobs.isEmpty())
        }
    }

    @Test
    fun theTestPageGoesOutAtTheWidthOfItsPaper() = runBlocking {
        val saved = SavedPrinter("00:11:22:33:44:55", null, PaperWidth.MM_58)

        val result = printing.printTest(saved, renderer, "Impresión de prueba", "ñ, ç y ã")

        assertTrue(result is PrintResult.Sent)
        assertEquals(PaperWidth.MM_58.dots, printer.printedImages().single().width)
    }

    private fun PrintedImage.hasInk(): Boolean =
        (0 until height).any { y -> (0 until width).any { x -> isBlack(x, y) } }

    private fun save(printed: PrintedImage, name: String) {
        val folder = File(context.getExternalFilesDir(null), "receipt-captures").apply { mkdirs() }
        val bitmap = Bitmap.createBitmap(printed.width, printed.height, Bitmap.Config.ARGB_8888)
        for (y in 0 until printed.height) {
            for (x in 0 until printed.width) {
                bitmap.setPixel(x, y, if (printed.isBlack(x, y)) Color.BLACK else Color.WHITE)
            }
        }
        FileOutputStream(File(folder, "$name.png")).use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
