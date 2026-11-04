package io.github.yesidrobayo.shopledger.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MoneyTest {
    @Test
    fun `adds subtracts and negates in minor units`() {
        assertEquals(Money(12350L), Money(12000L) + Money(350L))
        assertEquals(Money(-350L), Money(12000L) - Money(12350L))
        assertEquals(Money(-12350L), -Money(12350L))
        assertEquals(Money(12350L), -Money(-12350L))
    }

    @Test
    fun `zero is zero minor units`() {
        assertEquals(0L, Money.ZERO.minor)
        assertEquals(Money(7000L), Money(7000L) + Money.ZERO)
    }

    @Test
    fun `an addition that overflows fails instead of turning negative`() {
        assertThrows(ArithmeticException::class.java) { Money(Long.MAX_VALUE) + Money(1L) }
        assertThrows(ArithmeticException::class.java) { Money(Long.MIN_VALUE) + Money(-1L) }
    }

    @Test
    fun `a subtraction that overflows fails`() {
        assertThrows(ArithmeticException::class.java) { Money(Long.MIN_VALUE) - Money(1L) }
        assertThrows(ArithmeticException::class.java) { Money(Long.MAX_VALUE) - Money(-1L) }
    }

    @Test
    fun `negating the smallest amount fails because its opposite does not fit`() {
        assertThrows(ArithmeticException::class.java) { -Money(Long.MIN_VALUE) }
    }

    @Test
    fun `compares by amount`() {
        assertTrue(Money(-1L) < Money.ZERO)
        assertTrue(Money(2L) > Money(1L))
        assertEquals(0, Money(5L).compareTo(Money(5L)))
        assertEquals(Money(18000L), listOf(Money(7000L), Money(18000L), Money(6000L)).max())
    }

    @Test
    fun `sum adds the line totals of a sale`() {
        // Jabón 7000, café 6000 y crema 18000: la venta suma 31000.
        assertEquals(Money(31000L), listOf(Money(7000L), Money(6000L), Money(18000L)).sum())
    }

    @Test
    fun `sum of nothing is zero`() {
        assertEquals(Money.ZERO, emptyList<Money>().sum())
        assertEquals(Money.ZERO, emptyList<Money>().sumOrNull())
    }

    @Test
    fun `a sum that overflows fails so the write is reverted`() {
        assertThrows(ArithmeticException::class.java) {
            listOf(Money(Long.MAX_VALUE), Money(1L)).sum()
        }
    }

    @Test
    fun `a report sum of one hundred thousand sales at the cap has no value instead of crashing`() {
        val sales = List(100_000) { Money(1_000_000_000_000_000L) }

        assertNull(sales.sumOrNull())
    }

    @Test
    fun `a report sum fits 9223 documents at the cap and not one more`() {
        val atCap = Money(1_000_000_000_000_000L)

        assertEquals(Money(9_223_000_000_000_000_000L), List(9223) { atCap }.sumOrNull())
        assertNull(List(9224) { atCap }.sumOrNull())
    }

    @Test
    fun `a report sum with negative amounts keeps its sign`() {
        assertEquals(Money(-1200L), listOf(Money(-2000L), Money(800L)).sumOrNull())
    }
}
