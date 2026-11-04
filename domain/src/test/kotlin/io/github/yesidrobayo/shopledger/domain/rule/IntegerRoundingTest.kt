package io.github.yesidrobayo.shopledger.domain.rule

import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class IntegerRoundingTest {
    @Test
    fun `a tie rounds up`() {
        assertEquals(6161L, roundDiv(6_160_500L, 1000L))
        assertEquals(3L, roundDiv(2500L, 1000L))
    }

    @Test
    fun `a negative tie rounds away from zero like its positive opposite`() {
        assertEquals(-6161L, roundDiv(-6_160_500L, 1000L))
        assertEquals(-3L, roundDiv(-2500L, 1000L))
    }

    @Test
    fun `below the tie rounds toward zero in both signs`() {
        assertEquals(2L, roundDiv(2499L, 1000L))
        assertEquals(-2L, roundDiv(-2499L, 1000L))
    }

    @Test
    fun `above the tie rounds away from zero in both signs`() {
        assertEquals(3L, roundDiv(2501L, 1000L))
        assertEquals(-3L, roundDiv(-2501L, 1000L))
    }

    @Test
    fun `an exact division does not round`() {
        assertEquals(6L, roundDiv(6000L, 1000L))
        assertEquals(-6L, roundDiv(-6000L, 1000L))
        assertEquals(0L, roundDiv(0L, 7L))
    }

    @Test
    fun `half a minor unit becomes one`() {
        assertEquals(1L, roundDiv(1L, 2L))
        assertEquals(-1L, roundDiv(-1L, 2L))
        assertEquals(0L, roundDiv(1L, 3L))
    }

    @Test
    fun `works at the ends of the long range without overflowing`() {
        assertEquals(Long.MAX_VALUE, roundDiv(Long.MAX_VALUE, 1L))
        assertEquals(Long.MIN_VALUE, roundDiv(Long.MIN_VALUE, 1L))
        // Exacto 4611686018427387903,5: el empate sube sin calcular 2n + d.
        assertEquals(4_611_686_018_427_387_904L, roundDiv(Long.MAX_VALUE, 2L))
        assertEquals(-4_611_686_018_427_387_904L, roundDiv(Long.MIN_VALUE, 2L))
        // Exacto −3074457345618258602,67.
        assertEquals(-3_074_457_345_618_258_603L, roundDiv(Long.MIN_VALUE, 3L))
        assertEquals(1L, roundDiv(Long.MAX_VALUE, Long.MAX_VALUE))
    }

    @Test
    fun `compares the remainder with a huge divisor without overflowing`() {
        // 2^62 entre 2^63 − 1 queda apenas sobre la mitad; 2^62 − 1, apenas debajo.
        assertEquals(1L, roundDiv(4_611_686_018_427_387_904L, Long.MAX_VALUE))
        assertEquals(0L, roundDiv(4_611_686_018_427_387_903L, Long.MAX_VALUE))
        assertEquals(-1L, roundDiv(-4_611_686_018_427_387_904L, Long.MAX_VALUE))
    }

    @Test
    fun `a divisor that is not positive is a programming error`() {
        assertThrows(IllegalArgumentException::class.java) { roundDiv(10L, 0L) }
        assertThrows(IllegalArgumentException::class.java) { roundDiv(10L, -5L) }
    }

    @Test
    fun `round division matches half up rounding on ten thousand seeded cases`() {
        val random = Random(20_261_103)
        repeat(10_000) {
            val n = randomLong(random)
            val d = randomPositiveLong(random)
            val expected =
                BigDecimal(n)
                    .divide(BigDecimal(d), 0, RoundingMode.HALF_UP)
                    .longValueExact()

            assertEquals("roundDiv($n, $d)", expected, roundDiv(n, d))
        }
    }

    @Test
    fun `ceiling division rounds up a suggested price`() {
        // Exacto 1935,71.
        assertEquals(1936L, ceilDiv(13_550_000L, 7000L))
    }

    @Test
    fun `ceiling division keeps an exact result`() {
        assertEquals(2000L, ceilDiv(13_000_000L, 6500L))
        assertEquals(0L, ceilDiv(0L, 7L))
    }

    @Test
    fun `ceiling division does not overflow near the largest long`() {
        assertEquals(Long.MAX_VALUE, ceilDiv(Long.MAX_VALUE, 1L))
        assertEquals(4_611_686_018_427_387_904L, ceilDiv(Long.MAX_VALUE, 2L))
        assertEquals(1L, ceilDiv(1L, Long.MAX_VALUE))
    }

    @Test
    fun `ceiling division rejects a negative dividend or a divisor that is not positive`() {
        assertThrows(IllegalArgumentException::class.java) { ceilDiv(-1L, 7L) }
        assertThrows(IllegalArgumentException::class.java) { ceilDiv(10L, 0L) }
        assertThrows(IllegalArgumentException::class.java) { ceilDiv(10L, -7L) }
    }

    @Test
    fun `ceiling division matches the exact ceiling on ten thousand seeded cases`() {
        val random = Random(20_261_104)
        repeat(10_000) {
            val n = randomLong(random).let { if (it < 0) -(it + 1) else it }
            val d = randomPositiveLong(random)
            val (quotient, remainder) = BigInteger.valueOf(
                n,
            ).divideAndRemainder(BigInteger.valueOf(d))
            val expected = if (remainder.signum() == 0) quotient else quotient + BigInteger.ONE

            assertEquals("ceilDiv($n, $d)", expected.toLong(), ceilDiv(n, d))
        }
    }

    @Test
    fun `ceiling to a step rounds up to the next multiple`() {
        assertEquals(13_400L, ceilToStep(13_338L, 100L))
    }

    @Test
    fun `ceiling to a step keeps a value that is already a multiple`() {
        assertEquals(5500L, ceilToStep(5500L, 500L))
        assertEquals(0L, ceilToStep(0L, 50L))
    }

    @Test
    fun `bulk price changes round up to each step`() {
        // Precio actual, porcentaje en puntos básicos y el resultado con pasos 50, 100, 500 y 1000.
        val rows =
            listOf(
                Triple(12_350L, 800L, listOf(13_338L, 13_350L, 13_400L, 13_500L, 14_000L)),
                Triple(3240L, 800L, listOf(3499L, 3500L, 3500L, 3500L, 4000L)),
                Triple(9990L, -1000L, listOf(8991L, 9000L, 9000L, 9000L, 9000L)),
                Triple(5000L, 1000L, listOf(5500L, 5500L, 5500L, 5500L, 6000L)),
                Triple(80L, 500L, listOf(84L, 100L, 100L, 500L, 1000L)),
            )
        for ((price, percent, expected) in rows) {
            val unrounded = roundDiv(price * (10_000L + percent), 10_000L)
            val rounded = listOf(50L, 100L, 500L, 1000L).map { ceilToStep(unrounded, it) }

            assertEquals("precio $price", expected, listOf(unrounded) + rounded)
        }
    }

    @Test
    fun `ceiling to a step fails if the multiple does not fit in a long`() {
        assertThrows(ArithmeticException::class.java) { ceilToStep(Long.MAX_VALUE, 2L) }
    }

    @Test
    fun `a step that is not positive is a programming error`() {
        assertThrows(IllegalArgumentException::class.java) { ceilToStep(100L, 0L) }
    }

    private fun randomLong(random: Random): Long {
        val bits = random.nextInt(1, 64)
        val magnitude = random.nextLong() ushr (64 - bits)
        return if (random.nextBoolean()) -magnitude else magnitude
    }

    private fun randomPositiveLong(random: Random): Long {
        val bits = random.nextInt(1, 64)
        return (random.nextLong() ushr (64 - bits)).coerceAtLeast(1L)
    }
}
