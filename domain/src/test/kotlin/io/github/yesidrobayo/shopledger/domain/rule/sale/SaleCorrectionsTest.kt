package io.github.yesidrobayo.shopledger.domain.rule.sale

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.sale.LineDiscount
import io.github.yesidrobayo.shopledger.domain.model.sale.SAMPLE_DATE
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.sampleDetail
import io.github.yesidrobayo.shopledger.domain.model.sale.sampleLine
import io.github.yesidrobayo.shopledger.domain.model.sale.samplePayment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Lo que trae la venta nueva al anular y corregir: líneas con precio y descuento, el método, la
 * fecha y la nota; sin los productos hoy archivados, que se cuentan. Datos inventados.
 */
class SaleCorrectionsTest {
    @Test
    fun `the correction brings lines, prices, discounts, method, date and note`() {
        val detail = sampleDetail(
            lines = listOf(
                sampleLine(10L, 1L, "Jabón de avena", 2_000L, 3_000L),
                sampleLine(11L, 2L, "Crema", 1_000L, 20_000L, discount = 2_000L),
            ),
            payments = listOf(samplePayment(20L, 5L, 24_000L)),
            total = 24_000L,
        )

        val correction = SaleCorrections.of(detail)

        assertEquals(
            listOf(
                SaleLineRequest(1L, QuantityMilli(2_000L), Money(3_000L), LineDiscount.None),
                SaleLineRequest(
                    2L,
                    QuantityMilli(1_000L),
                    Money(20_000L),
                    LineDiscount.Amount(Money(2_000L)),
                ),
            ),
            correction.lines,
        )
        assertEquals(5L, correction.paymentMethodId)
        assertEquals(SAMPLE_DATE, correction.date)
        assertEquals("Nota interna", correction.note)
        assertEquals(0, correction.leftOut)
    }

    @Test
    fun `a line with an archived product is left out and counted`() {
        val detail = sampleDetail(
            lines = listOf(
                sampleLine(10L, 1L, "Jabón de avena", 1_000L, 3_000L),
                sampleLine(11L, 2L, "Crema", 1_000L, 20_000L),
                sampleLine(12L, 3L, "Café", 1_000L, 6_000L),
            ),
            payments = listOf(samplePayment(20L, 5L, 29_000L)),
            total = 29_000L,
            archived = setOf(2L, 3L),
        )

        val correction = SaleCorrections.of(detail)

        assertEquals(listOf(1L), correction.lines.map { it.productId })
        assertEquals(2, correction.leftOut)
    }

    @Test
    fun `a product in two lines arrives as one, and extras do not come`() {
        val detail = sampleDetail(
            lines = listOf(
                sampleLine(10L, 1L, "Jabón", 2_000L, 3_000L, discount = 500L),
                sampleLine(11L, 9L, "Extra", 1_000L, 1_000L, parentLineId = 10L),
                sampleLine(12L, 1L, "Jabón", 1_000L, 2_500L, discount = 100L),
            ),
            payments = listOf(samplePayment(20L, 5L, 1L), samplePayment(21L, 6L, 1L)),
            total = 9_900L,
            note = null,
        )

        val correction = SaleCorrections.of(detail)

        assertEquals(
            listOf(
                SaleLineRequest(
                    1L,
                    QuantityMilli(3_000L),
                    Money(3_000L),
                    LineDiscount.Amount(Money(600L)),
                ),
            ),
            correction.lines,
        )
        // Con varios pagos, la venta nueva se cobra con el método del primero.
        assertEquals(5L, correction.paymentMethodId)
        assertNull(correction.note)
        assertEquals(0, correction.leftOut)
    }

    @Test
    fun `a sale without payments has no method to bring`() {
        val detail = sampleDetail(
            lines = listOf(sampleLine(10L, 1L, "Jabón", 1_000L, 3_000L)),
            payments = emptyList(),
            total = 3_000L,
        )

        assertNull(SaleCorrections.of(detail).paymentMethodId)
    }
}
