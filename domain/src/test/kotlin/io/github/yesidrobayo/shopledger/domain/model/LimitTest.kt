package io.github.yesidrobayo.shopledger.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Los topes y escalas con sus valores escritos a mano, y la tabla de peores casos que justifica
 * cada tope: lo que cabe en `Long` se multiplica directo, y lo que no, pasa por la regla de 128
 * bits.
 */
class LimitTest {
    @Test
    fun `scales are written by hand`() {
        assertEquals(1_000L, Scale.MILLI)
        assertEquals(10_000L, Scale.BASIS_POINTS)
        assertEquals(1_000_000L, Scale.MICRO)
    }

    @Test
    fun `every cap has the documented value`() {
        assertEquals(1_000_000_000_000L, Limit.UNIT_MONEY.max)
        assertEquals(1_000_000_000_000_000L, Limit.DOCUMENT_TOTAL.max)
        assertEquals(9_000_000_000_000_000L, Limit.STOCK_VALUE.max)
        assertEquals(1_000_000_000_000L, Limit.POINTS.max)
        assertEquals(1_000_000_000L, Limit.LINE_QUANTITY_MILLI.max)
        assertEquals(8_000_000_000L, Limit.ON_HAND_MILLI.max)
        assertEquals(200L, Limit.DOCUMENT_LINES.max)
        assertEquals(7, Limit.entries.size)
    }

    @Test
    fun `unit money accepts its cap and rejects one more`() {
        assertTrue(Limit.UNIT_MONEY.allows(1_000_000_000_000L))
        assertFalse(Limit.UNIT_MONEY.allows(1_000_000_000_001L))
    }

    @Test
    fun `document total accepts its cap and rejects one more`() {
        assertTrue(Limit.DOCUMENT_TOTAL.allows(1_000_000_000_000_000L))
        assertFalse(Limit.DOCUMENT_TOTAL.allows(1_000_000_000_000_001L))
    }

    @Test
    fun `stock value accepts its cap and rejects one more`() {
        assertTrue(Limit.STOCK_VALUE.allows(9_000_000_000_000_000L))
        assertFalse(Limit.STOCK_VALUE.allows(9_000_000_000_000_001L))
    }

    @Test
    fun `points accept their cap and reject one more`() {
        assertTrue(Limit.POINTS.allows(1_000_000_000_000L))
        assertFalse(Limit.POINTS.allows(1_000_000_000_001L))
    }

    @Test
    fun `line quantity accepts its cap and rejects one more`() {
        assertTrue(Limit.LINE_QUANTITY_MILLI.allows(1_000_000_000L))
        assertFalse(Limit.LINE_QUANTITY_MILLI.allows(1_000_000_001L))
    }

    @Test
    fun `stock on hand accepts its cap and rejects one more`() {
        assertTrue(Limit.ON_HAND_MILLI.allows(8_000_000_000L))
        assertFalse(Limit.ON_HAND_MILLI.allows(8_000_000_001L))
    }

    @Test
    fun `document lines accept their cap and reject one more`() {
        assertTrue(Limit.DOCUMENT_LINES.allows(200L))
        assertFalse(Limit.DOCUMENT_LINES.allows(201L))
    }

    @Test
    fun `caps only bound from above so negative stock has no limit of its own`() {
        assertTrue(Limit.ON_HAND_MILLI.allows(-8_000_000_001L))
        assertTrue(Limit.ON_HAND_MILLI.allows(Long.MIN_VALUE))
    }

    @Test
    fun `the cost of an entry times one thousand fits in a long`() {
        assertEquals(
            1_000_000_000_000_000_000L,
            Math.multiplyExact(Limit.DOCUMENT_TOTAL.max, Scale.MILLI),
        )
    }

    @Test
    fun `the stock value times one thousand fits in a long`() {
        assertEquals(
            9_000_000_000_000_000_000L,
            Math.multiplyExact(Limit.STOCK_VALUE.max, Scale.MILLI),
        )
    }

    @Test
    fun `a margin at the highest price fits in a long`() {
        assertEquals(
            10_000_000_000_000_000L,
            Math.multiplyExact(Limit.UNIT_MONEY.max, Scale.BASIS_POINTS),
        )
    }

    @Test
    fun `the highest bulk increase on the highest price fits in a long`() {
        val factor = Scale.BASIS_POINTS + BasisPoints.MAX_BULK_PERCENT.bp

        assertEquals(109_999_000_000_000_000L, Math.multiplyExact(Limit.UNIT_MONEY.max, factor))
    }

    @Test
    fun `a full line at the highest price does not fit in a long and needs 128 bits`() {
        assertThrows(ArithmeticException::class.java) {
            Math.multiplyExact(Limit.LINE_QUANTITY_MILLI.max, Limit.UNIT_MONEY.max)
        }
    }

    @Test
    fun `the cost of an exit from the highest stock does not fit in a long and needs 128 bits`() {
        assertThrows(ArithmeticException::class.java) {
            Math.multiplyExact(Limit.STOCK_VALUE.max, Limit.ON_HAND_MILLI.max)
        }
    }

    @Test
    fun `a percentage of the highest document does not fit in a long and needs 128 bits`() {
        assertThrows(ArithmeticException::class.java) {
            Math.multiplyExact(Limit.DOCUMENT_TOTAL.max, Scale.BASIS_POINTS)
        }
    }

    @Test
    fun `a report fits 9223 documents at the cap before a sum overflows`() {
        assertEquals(9223L, Long.MAX_VALUE / Limit.DOCUMENT_TOTAL.max)
    }
}
