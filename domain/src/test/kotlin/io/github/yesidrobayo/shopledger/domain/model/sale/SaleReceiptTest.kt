package io.github.yesidrobayo.shopledger.domain.model.sale

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * El comprobante de una venta: nombre del negocio, fecha, líneas y total, y nada de costo, comisión
 * ni nota. Las cifras de costo y comisión son centinelas que no deben aparecer en ninguna parte.
 */
class SaleReceiptTest {
    private val detail = sampleDetail(
        lines = listOf(
            sampleLine(10L, 1L, "Jabón de avena", 2_000L, 3_500L),
            sampleLine(11L, 2L, "Crema", 1_000L, 20_000L, discount = 2_000L),
        ),
        payments = listOf(samplePayment(20L, 1L, 25_000L)),
        total = 25_000L,
    )

    @Test
    fun `the receipt has the business, the date, each line and the total`() {
        val receipt = SaleReceipt.of(detail, "  Tienda del barrio  ")

        assertEquals("Tienda del barrio", receipt.businessName)
        assertEquals(SAMPLE_DATE, receipt.businessDate)
        assertEquals(SAMPLE_AT, receipt.occurredAt)
        assertEquals(
            listOf(
                SaleReceiptLine("Jabón de avena", QuantityMilli(2_000L), 1L, Money(7_000L)),
                SaleReceiptLine("Crema", QuantityMilli(1_000L), 1L, Money(18_000L)),
            ),
            receipt.lines,
        )
        assertEquals(Money(25_000L), receipt.total)
    }

    @Test
    fun `without a business name, or with a blank one, nothing heads the receipt`() {
        assertNull(SaleReceipt.of(detail, null).businessName)
        assertNull(SaleReceipt.of(detail, "   ").businessName)
    }

    @Test
    fun `no cost, fee nor note reaches the receipt`() {
        val text = SaleReceipt.of(detail, "Tienda").toString()

        assertFalse(text.contains(SENTINEL_COST.toString()))
        assertFalse(text.contains("Nota interna"))
    }
}
