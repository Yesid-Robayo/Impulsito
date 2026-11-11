package io.github.yesidrobayo.shopledger.ui.sale

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleReceipt
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleReceiptLine
import io.github.yesidrobayo.shopledger.ui.product.SAMPLE_UNIT
import io.github.yesidrobayo.shopledger.ui.theme.previewFormats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * El comprobante como texto en el teléfono: encabezado con el negocio y la fecha, una línea por
 * producto con su cantidad y lo que cobró, el total y la leyenda de que no es una factura, que
 * siempre va. Sale de un modelo sin costos, así que las cifras de costo de la venta no aparecen.
 */
@RunWith(AndroidJUnit4::class)
class SaleReceiptTextTest {
    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
    private val formats = previewFormats()

    private val receipt = SaleReceipt(
        businessName = "Tienda del barrio",
        businessDate = DETAIL_DATE,
        occurredAt = saleDetailView().occurredAt,
        lines = listOf(
            SaleReceiptLine("Jabón de avena", QuantityMilli(2_000L), SAMPLE_UNIT.id, Money(7_000L)),
            SaleReceiptLine("Crema", QuantityMilli(1_000L), SAMPLE_UNIT.id, Money(18_000L)),
        ),
        total = Money(25_000L),
    )

    private fun text(receipt: SaleReceipt, zone: BusinessTimeZone? = ZONE) = SaleReceiptText.of(
        receipt,
        resources,
        formats,
        mapOf(SAMPLE_UNIT.id to SAMPLE_UNIT),
        zone,
    )

    @Test
    fun theReceiptHasTheBusinessTheDateEachLineTheTotalAndTheLegend() {
        val lines = text(receipt).lines()

        assertEquals("Tienda del barrio", lines[0])
        assertTrue(lines[1].startsWith(formats.date.longDate(DETAIL_DATE)))
        assertTrue(lines.any { it.contains("Jabón de avena") && it.contains("7.000") })
        assertTrue(lines.any { it.contains("Crema") && it.contains("18.000") })
        assertTrue(lines.any { it.contains("25.000") })
        assertEquals(resources.getString(R.string.legal_receipt_not_invoice), lines.last())
    }

    @Test
    fun withoutABusinessNameOrZoneTheReceiptStartsWithTheDate() {
        val lines = text(receipt.copy(businessName = null), zone = null).lines()

        assertEquals(formats.date.longDate(DETAIL_DATE), lines[0])
        assertEquals(resources.getString(R.string.legal_receipt_not_invoice), lines.last())
    }

    @Test
    fun noCostOfTheSaleReachesTheText() {
        // La venta de muestra tiene costo 17.817 y ganancia 13.183: ninguna cifra viaja.
        val shared = text(receipt)

        assertFalse(shared.contains("17.817"))
        assertFalse(shared.contains("13.183"))
        assertFalse(shared.contains("899"))
    }

    private companion object {
        val ZONE = BusinessTimeZone.of("Etc/GMT+5")
    }
}
