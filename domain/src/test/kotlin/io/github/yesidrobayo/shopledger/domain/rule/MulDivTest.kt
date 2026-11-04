package io.github.yesidrobayo.shopledger.domain.rule

import io.github.yesidrobayo.shopledger.domain.model.Checked
import io.github.yesidrobayo.shopledger.domain.model.Limit
import java.math.BigInteger
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MulDivTest {
    @Test
    fun `a full line at the highest price fits although its product does not`() {
        // 10^9 × 10^12 = 10^21 no cabe en Long; el resultado, 10^18, sí.
        assertEquals(
            Checked.Value(1_000_000_000_000_000_000L),
            mulDivRound(1_000_000_000L, 1_000_000_000_000L, 1000L),
        )
    }

    @Test
    fun `a result that does not fit in a long is rejected`() {
        assertEquals(Checked.Overflow, mulDivRound(3_037_000_500L, 3_037_000_500L, 1L))
        assertEquals(Checked.Overflow, mulDivRound(Long.MAX_VALUE, 3L, 2L))
    }

    @Test
    fun `half a minor unit rounds away from zero in both signs`() {
        assertEquals(Checked.Value(1L), mulDivRound(1L, 1L, 2L))
        assertEquals(Checked.Value(-1L), mulDivRound(-1L, 1L, 2L))
    }

    @Test
    fun `a third of a kilogram rounds its tie up`() {
        // 0,333 kg a 18500 por kilogramo: exacto 6160,5.
        assertEquals(Checked.Value(6161L), mulDivRound(333L, 18_500L, 1000L))
    }

    @Test
    fun `a result that needs 128 bits in the middle comes back exact`() {
        assertEquals(Checked.Value(Long.MAX_VALUE), mulDivRound(Long.MAX_VALUE, 2L, 2L))
        assertEquals(
            Checked.Value(Long.MAX_VALUE),
            mulDivRound(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE),
        )
    }

    @Test
    fun `the sign is the product of both signs`() {
        // 3 × 5 / 2 = 7,5 en cada combinación de signos.
        assertEquals(Checked.Value(8L), mulDivRound(3L, 5L, 2L))
        assertEquals(Checked.Value(-8L), mulDivRound(-3L, 5L, 2L))
        assertEquals(Checked.Value(-8L), mulDivRound(3L, -5L, 2L))
        assertEquals(Checked.Value(8L), mulDivRound(-3L, -5L, 2L))
        assertEquals(Checked.Value(0L), mulDivRound(0L, Long.MAX_VALUE, 7L))
    }

    @Test
    fun `a product whose high half reaches the divisor is rejected`() {
        // 2^32 × 2^32 = 2^64: el cociente entre 1 ya no cabe en 64 bits.
        assertEquals(Checked.Overflow, mulDivRound(4_294_967_296L, 4_294_967_296L, 1L))
    }

    @Test
    fun `a quotient that fits in 64 bits but not in a long is rejected`() {
        // (2^32 − 1) × (2^32 + 1) = 2^64 − 1.
        assertEquals(Checked.Overflow, mulDivRound(4_294_967_295L, 4_294_967_297L, 1L))
    }

    @Test
    fun `a quotient that only overflows when rounding is rejected`() {
        // (2^64 − 1) / 2 = 2^63 − 0,5, que redondea a 2^63.
        assertEquals(Checked.Overflow, mulDivRound(4_294_967_295L, 4_294_967_297L, 2L))
        assertEquals(Checked.Overflow, mulDivRound(-4_294_967_295L, 4_294_967_297L, 2L))
    }

    @Test
    fun `the range is symmetric so the smallest long is rejected as a result`() {
        assertEquals(Checked.Overflow, mulDivRound(Long.MIN_VALUE, 1L, 1L))
        assertEquals(
            Checked.Value(-4_611_686_018_427_387_904L),
            mulDivRound(Long.MIN_VALUE, 1L, 2L),
        )
        assertEquals(
            Checked.Value(4_611_686_018_427_387_904L),
            mulDivRound(Long.MIN_VALUE, -1L, 2L),
        )
    }

    @Test
    fun `a divisor that is not positive is a programming error`() {
        assertThrows(IllegalArgumentException::class.java) { mulDivRound(1L, 1L, 0L) }
        assertThrows(IllegalArgumentException::class.java) {
            mulDivRound(1L, 1L, -1L, Limit.DOCUMENT_TOTAL)
        }
    }

    @Test
    fun `a line of one million units at one billion each is exactly at the document cap`() {
        assertEquals(
            Checked.Value(1_000_000_000_000_000L),
            mulDivRound(1_000_000_000L, 1_000_000_000L, 1000L, Limit.DOCUMENT_TOTAL),
        )
    }

    @Test
    fun `a line of one million units at the highest price is above the document cap`() {
        assertEquals(
            Checked.AboveLimit(Limit.DOCUMENT_TOTAL),
            mulDivRound(1_000_000_000L, 1_000_000_000_000L, 1000L, Limit.DOCUMENT_TOTAL),
        )
    }

    @Test
    fun `a result that does not fit is an overflow also when a cap is asked`() {
        assertEquals(Checked.Overflow, mulDivRound(Long.MAX_VALUE, 3L, 2L, Limit.DOCUMENT_TOTAL))
    }

    @Test
    fun `a cap only bounds from above`() {
        assertEquals(
            Checked.Value(-1_000_000_000_000_001L),
            mulDivRound(-1_000_000_000_000_001L, 1000L, 1000L, Limit.DOCUMENT_TOTAL),
        )
    }

    @Test
    fun `unit money is accepted at its cap and rejected one above`() {
        // Una actualización masiva de 0 % y de 0,01 % sobre el precio máximo.
        assertEquals(
            Checked.Value(1_000_000_000_000L),
            mulDivRound(1_000_000_000_000L, 10_000L, 10_000L, Limit.UNIT_MONEY),
        )
        assertEquals(
            Checked.AboveLimit(Limit.UNIT_MONEY),
            mulDivRound(1_000_000_000_000L, 10_001L, 10_000L, Limit.UNIT_MONEY),
        )
        assertEquals(
            Checked.AboveLimit(Limit.UNIT_MONEY),
            mulDivRound(1_000_000_000_001L, 1000L, 1000L, Limit.UNIT_MONEY),
        )
    }

    @Test
    fun `document total is accepted at its cap and rejected one above`() {
        assertEquals(
            Checked.Value(1_000_000_000_000_000L),
            mulDivRound(1_000_000_000_000_000L, 1000L, 1000L, Limit.DOCUMENT_TOTAL),
        )
        assertEquals(
            Checked.AboveLimit(Limit.DOCUMENT_TOTAL),
            mulDivRound(1_000_000_000_000_001L, 1000L, 1000L, Limit.DOCUMENT_TOTAL),
        )
    }

    @Test
    fun `stock value is accepted at its cap and rejected one above`() {
        assertEquals(
            Checked.Value(9_000_000_000_000_000L),
            mulDivRound(9_000_000_000_000_000L, 8_000_000_000L, 8_000_000_000L, Limit.STOCK_VALUE),
        )
        assertEquals(
            Checked.AboveLimit(Limit.STOCK_VALUE),
            mulDivRound(9_000_000_000_000_001L, 8_000_000_000L, 8_000_000_000L, Limit.STOCK_VALUE),
        )
    }

    @Test
    fun `points are accepted at their cap and rejected one above`() {
        assertEquals(
            Checked.Value(1_000_000_000_000L),
            mulDivRound(1_000_000_000_000L, 1000L, 1000L, Limit.POINTS),
        )
        assertEquals(
            Checked.AboveLimit(Limit.POINTS),
            mulDivRound(1_000_000_000_001L, 1000L, 1000L, Limit.POINTS),
        )
    }

    @Test
    fun `a converted line quantity is accepted at its cap and rejected one above`() {
        // Una línea en una unidad con factor 1: la cantidad base es la misma.
        assertEquals(
            Checked.Value(1_000_000_000L),
            mulDivRound(1_000_000_000L, 1000L, 1000L, Limit.LINE_QUANTITY_MILLI),
        )
        assertEquals(
            Checked.AboveLimit(Limit.LINE_QUANTITY_MILLI),
            mulDivRound(1_000_000_001L, 1000L, 1000L, Limit.LINE_QUANTITY_MILLI),
        )
    }

    @Test
    fun `stock on hand is accepted at its cap and rejected one above`() {
        assertEquals(
            Checked.Value(8_000_000_000L),
            mulDivRound(8_000_000_000L, 1000L, 1000L, Limit.ON_HAND_MILLI),
        )
        assertEquals(
            Checked.AboveLimit(Limit.ON_HAND_MILLI),
            mulDivRound(8_000_000_001L, 1000L, 1000L, Limit.ON_HAND_MILLI),
        )
    }

    @Test
    fun `document lines are accepted at their cap and rejected one above`() {
        assertEquals(Checked.Value(200L), mulDivRound(200L, 1L, 1L, Limit.DOCUMENT_LINES))
        assertEquals(
            Checked.AboveLimit(Limit.DOCUMENT_LINES),
            mulDivRound(201L, 1L, 1L, Limit.DOCUMENT_LINES),
        )
    }

    @Test
    fun `matches big integer arithmetic on ten thousand seeded cases`() {
        val random = Random(20_261_103)
        val anyRange = List(6000) { randomCase(random) }
        val ties = List(2000) { tieCase(random) }
        val edges = List(2000) { edgeCase(random) }
        val cases = anyRange + ties + edges

        assertEquals(10_000, cases.size)
        for ((a, b, d) in cases) {
            assertEquals("mulDivRound($a, $b, $d)", reference(a, b, d), mulDivRound(a, b, d))
        }
    }

    @Test
    fun `matches big integer arithmetic on every combination of edge values`() {
        for (a in EDGES) {
            for (b in EDGES) {
                for (d in EDGE_DIVISORS) {
                    assertEquals(
                        "mulDivRound($a, $b, $d)",
                        reference(a, b, d),
                        mulDivRound(a, b, d),
                    )
                }
            }
        }
    }

    @Test
    fun `the ceiling of an exact division does not round`() {
        // Una caja de 1000 unidades por 400, por mil y por 10000: 0,4 por unidad son 4000.
        assertEquals(Checked.Value(4000L), mulDivCeil(400L, 10_000_000L, 1_000_000L))
        assertEquals(Checked.Value(Long.MAX_VALUE), mulDivCeil(Long.MAX_VALUE, 2L, 2L))
        assertEquals(Checked.Value(0L), mulDivCeil(0L, Long.MAX_VALUE, 7L))
    }

    @Test
    fun `any remainder rounds the ceiling up`() {
        // 1355 × 10000 / 7000 = 1935,71, el precio de un margen del 30 %.
        assertEquals(Checked.Value(1936L), mulDivCeil(1355L, 10_000L, 7000L))
        // Una caja de 24 por 10000, por mil y por 10000: 4166666,67.
        assertEquals(Checked.Value(4_166_667L), mulDivCeil(10_000L, 10_000_000L, 24_000L))
        // Un tercio sube igual que medio, y que casi uno.
        assertEquals(Checked.Value(1L), mulDivCeil(1L, 1L, 3L))
        assertEquals(Checked.Value(1L), mulDivCeil(1L, 1L, 2L))
        assertEquals(Checked.Value(1L), mulDivCeil(2L, 1L, 3L))
    }

    @Test
    fun `a ceiling that needs 128 bits in the middle comes back exact`() {
        // 10^9 × 10^12 = 10^21 no cabe en Long; el resultado, 10^18, sí.
        assertEquals(
            Checked.Value(1_000_000_000_000_000_000L),
            mulDivCeil(1_000_000_000L, 1_000_000_000_000L, 1000L),
        )
        assertEquals(
            Checked.Value(Long.MAX_VALUE),
            mulDivCeil(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE),
        )
    }

    @Test
    fun `a ceiling whose product high half reaches the divisor is rejected`() {
        // 2^32 × 2^32 = 2^64: el cociente entre 1 ya no cabe en 64 bits.
        assertEquals(Checked.Overflow, mulDivCeil(4_294_967_296L, 4_294_967_296L, 1L))
        assertEquals(Checked.Overflow, mulDivCeil(3_037_000_500L, 3_037_000_500L, 1L))
    }

    @Test
    fun `a ceiling that fits in 64 bits but not in a long is rejected`() {
        // (2^32 − 1) × (2^32 + 1) = 2^64 − 1.
        assertEquals(Checked.Overflow, mulDivCeil(4_294_967_295L, 4_294_967_297L, 1L))
    }

    @Test
    fun `a ceiling that only overflows when rounding up is rejected`() {
        // (2^64 − 1) / 2 = 2^63 − 0,5: el cociente es el máximo de Long y el techo, 2^63, no cabe.
        assertEquals(Checked.Overflow, mulDivCeil(4_294_967_295L, 4_294_967_297L, 2L))
    }

    @Test
    fun `a quotient of all ones with a remainder does not wrap around to zero`() {
        // 31 × 1190112520884487201 = 2^65 − 1, y entre 2 da 2^64 − 1 con resto 1. Sumar uno a ese
        // cociente sin mirarlo antes daría cero.
        assertEquals(Checked.Overflow, mulDivCeil(31L, 1_190_112_520_884_487_201L, 2L))
    }

    @Test
    fun `a negative factor or a divisor that is not positive is a programming error`() {
        assertThrows(IllegalArgumentException::class.java) { mulDivCeil(-1L, 1L, 1L) }
        assertThrows(IllegalArgumentException::class.java) { mulDivCeil(1L, -1L, 1L) }
        assertThrows(IllegalArgumentException::class.java) { mulDivCeil(1L, 1L, 0L) }
        assertThrows(IllegalArgumentException::class.java) { mulDivCeil(1L, 1L, -1L) }
    }

    @Test
    fun `the ceiling matches big integer arithmetic on ten thousand seeded cases`() {
        val random = Random(20_261_104)
        val anyRange = List(8000) { ceilingCase(random) }
        val edges = List(2000) { ceilingEdgeCase(random) }
        val cases = anyRange + edges

        assertEquals(10_000, cases.size)
        for ((a, b, d) in cases) {
            assertEquals("mulDivCeil($a, $b, $d)", ceilingReference(a, b, d), mulDivCeil(a, b, d))
        }
    }

    @Test
    fun `the ceiling matches big integer arithmetic on every combination of edge values`() {
        val factors = EDGES.filter { it >= 0L }
        for (a in factors) {
            for (b in factors) {
                for (d in EDGE_DIVISORS) {
                    assertEquals(
                        "mulDivCeil($a, $b, $d)",
                        ceilingReference(a, b, d),
                        mulDivCeil(a, b, d),
                    )
                }
            }
        }
    }

    /**
     * La misma cuenta con `BigInteger`, que solo existe en las pruebas, y con otra forma de
     * redondear: comparar el doble del resto con el divisor.
     */
    private fun reference(a: Long, b: Long, d: Long): Checked<Long> {
        val product = BigInteger.valueOf(a).multiply(BigInteger.valueOf(b))
        val divisor = BigInteger.valueOf(d)
        val (quotient, remainder) = product.abs().divideAndRemainder(divisor)
        val roundsUp = remainder.shiftLeft(1) >= divisor
        val magnitude = if (roundsUp) quotient + BigInteger.ONE else quotient
        if (magnitude > BigInteger.valueOf(Long.MAX_VALUE)) return Checked.Overflow
        val signed = if (product.signum() < 0) magnitude.negate() else magnitude
        return Checked.Value(signed.toLong())
    }

    /**
     * El techo con `BigInteger` y con otra fórmula, `(n + d − 1) / d`, que en `Long` desbordaría y
     * aquí no.
     */
    private fun ceilingReference(a: Long, b: Long, d: Long): Checked<Long> {
        val product = BigInteger.valueOf(a).multiply(BigInteger.valueOf(b))
        val divisor = BigInteger.valueOf(d)
        val ceiling = (product + divisor - BigInteger.ONE) / divisor
        if (ceiling > BigInteger.valueOf(Long.MAX_VALUE)) return Checked.Overflow
        return Checked.Value(ceiling.toLong())
    }

    /** Factores sin signo de cualquier tamaño, con el divisor también al azar. */
    private fun ceilingCase(random: Random): Triple<Long, Long, Long> = Triple(
        randomNonNegativeLong(random),
        randomNonNegativeLong(random),
        randomPositiveLong(random),
    )

    private fun ceilingEdgeCase(random: Random): Triple<Long, Long, Long> {
        val factors = EDGES.filter { it >= 0L }
        return Triple(factors.random(random), factors.random(random), EDGE_DIVISORS.random(random))
    }

    /** Cualquier rango: cada número lleva una cantidad de bits al azar, y se cruzan los tamaños. */
    private fun randomCase(random: Random): Triple<Long, Long, Long> =
        Triple(randomLong(random), randomLong(random), randomPositiveLong(random))

    /** Un empate exacto: `a × k / 2k` con `a` impar es media unidad. */
    private fun tieCase(random: Random): Triple<Long, Long, Long> {
        val half = random.nextLong(1L, 1L shl 40)
        val odd = (random.nextLong(0L, 1L shl 40) shl 1) + 1L
        val a = if (random.nextBoolean()) -odd else odd
        return Triple(a, half, half * 2L)
    }

    private fun edgeCase(random: Random): Triple<Long, Long, Long> =
        Triple(EDGES.random(random), EDGES.random(random), EDGE_DIVISORS.random(random))

    private fun randomLong(random: Random): Long {
        val bits = random.nextInt(1, 64)
        val magnitude = random.nextLong() ushr (64 - bits)
        return if (random.nextBoolean()) -magnitude else magnitude
    }

    private fun randomNonNegativeLong(random: Random): Long {
        val bits = random.nextInt(1, 64)
        return random.nextLong() ushr (64 - bits)
    }

    private fun randomPositiveLong(random: Random): Long {
        val bits = random.nextInt(1, 64)
        return (random.nextLong() ushr (64 - bits)).coerceAtLeast(1L)
    }

    private companion object {
        /** Valores en los bordes de 32 y 64 bits, de la raíz de `Long.MAX_VALUE` y de cada tope. */
        val EDGES =
            listOf(
                0L, 1L, -1L, 2L, -2L, 3L, 999L, 1000L, 1001L, -1001L,
                4_294_967_295L, 4_294_967_296L, 4_294_967_297L, -4_294_967_296L,
                3_037_000_499L, 3_037_000_500L, -3_037_000_500L,
                1_000_000_000L, 8_000_000_000L, 1_000_000_000_000L,
                1_000_000_000_000_000L, 9_000_000_000_000_000L,
                Long.MAX_VALUE, Long.MAX_VALUE - 1L, Long.MIN_VALUE, Long.MIN_VALUE + 1L,
            )
        val EDGE_DIVISORS =
            listOf(
                1L, 2L, 3L, 7L, 1000L, 10_000L, 1_000_000L, 4_294_967_296L, 3_037_000_500L,
                8_000_000_000L, Long.MAX_VALUE - 1L, Long.MAX_VALUE,
            )
    }
}
