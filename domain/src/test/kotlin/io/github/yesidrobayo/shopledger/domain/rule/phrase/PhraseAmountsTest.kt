package io.github.yesidrobayo.shopledger.domain.rule.phrase

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.phrase.DraftLine
import io.github.yesidrobayo.shopledger.domain.model.phrase.DraftTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhraseAmountsTest {
    private fun line(quantity: Long?, unitPrice: Long? = null, lineTotal: Long? = null) = DraftLine(
        product = DraftTarget.Existing(1L),
        quantity = quantity?.let(::QuantityMilli),
        unitId = null,
        unitPrice = unitPrice?.let(::Money),
        lineTotal = lineTotal?.let(::Money),
    )

    @Test
    fun `a line amount is its total, or its price times its quantity`() {
        assertEquals(Money(900), PhraseAmounts.lineAmount(line(2_000, 1_500, 900)))
        assertEquals(Money(3_000), PhraseAmounts.lineAmount(line(2_000, 1_500)))
        // 0,250 a 12500 son 3125.
        assertEquals(Money(3_125), PhraseAmounts.lineAmount(line(250, 12_500)))
    }

    @Test
    fun `a line without a price or a quantity, or above the limit, has no amount`() {
        assertNull(PhraseAmounts.lineAmount(line(2_000)))
        assertNull(PhraseAmounts.lineAmount(line(null, 1_500)))
        assertNull(PhraseAmounts.lineAmount(line(2_000_000_000, 1_000_000_000_000)))
    }

    @Test
    fun `three breads for 1000 charge exactly 1000`() {
        assertEquals(
            ExactLine(Money(334), Money(2)),
            PhraseAmounts.exactLine(Money(1_000), QuantityMilli(3_000)),
        )
    }

    @Test
    fun `an exact division needs no discount`() {
        assertEquals(
            ExactLine(Money(500), Money.ZERO),
            PhraseAmounts.exactLine(Money(1_500), QuantityMilli(3_000)),
        )
        // Medio kilo por 9250 es 18500 el kilo.
        assertEquals(
            ExactLine(Money(18_500), Money.ZERO),
            PhraseAmounts.exactLine(Money(9_250), QuantityMilli(500)),
        )
    }

    @Test
    fun `an exact line is refused when it cannot be priced`() {
        assertNull(PhraseAmounts.exactLine(Money(-1), QuantityMilli(1_000)))
        assertNull(PhraseAmounts.exactLine(Money(1_000), QuantityMilli.ZERO))
        assertNull(PhraseAmounts.exactLine(Money(Long.MAX_VALUE), QuantityMilli(1)))
        assertNull(PhraseAmounts.exactLine(Money(2_000_000_000_000), QuantityMilli(1_000)))
    }

    @Test
    fun `only the metric initial units have a measure`() {
        assertEquals(1_000_000L, PhraseMeasures.baseAmountMilli("KILOGRAM"))
        assertEquals(1_000L, PhraseMeasures.baseAmountMilli("GRAM"))
        assertEquals(1_000_000L, PhraseMeasures.baseAmountMilli("LITER"))
        assertEquals(1_000L, PhraseMeasures.baseAmountMilli("MILLILITER"))
        assertEquals(1_000L, PhraseMeasures.baseAmountMilli("METER"))
        assertNull(PhraseMeasures.baseAmountMilli("POUND"))
        assertNull(PhraseMeasures.baseAmountMilli("GALLON"))
        assertNull(PhraseMeasures.baseAmountMilli(null))
    }

    @Test
    fun `five hundred grams are half a kilogram`() {
        assertEquals(
            QuantityMilli(500),
            PhraseMeasures.convert(QuantityMilli(500_000), 1_000L, 1_000_000L),
        )
        // Tres cajas de 24 son 72 unidades.
        assertEquals(
            QuantityMilli(72_000),
            PhraseMeasures.convert(QuantityMilli(3_000), 24_000L, 1_000L),
        )
    }

    @Test
    fun `a conversion that rounds to zero or passes the line limit is refused`() {
        assertNull(PhraseMeasures.convert(QuantityMilli(1), 1_000L, 1_000_000L))
        assertNull(PhraseMeasures.convert(QuantityMilli(1_000_000_000), 1_000_000L, 1_000L))
    }

    @Test
    fun `a price per gram is a thousand times that per kilogram`() {
        assertEquals(Money(20_000), PhraseMeasures.priceIn(Money(20), 1_000L, 1_000_000L))
        // Una caja de 24 a 12000 es 500 la unidad.
        assertEquals(Money(500), PhraseMeasures.priceIn(Money(12_000), 24_000L, 1_000L))
        assertNull(PhraseMeasures.priceIn(Money(1_000_000_000_000), 1_000L, 1_000_000L))
    }
}
