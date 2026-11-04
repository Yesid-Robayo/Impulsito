package io.github.yesidrobayo.shopledger.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class QuantityMilliTest {
    @Test
    fun `three units are three thousand thousandths`() {
        assertEquals(QuantityMilli(3000L), QuantityMilli.ofUnits(3L))
    }

    @Test
    fun `a quarter kilogram in a product sold by kilogram is 250`() {
        val quarterKilogram = QuantityMilli.ofUnits(0L) + QuantityMilli(250L)

        assertEquals(250L, quarterKilogram.milli)
        assertFalse(quarterKilogram.isWholeUnits)
    }

    @Test
    fun `the same weight in a product sold by gram is 250000`() {
        assertEquals(QuantityMilli(250_000L), QuantityMilli.ofUnits(250L))
    }

    @Test
    fun `one and a half pounds or liters are 1500`() {
        assertEquals(QuantityMilli(1500L), QuantityMilli.ofUnits(1L) + QuantityMilli(500L))
    }

    @Test
    fun `seven hundred fifty milliliters in a product sold by milliliter are 750000`() {
        assertEquals(QuantityMilli(750_000L), QuantityMilli.ofUnits(750L))
    }

    @Test
    fun `whole units have no fraction in either sign`() {
        assertTrue(QuantityMilli(3000L).isWholeUnits)
        assertTrue(QuantityMilli(-2000L).isWholeUnits)
        assertTrue(QuantityMilli.ZERO.isWholeUnits)
        assertFalse(QuantityMilli(1500L).isWholeUnits)
        assertFalse(QuantityMilli(-2500L).isWholeUnits)
        assertFalse(QuantityMilli(1L).isWholeUnits)
    }

    @Test
    fun `adds subtracts and negates in thousandths`() {
        assertEquals(QuantityMilli(4250L), QuantityMilli(4000L) + QuantityMilli(250L))
        assertEquals(QuantityMilli(-2000L), QuantityMilli(1000L) - QuantityMilli(3000L))
        assertEquals(QuantityMilli(-2000L), -QuantityMilli(2000L))
        assertEquals(0L, QuantityMilli.ZERO.milli)
    }

    @Test
    fun `arithmetic that overflows fails`() {
        val largest = QuantityMilli(Long.MAX_VALUE)
        val smallest = QuantityMilli(Long.MIN_VALUE)
        val one = QuantityMilli(1L)

        assertThrows(ArithmeticException::class.java) { largest + one }
        assertThrows(ArithmeticException::class.java) { smallest - one }
        assertThrows(ArithmeticException::class.java) { -smallest }
        assertThrows(ArithmeticException::class.java) {
            QuantityMilli.ofUnits(9_223_372_036_854_776L)
        }
    }

    @Test
    fun `the largest whole number of units still fits`() {
        assertEquals(
            QuantityMilli(9_223_372_036_854_775_000L),
            QuantityMilli.ofUnits(9_223_372_036_854_775L),
        )
    }

    @Test
    fun `compares by quantity`() {
        assertTrue(QuantityMilli(-1L) < QuantityMilli.ZERO)
        assertTrue(QuantityMilli(1001L) > QuantityMilli(1000L))
        assertEquals(0, QuantityMilli(250L).compareTo(QuantityMilli(250L)))
    }
}
