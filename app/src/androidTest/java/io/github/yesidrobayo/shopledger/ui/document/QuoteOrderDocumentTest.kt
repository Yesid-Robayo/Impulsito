package io.github.yesidrobayo.shopledger.ui.document

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.document.ReceiptAmount
import io.github.yesidrobayo.shopledger.domain.model.document.ReceiptDocument
import io.github.yesidrobayo.shopledger.domain.model.document.ReceiptHeader
import io.github.yesidrobayo.shopledger.domain.model.document.ReceiptKind
import io.github.yesidrobayo.shopledger.domain.model.document.ReceiptLine
import io.github.yesidrobayo.shopledger.domain.model.settings.ColorPalette
import io.github.yesidrobayo.shopledger.ui.theme.previewFormats
import java.io.File
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * La cotización y el pedido en papel, con el motor del comprobante: su propio título con el
 * número, la vigencia o la entrega, el envío y su leyenda al pie, sin el título ni la leyenda del
 * comprobante de venta. Datos inventados.
 */
@RunWith(AndroidJUnit4::class)
class QuoteOrderDocumentTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val resources = context.resources
    private val formats = previewFormats()
    private val renderer = ReceiptRenderer(context, formats, ColorPalette.INDIGO_MUSTARD)
    private val assets = ReceiptAssets(logo = null, units = emptyMap(), zone = null)

    private fun string(id: Int, vararg args: Any) = resources.getString(id, *args)

    private fun digits(number: Long) = formats.quantity.format(QuantityMilli.ofUnits(number), false)

    @Test
    fun aQuoteCarriesItsTitleValidityAndLegend() {
        val texts = renderer.textsOf(document(ReceiptKind.QUOTE), assets)
            .map { it.substringBefore('\t') }

        assertEquals(string(R.string.quote_doc_title, digits(41L)), texts[1])
        val validity = string(R.string.quote_doc_valid_until, formats.date.dayMonthYear(DATE))
        assertTrue(validity in texts)
        assertEquals(string(R.string.quote_doc_legend), texts.last())
        assertFalse(string(R.string.receipt_doc_title) in texts)
        assertFalse(string(R.string.legal_receipt_not_invoice) in texts)
    }

    @Test
    fun anOrderCarriesItsDeliveryShippingAndLegend() {
        val texts = renderer.textsOf(document(ReceiptKind.ORDER), assets)
            .map { it.substringBefore('\t') }

        assertEquals(string(R.string.order_doc_title, digits(58L)), texts[1])
        val delivery = string(R.string.order_delivery_row, formats.date.dayMonthYear(DATE))
        assertTrue(delivery in texts)
        assertTrue(string(R.string.order_summary_shipping) in texts)
        assertEquals(string(R.string.order_doc_legend), texts.last())
        assertFalse(string(R.string.receipt_doc_title) in texts)
    }

    @Test
    fun bothWriteAPdf() {
        for (kind in listOf(ReceiptKind.QUOTE, ReceiptKind.ORDER)) {
            val target = File(context.cacheDir, "document-$kind.pdf")
            assertTrue(renderer.writePdf(document(kind), assets, target))
            assertTrue(target.length() > 0L)
            target.delete()
        }
    }

    private fun document(kind: ReceiptKind) = ReceiptDocument(
        kind = kind,
        number = if (kind == ReceiptKind.QUOTE) 41L else 58L,
        voided = false,
        header = ReceiptHeader(null, "Tienda Central", null),
        businessDate = DATE,
        occurredAt = kotlin.time.Instant.fromEpochMilliseconds(1_793_198_520_000L),
        customerName = "Ana Ríos",
        lines = listOf(ReceiptLine("Arroz 1 kg", QuantityMilli(2_000L), 1L, Money(8_400L), false)),
        amounts = buildList {
            add(ReceiptAmount.Subtotal(Money(8_400L)))
            add(ReceiptAmount.Tax("Impuesto general", BasisPoints(1_900L), Money(1_596L), false))
            if (kind == ReceiptKind.ORDER) add(ReceiptAmount.Shipping(Money(1_500L)))
            add(ReceiptAmount.Total(Money(if (kind == ReceiptKind.ORDER) 11_496L else 9_996L)))
        },
        footerText = null,
        validUntil = DATE.takeIf { kind == ReceiptKind.QUOTE },
        dueDate = DATE.takeIf { kind == ReceiptKind.ORDER },
    )

    private companion object {
        val DATE = BusinessDate(LocalDate(2026, 11, 28))
    }
}
