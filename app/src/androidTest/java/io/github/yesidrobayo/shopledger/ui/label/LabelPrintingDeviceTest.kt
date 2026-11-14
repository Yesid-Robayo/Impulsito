package io.github.yesidrobayo.shopledger.ui.label

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.di.ThermalLabelPrinting
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.label.LabelTemplate
import io.github.yesidrobayo.shopledger.domain.model.print.PairedPrinter
import io.github.yesidrobayo.shopledger.domain.model.print.PairedPrintersResult
import io.github.yesidrobayo.shopledger.domain.model.print.PrintFailure
import io.github.yesidrobayo.shopledger.domain.model.print.PrintResult
import io.github.yesidrobayo.shopledger.domain.rule.label.LabelItem
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
import zxingcpp.BarcodeReader

/**
 * Las etiquetas de verdad contra la impresora falsa: el mismo dibujo de la vista previa pasa a
 * ESC/POS, los bytes se vuelven a leer como los leería la impresora, y la imagen que saldría en
 * papel tiene el ancho exacto de cada papel y un Code 128 que `zxing-cpp` lee sin red. Los
 * productos son inventados; los nombres llevan `ñ`, `ç` y `ã`, que van dibujados y no dependen de
 * una página de códigos.
 */
@RunWith(AndroidJUnit4::class)
class LabelPrintingDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val renderer = LabelRenderer(context, previewFormats())
    private val printer = FakeThermalPrinter()
    private val printing =
        ThermalLabelPrinting(
            listPaired = { PairedPrintersResult.Listed(listOf(PAIRED)) },
            printerFor = { printer },
            renderer = renderer,
            cpuContext = Dispatchers.Default,
        )

    @Test
    fun bothPaperWidthsPrintAReadableCodeAtTheirExactWidth() = runBlocking {
        for (size in listOf(LabelSize.MM_58, LabelSize.MM_80)) {
            for (template in listOf(LabelTemplate.NAME_PRICE_CODE, LabelTemplate.CODE_ONLY)) {
                val result = printing.printLabels(PAIRED, listOf(OWN), template, size)

                assertTrue("$size $template se envió", result is PrintResult.Sent)
                val printed = printer.printedImages().single()
                val dots = requireNotNull(size.paper).dots
                assertEquals("$size: ancho exacto del papel", dots, printed.width)
                save(printed, "label-${size.name.lowercase()}-${template.name.lowercase()}")
                assertEquals("$size $template: el código se lee", OWN.code, readCode(printed))
            }
        }
    }

    @Test
    fun theFactoryCodeIsReadableOn80mmAndOnTheSheetDrawing() = runBlocking {
        assertTrue(!codeFits(requireNotNull(FACTORY.code), LabelSize.MM_58))

        val template = LabelTemplate.NAME_PRICE_CODE
        printing.printLabels(PAIRED, listOf(FACTORY), template, LabelSize.MM_80)

        val printed = printer.printedImages().single()
        assertEquals(LabelSize.MM_80.dots, printed.width)
        assertEquals(FACTORY.code, readCode(printed))
        // La hoja usa el mismo dibujo, con su propio ancho, antes de escalarse al PDF.
        val sheet = renderer.render(FACTORY, LabelTemplate.NAME_PRICE_CODE, LabelSize.SHEET)
        assertEquals(LabelSize.SHEET_LABEL_DOTS, sheet.width)
        assertEquals(FACTORY.code, read(sheet))
    }

    @Test
    fun copiesGoInOneJobWithOneCutEachAndTheSameImage() = runBlocking {
        val labels = listOf(OWN.copy(copies = 3), NAME_ONLY)

        printing.printLabels(PAIRED, labels, LabelTemplate.NAME_PRICE, LabelSize.MM_58)

        assertEquals(1, printer.jobs.size)
        val images = printer.printedImages()
        assertEquals(4, images.size)
        assertEquals(images[0].rows(), images[1].rows())
        assertEquals(images[0].rows(), images[2].rows())
        assertTrue("otra etiqueta, otra imagen", images[0].rows() != images[3].rows())
        // Sin código: ninguna fila de barras.
        save(images[3], "label-mm_58-name_price-long-name")
    }

    @Test
    fun whatThePreviewShowsIsWhatThePrinterGets() = runBlocking {
        val preview = renderer.render(OWN, LabelTemplate.NAME_PRICE_CODE, LabelSize.MM_58)

        printing.printLabels(PAIRED, listOf(OWN), LabelTemplate.NAME_PRICE_CODE, LabelSize.MM_58)

        val printed = printer.printedImages().single()
        // El papel se redondea a bytes enteros: 384 ya es múltiplo de 8.
        assertEquals(preview.width, printed.width)
        assertEquals(preview.height, printed.height)
        for (y in 0 until preview.height) {
            for (x in 0 until preview.width) {
                val black = preview.getPixel(x, y) == Color.BLACK
                if (black != printed.isBlack(x, y)) error("Punto distinto en $x, $y")
            }
        }
    }

    @Test
    fun aDisconnectionMidwayIsReportedAndNothingIsRecorded() = runBlocking {
        printer.failure = PrintFailure.WRITE_FAILED

        val result =
            printing.printLabels(PAIRED, listOf(OWN), TEMPLATE, LabelSize.MM_58)

        assertEquals(PrintResult.Failed(PrintFailure.WRITE_FAILED), result)
        assertEquals(0, printer.jobs.size)
    }

    private fun readCode(printed: PrintedImage): String? = read(printed.toBitmap())

    private fun read(bitmap: Bitmap): String? {
        val options = BarcodeReader.Options(formats = setOf(BarcodeReader.Format.CODE_128))
        val reader = BarcodeReader(options)
        return reader.read(bitmap, Rect(0, 0, bitmap.width, bitmap.height), 0).firstOrNull()?.text
    }

    private fun PrintedImage.toBitmap(): Bitmap {
        val pixels = IntArray(width * height) { index ->
            if (isBlack(index % width, index / width)) Color.BLACK else Color.WHITE
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    /** Guarda la imagen para mirarla a mano, en los archivos de la app: se borra al desinstalar.
     */
    private fun save(printed: PrintedImage, name: String) {
        val folder = File(context.getExternalFilesDir(null), "label-captures").apply { mkdirs() }
        FileOutputStream(File(folder, "$name.png")).use {
            printed.toBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private companion object {
        val TEMPLATE = LabelTemplate.NAME_PRICE_CODE
        val PAIRED = PairedPrinter(name = "Térmica de prueba", address = "00:11:22:33:44:55")
        val OWN = LabelItem(1L, "Piña con açaí y limón", Money(12_500L), "P00000001", copies = 1)
        val FACTORY =
            LabelItem(2L, "Avena en hojuelas 500 g", Money(4_000L), "2001234567890", copies = 1)
        val NAME_ONLY = LabelItem(
            3L,
            "Jabón artesanal de avena, miel y caléndula para piel sensible, barra de 120 g",
            Money(9_900L),
            null,
            copies = 1,
        )
    }
}
