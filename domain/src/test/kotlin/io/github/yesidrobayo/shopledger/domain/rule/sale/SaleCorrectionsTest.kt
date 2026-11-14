package io.github.yesidrobayo.shopledger.domain.rule.sale

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.sale.LineDiscount
import io.github.yesidrobayo.shopledger.domain.model.sale.SAMPLE_DATE
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLine
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
                SaleLineRequest(1L, QuantityMilli(2_000L), Money(3_000L), LineDiscount.None, 1L),
                SaleLineRequest(
                    2L,
                    QuantityMilli(1_000L),
                    Money(20_000L),
                    LineDiscount.Amount(Money(2_000L)),
                    unitId = 1L,
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
    fun `a product in two lines arrives as one, with its extras under it`() {
        val detail = sampleDetail(
            lines = listOf(
                sampleLine(10L, 1L, "Jabón", 2_000L, 3_000L, discount = 500L),
                sampleLine(11L, 9L, "Extra", 2_000L, 1_000L, parentLineId = 10L),
                sampleLine(12L, 1L, "Jabón", 1_000L, 2_500L, discount = 100L),
                sampleLine(13L, 9L, "Extra", 1_000L, 900L, parentLineId = 12L),
                sampleLine(14L, 9L, "Extra", 1_000L, 1_000L),
            ),
            payments = listOf(samplePayment(20L, 5L, 1L), samplePayment(21L, 6L, 1L)),
            total = 12_800L,
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
                    unitId = 1L,
                ),
                // Un extra por producto, con el precio del primero: la cantidad la pone la venta.
                SaleLineRequest(
                    9L,
                    QuantityMilli(3_000L),
                    Money(1_000L),
                    LineDiscount.None,
                    unitId = 1L,
                    parentIndex = 0,
                ),
                // El mismo producto vendido solo sigue en su propia línea.
                SaleLineRequest(9L, QuantityMilli(1_000L), Money(1_000L), LineDiscount.None, 1L),
            ),
            correction.lines,
        )
        // Con varios pagos, la venta nueva se cobra con el método del primero.
        assertEquals(5L, correction.paymentMethodId)
        assertNull(correction.note)
        assertEquals(0, correction.leftOut)
    }

    @Test
    fun `an extra of an archived line, or of an archived product, is left out and counted`() {
        val detail = sampleDetail(
            lines = listOf(
                sampleLine(10L, 1L, "Arepa", 1_000L, 3_000L),
                sampleLine(11L, 9L, "Queso", 1_000L, 1_000L, parentLineId = 10L),
                sampleLine(12L, 8L, "Salsa", 1_000L, 500L, parentLineId = 10L),
                sampleLine(13L, 2L, "Té", 1_000L, 2_000L),
                sampleLine(14L, 9L, "Queso", 1_000L, 1_000L, parentLineId = 13L),
            ),
            payments = listOf(samplePayment(20L, 5L, 7_500L)),
            total = 7_500L,
            archived = setOf(2L, 8L),
        )

        val correction = SaleCorrections.of(detail)

        assertEquals(listOf(1L, 9L), correction.lines.map { it.productId })
        assertEquals(listOf(null, 0), correction.lines.map { it.parentIndex })
        assertEquals(3, correction.leftOut)
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

    @Test
    fun `a sale by the box comes back in boxes, with the price of a box`() {
        // 3 cajas de 12 a 12000 la caja: el libro guardó 36 unidades.
        val detail = sampleDetail(
            lines = listOf(
                boxLine(10L, 1L, boxes = 3_000L, unitPrice = 12_000L, discount = 1_000L),
            ),
            payments = listOf(samplePayment(20L, 5L, 35_000L)),
            total = 35_000L,
        )

        val correction = SaleCorrections.of(detail)

        assertEquals(
            listOf(
                SaleLineRequest(
                    1L,
                    QuantityMilli(3_000L),
                    Money(12_000L),
                    LineDiscount.Amount(Money(1_000L)),
                    unitId = BOX,
                ),
            ),
            correction.lines,
        )
    }

    @Test
    fun `a product in two lines of the same box adds the boxes`() {
        val detail = sampleDetail(
            lines = listOf(
                boxLine(10L, 1L, boxes = 1_000L, unitPrice = 12_000L),
                boxLine(11L, 1L, boxes = 2_000L, unitPrice = 11_000L),
            ),
            payments = listOf(samplePayment(20L, 5L, 34_000L)),
            total = 34_000L,
        )

        val line = SaleCorrections.of(detail).lines.single()

        assertEquals(QuantityMilli(3_000L), line.quantity)
        assertEquals(Money(12_000L), line.unitPrice)
        assertEquals(BOX, line.unitId)
    }

    @Test
    fun `a product sold in two units comes back in its base unit`() {
        // Una caja de 12 a 12000 y 2 unidades sueltas: 14 unidades a 1000, el precio de la caja
        // llevado a una unidad.
        val detail = sampleDetail(
            lines = listOf(
                boxLine(10L, 1L, boxes = 1_000L, unitPrice = 12_000L, discount = 500L),
                sampleLine(11L, 1L, "Jabón", 2_000L, 1_100L, discount = 100L),
            ),
            payments = listOf(samplePayment(20L, 5L, 13_600L)),
            total = 13_600L,
        )

        val line = SaleCorrections.of(detail).lines.single()

        assertEquals(
            SaleLineRequest(
                1L,
                QuantityMilli(14_000L),
                Money(1_000L),
                LineDiscount.Amount(Money(600L)),
            ),
            line,
        )
    }

    @Test
    fun `a base price that does not fit takes the price of today`() {
        // Un gramo de un producto por kilo, al tope de un precio: el kilo no cabe.
        val gram = sampleLine(10L, 1L, "Azafrán", 1_000L, 1_000_000_000_000L).inUnit(GRAM, 1L, 1L)
        val detail = sampleDetail(
            lines = listOf(gram, sampleLine(11L, 1L, "Azafrán", 1_000L, 2_000L)),
            payments = emptyList(),
            total = 2_000L,
        )

        val line = SaleCorrections.of(detail).lines.single()

        assertNull(line.unitPrice)
        assertNull(line.unitId)
        assertEquals(QuantityMilli(1_001L), line.quantity)
    }

    /** Una línea guardada de [boxes] milésimas de caja de 12 a [unitPrice] la caja. */
    private fun boxLine(
        id: Long,
        productId: Long,
        boxes: Long,
        unitPrice: Long,
        discount: Long = 0L,
    ): SaleLine = sampleLine(id, productId, "Jabón", boxes, unitPrice, discount)
        .inUnit(BOX, BOX_FACTOR, boxes * BOX_FACTOR / 1_000L)

    /** La misma línea vendida en [unitId], con su factor y la cantidad del libro en la base. */
    private fun SaleLine.inUnit(unitId: Long, factor: Long, baseQuantity: Long): SaleLine = copy(
        content = content.copy(
            unitId = unitId,
            unitFactor = QuantityMilli(factor),
            quantity = QuantityMilli(baseQuantity),
        ),
    )

    private companion object {
        const val BOX = 7L
        const val GRAM = 8L
        const val BOX_FACTOR = 12_000L
    }
}
