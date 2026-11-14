package io.github.yesidrobayo.shopledger.ui.document

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.annotation.RequiresApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.VoidReason
import io.github.yesidrobayo.shopledger.domain.model.Voiding
import io.github.yesidrobayo.shopledger.domain.model.document.ReceiptAmount
import io.github.yesidrobayo.shopledger.domain.model.document.ReceiptDocument
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleDetail
import io.github.yesidrobayo.shopledger.domain.model.settings.ColorPalette
import io.github.yesidrobayo.shopledger.domain.model.settings.CurrencyCode
import io.github.yesidrobayo.shopledger.domain.model.settings.ReceiptSettings
import io.github.yesidrobayo.shopledger.domain.rule.document.SaleReceiptDocument
import io.github.yesidrobayo.shopledger.format.DateFormatter
import io.github.yesidrobayo.shopledger.format.FormatLocale
import io.github.yesidrobayo.shopledger.format.MoneyFormatter
import io.github.yesidrobayo.shopledger.format.PercentFormatter
import io.github.yesidrobayo.shopledger.format.QuantityFormatter
import io.github.yesidrobayo.shopledger.ui.currency.CmapReader
import io.github.yesidrobayo.shopledger.ui.currency.CurrencySymbolChain
import io.github.yesidrobayo.shopledger.ui.format.AppFormats
import io.github.yesidrobayo.shopledger.ui.theme.previewFormats
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * El comprobante en papel, dibujado en el teléfono con las fuentes del APK: sin costo, comisión ni
 * nota aunque la venta los tenga, con la leyenda al pie siempre, en la hoja del formato regional,
 * en varias páginas si hace falta, y con cada símbolo de moneda dibujado por una fuente que lo
 * tiene, también dentro del PDF.
 */
@RunWith(AndroidJUnit4::class)
class ReceiptRendererTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val resources = context.resources
    private val formats = previewFormats()
    private val renderer = ReceiptRenderer(context, formats, ColorPalette.INDIGO_MUSTARD)
    private val assets = ReceiptAssets(logo = null, units = emptyMap(), zone = ZONE)
    private val folder = File(context.getExternalFilesDir(null), "receipt-captures")
        .apply { mkdirs() }

    @Test
    fun noCostFeeNorNoteReachesThePaperAndTheyChangeNoPixel() {
        val withSentinels = documentOf(sampleSale())
        val withoutThem = documentOf(sampleSale(cost = 0L, fee = 0L, note = null))

        val texts = renderer.textsOf(withSentinels, assets).joinToString("\n")
        assertFalse(texts.contains(formats.money.parts(Money(SENTINEL_COST)).number))
        assertFalse(texts.contains(SENTINEL_NOTE))
        val image = renderer.renderImage(withSentinels, assets)
        assertTrue(image.sameAs(renderer.renderImage(withoutThem, assets)))
        save(image, "receipt-sale")
    }

    @Test
    fun theDocumentReadsInPaperOrderAndTheLegendAlwaysCloses() {
        val texts = renderer.textsOf(documentOf(sampleSale()), assets)
        val labels = texts.map { it.substringBefore('\t') }

        assertEquals(
            listOf(
                "Tienda Central",
                "Calle Mayor 8, local 2",
                string(R.string.receipt_doc_title),
                resources.getString(R.string.receipt_doc_sale_number, saleNumber()),
            ),
            labels.take(4),
        )
        val amounts = labels.dropWhile { it != string(R.string.receipt_doc_subtotal) }
        assertEquals(
            listOf(
                string(R.string.receipt_doc_subtotal),
                "Impuesto general",
                string(R.string.receipt_doc_total),
                "Efectivo",
                "A crédito",
                string(R.string.receipt_doc_change),
                string(R.string.receipt_doc_balance_due),
                "Gracias por venir",
                string(R.string.legal_receipt_not_invoice),
            ),
            amounts,
        )
        // Con la plantilla vacía, sin nombre y sin cliente, la leyenda sigue cerrando el papel.
        val bare = SaleReceiptDocument.of(sampleSale(), EMPTY_TEMPLATE, null, null, Money.ZERO)
        val bareTexts = renderer.textsOf(bare, assets)
        assertEquals(string(R.string.receipt_doc_title), bareTexts.first())
        assertEquals(string(R.string.legal_receipt_not_invoice), bareTexts.last())
    }

    /**
     * Con 3.000 a favor antes de la venta, los 5.000 a crédito del ejemplo se parten: 3.000 de
     * saldo a favor aplicado y 2.000 pendientes, cada uno en su fila y en ese orden.
     */
    @Test
    fun theCreditBalanceAppliedHasItsOwnRowBeforeWhatIsStillDue() {
        val document = SaleReceiptDocument.of(
            sampleSale(),
            SAMPLE_TEMPLATE,
            "Tienda Central",
            "Cliente de prueba",
            Money(3_000L),
        )
        val labels = renderer.textsOf(document, assets).map { it.substringBefore('\t') }

        val applied = labels.indexOf(string(R.string.receipt_doc_credit_applied))
        assertTrue(applied >= 0)
        assertEquals(string(R.string.receipt_doc_balance_due), labels[applied + 1])
        save(renderer.renderImage(document, assets), "receipt-credit-applied")
    }

    @Test
    fun anExtraGoesUnderItsProductAndAVoidedSaleSaysSo() {
        val sale = sampleSale()
        val voided = sale.copy(
            sale = sale.sale.copy(
                voiding = Voiding(SAMPLE_AT, VoidReason.ENTRY_ERROR, null),
            ),
        )
        val texts = renderer.textsOf(documentOf(voided), assets)

        val number = resources.getString(R.string.receipt_doc_sale_number, saleNumber())
        val status = string(R.string.receipt_doc_voided)
        val heading = resources.getString(R.string.receipt_doc_number_status, number, status)
        assertTrue(texts.contains(heading))
        save(renderer.renderImage(documentOf(voided), assets), "receipt-voided")
    }

    @Test
    fun thePdfUsesThePaperOfTheRegionalFormatAndTheImageIsTwiceTheColumn() {
        val document = documentOf(sampleSale())

        assertEquals(PaperSize.A4, pageSizeOf(renderer, document, "a4"))
        val letter = ReceiptRenderer(context, formatsFor("en-US", "$"), ColorPalette.FOREST_HONEY)
        // Android 8 no expone la hoja de ICU: ahí todo sale en A4.
        val expected = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PaperSize(612, 791)
        } else {
            PaperSize.A4
        }
        assertEquals(expected, pageSizeOf(letter, document, "letter"))
        assertEquals(816, renderer.renderImage(document, assets).width)
    }

    /**
     * Los montos del PDF se pueden copiar y buscar: el texto que el sistema saca de la página trae
     * el total como se escribe. Solo Android 15 o más nuevo sabe sacar el texto de un PDF.
     */
    @Test
    fun theAmountsOfThePdfCanBeCopiedAndSearched() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM)
        val document = documentOf(sampleSale())
        val file = File(folder, "receipt-text.pdf")
        assertTrue(renderer.writePdf(document, assets, file))

        val total = document.amounts.filterIsInstance<ReceiptAmount.Total>().single().amount
        val digits = formats.money.parts(total).number.filterNot { it.isWhitespace() }
        val text = pdfText(file).filterNot { it.isWhitespace() }
        assertTrue("$digits en $text", text.contains(digits))
    }

    @Test
    fun aLongSaleContinuesOnMorePages() {
        val lines = (1L..200L).map {
            sampleLine(it, "Producto de prueba número $it", 1_000L, 1_000L)
        }
        val file = File(folder, "receipt-long.pdf")

        assertTrue(renderer.writePdf(documentOf(sampleSale(lines = lines)), assets, file))
        assertTrue(pageCount(file) > 1)
    }

    /**
     * Los símbolos de moneda de la prueba de símbolos, en el PDF: cada tramo del total va con una
     * fuente que tiene sus caracteres, o el símbolo entero cambia por el código ISO. Deja una hoja
     * con todos los totales dibujados desde el PDF, para mirarla.
     */
    @Test
    fun everyCurrencySymbolIsDrawnInThePdfByAFontThatHasIt() {
        val figtree = ownCodePoints(R.font.figtree_variable)
        val currency = ownCodePoints(R.font.currency_symbols_600)
        SYMBOLS.chunked(SHEET_CELLS).forEachIndexed { sheet, symbols ->
            val crops = symbols.map { symbol -> symbolBand(symbol, figtree, currency) }
            save(sheetOf(crops), "receipt-symbols-${sheet + 1}")
            crops.forEach(Bitmap::recycle)
        }
    }

    /** Comprueba las fuentes del total con [symbol] y devuelve su franja dibujada desde el PDF. */
    private fun symbolBand(symbol: String, figtree: Set<Int>, currency: Set<Int>): Bitmap {
        val formats = formatsFor("en", symbol)
        val runs = DocumentMoney(resources, formats).runsOf(Money(123_456L))
        for (run in runs) {
            for (codePoint in CurrencySymbolChain.codePointsOf(run.text)) {
                val covered = when (run.font) {
                    DocumentFont.APP -> codePoint in figtree || codePoint == NO_BREAK_SPACE

                    DocumentFont.CURRENCY -> codePoint in currency

                    DocumentFont.SYSTEM ->
                        Paint().hasGlyph(String(Character.toChars(codePoint)))
                }
                assertTrue("${label(symbol)} en ${run.font}", covered)
            }
        }
        val document = SaleReceiptDocument.of(
            sampleSale(lines = listOf(sampleLine(1L, "Arroz", 1_000L, 123_456L))),
            EMPTY_TEMPLATE,
            null,
            null,
            Money.ZERO,
        )
        val file = File(folder, "symbol.pdf")
        assertTrue(ReceiptRenderer(context, formats, PALETTE).writePdf(document, assets, file))
        return firstPageBand(file)
    }

    private fun documentOf(sale: SaleDetail) = SaleReceiptDocument.of(
        sale,
        SAMPLE_TEMPLATE,
        "Tienda Central",
        "Cliente de prueba",
        Money.ZERO,
    )

    private fun pageSizeOf(
        renderer: ReceiptRenderer,
        document: ReceiptDocument,
        name: String,
    ): PaperSize {
        val file = File(folder, "receipt-$name.pdf")
        assertTrue(renderer.writePdf(document, assets, file))
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { pdf ->
                pdf.openPage(0).use { PaperSize(it.width, it.height) }
            }
        }
    }

    /** El texto que el sistema saca de la primera página del PDF. */
    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private fun pdfText(file: File): String =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { pdf ->
                pdf.openPage(0).use { page -> page.textContents.joinToString(" ") { it.text } }
            }
        }

    private fun pageCount(file: File): Int =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { it.pageCount }
        }

    /** La franja de arriba de la primera página, donde van las cifras, al doble. */
    private fun firstPageBand(file: File): Bitmap =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { pdf ->
                pdf.openPage(0).use { page ->
                    val full = Bitmap.createBitmap(
                        page.width * 2,
                        page.height * 2,
                        Bitmap.Config.ARGB_8888,
                    )
                    full.eraseColor(Color.WHITE)
                    page.render(full, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                    // La columna va centrada: al doble, su borde izquierdo cae en el ancho de la
                    // hoja menos el de la columna.
                    val band = Bitmap.createBitmap(
                        full,
                        page.width - COLUMN,
                        BAND_TOP * 2,
                        COLUMN * 2,
                        BAND_HEIGHT,
                    )
                    full.recycle()
                    band
                }
            }
        }

    private fun sheetOf(crops: List<Bitmap>): Bitmap {
        val columns = SHEET_COLUMNS
        val rows = (crops.size + columns - 1) / columns
        val width = crops.first().width
        val sheet =
            Bitmap.createBitmap(width * columns, BAND_HEIGHT * rows, Bitmap.Config.ARGB_8888)
        sheet.eraseColor(Color.WHITE)
        val canvas = Canvas(sheet)
        crops.forEachIndexed { index, crop ->
            val x = (index % columns) * width
            val y = (index / columns) * BAND_HEIGHT
            canvas.drawBitmap(crop, null, Rect(x, y, x + width, y + BAND_HEIGHT), null)
        }
        return sheet
    }

    private fun formatsFor(tag: String, symbol: String): AppFormats {
        val formatLocale = requireNotNull(FormatLocale.of(tag))
        return AppFormats(
            money = MoneyFormatter(
                formatLocale = formatLocale,
                currencyCode = requireNotNull(CurrencyCode.of("XTS")),
                currencyDecimals = 2,
                currencySymbol = symbol,
                uiLocale = Locale.ENGLISH,
            ),
            quantity = QuantityFormatter(formatLocale),
            percent = PercentFormatter(formatLocale),
            date = DateFormatter(formatLocale, Locale.ENGLISH),
        )
    }

    // openRawResource lee cualquier recurso que sea un archivo, y los .ttf de res/font lo son.
    @SuppressLint("ResourceType")
    private fun ownCodePoints(fontId: Int): Set<Int> =
        CmapReader.readCodePoints(resources.openRawResource(fontId).use { it.readBytes() })

    private fun string(id: Int): String = resources.getString(id)

    private fun saleNumber(): String = formats.quantity.format(QuantityMilli.ofUnits(1_204L), false)

    private fun label(symbol: String): String =
        CurrencySymbolChain.codePointsOf(symbol).joinToString(" ") {
            "U+" + Integer.toHexString(it).uppercase().padStart(4, '0')
        }

    private fun save(bitmap: Bitmap, name: String) {
        FileOutputStream(File(folder, "$name.png")).use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private companion object {
        val ZONE = requireNotNull(BusinessTimeZone.of("UTC"))
        val PALETTE = ColorPalette.INDIGO_MUSTARD
        val EMPTY_TEMPLATE = ReceiptSettings(null, null, null, null)
        const val NO_BREAK_SPACE = 0x00A0
        const val BAND_HEIGHT = 520
        const val SHEET_COLUMNS = 3
        const val SHEET_CELLS = 12
        const val COLUMN = 360
        const val BAND_TOP = 96

        /** Los signos de moneda del plano básico y tres símbolos escritos a mano. */
        val SYMBOLS: List<String> =
            (
                listOf(0x0024, 0x00A2, 0x00A3, 0x00A4, 0x00A5, 0x058F, 0x060B, 0x07FE, 0x07FF) +
                    listOf(0x09F2, 0x09F3, 0x09FB, 0x0AF1, 0x0BF9, 0x0E3F, 0x17DB) +
                    (0x20A0..0x20C0).toList() +
                    listOf(0xA838, 0xFDFC, 0xFE69, 0xFF04, 0xFFE0, 0xFFE1, 0xFFE5, 0xFFE6)
                ).map { String(Character.toChars(it)) } + listOf("XY₵", "Дж", "بج")
    }
}
