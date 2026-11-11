package io.github.yesidrobayo.shopledger.domain.rule.sale

import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Repartir un ajuste de toda la venta entre sus líneas, con los ejemplos de las reglas a mano. */
class OrderAdjustmentSharesTest {
    private val limit = Limit.DOCUMENT_TOTAL.max

    @Test
    fun theWorkedExampleSplitsAGlobalDiscountByBase() {
        // 7000, 6000 y 18000 con −1000: −225,8 y −193,5 redondean a −226 y −194, y la de mayor
        // base, que aquí es la última, absorbe el residuo.
        assertEquals(shares(-226, -194, -580), split(-1_000, 7_000, 6_000, 18_000))
    }

    @Test
    fun theLineWithTheLargestBaseAbsorbsTheRemainder() {
        // Con la de mayor base al principio, el residuo es suyo y no de la última.
        assertEquals(shares(-580, -226, -194), split(-1_000, 18_000, 7_000, 6_000))
        // Con un empate, la última de las empatadas: 1,5 redondea a 2 y la otra toma 1.
        assertEquals(shares(2, 1, 0), split(3, 5, 5, 0))
    }

    @Test
    fun aSmallLastLineNeverGoesNegative() {
        // 1, 1, 1 y 0 con −1: un tercio redondea a cero, y la tercera, que empata con la mayor
        // base y es la última de las empatadas, absorbe el −1. La última, con base 0, no se toca.
        assertEquals(shares(0, 0, -1, 0), split(-1, 1, 1, 1, 1 - 1))
    }

    @Test
    fun aRemainderTooLargeForItsLineMovesToTheNextLargestBases() {
        // Once líneas de 1 con −5: −5/11 redondea a cero en las diez primeras, y la última no puede
        // absorber −5. Queda en 0 y el resto pasa a las de mayor base, de la última hacia atrás.
        val bases = LongArray(11) { 1L }

        assertEquals(
            shares(0, 0, 0, 0, 0, 0, -1, -1, -1, -1, -1),
            split(-5, *bases),
        )
    }

    @Test
    fun withoutBasesTheAdjustmentGoesWholeToTheLastLine() {
        assertEquals(shares(0, 0, 500), split(500, 0, 0, 0))
        assertEquals(shares(0), split(0, 0))
        assertEquals(AdjustmentSplit.DiscountAboveBases, split(-1, 0, 0))
    }

    @Test
    fun aDiscountUpToTheSumOfBasesLeavesEveryLineAtZeroOrMore() {
        assertEquals(shares(-7_000, -6_000), split(-13_000, 7_000, 6_000))
        assertEquals(AdjustmentSplit.DiscountAboveBases, split(-13_001, 7_000, 6_000))
        assertEquals(shares(0, 0), split(0, 7_000, 6_000))
    }

    @Test
    fun randomCasesAddUpExactlyAndLeaveNoLineNegative() {
        val random = Random(20261111)
        repeat(5_000) {
            val size = random.nextInt(1, 30)
            val bases = List(size) {
                when (random.nextInt(4)) {
                    0 -> 0L
                    1 -> random.nextLong(1, 10)
                    2 -> random.nextLong(1, 100_000)
                    else -> random.nextLong(1, limit / 50)
                }
            }.map(::Money)
            val baseSum = bases.fold(0L) { sum, base -> sum + base.minor }
            val adjustment = Money(random.nextLong(-baseSum, minOf(baseSum, limit) + 1))

            val result = OrderAdjustmentShares.split(adjustment, bases)

            val parts = (result as AdjustmentSplit.Shares).shares
            assertEquals(bases.size, parts.size)
            assertEquals(adjustment.minor, parts.sumOf { it.minor })
            bases.zip(parts).forEach { (base, share) ->
                assertTrue("$bases $adjustment $parts", base.minor + share.minor >= 0)
            }
        }
    }

    @Test
    fun anImpossibleInputIsAProgrammingError() {
        val wrong = listOf<() -> Unit>(
            { OrderAdjustmentShares.split(Money.ZERO, emptyList()) },
            { split(0, 1, -1) },
            { split(0, limit + 1) },
            { split(limit + 1, 1) },
            { split(-limit - 1, 1) },
        )
        wrong.forEach { assertThrows(IllegalArgumentException::class.java) { it() } }
    }

    @Test
    fun theLargestAdjustmentOnTheLargestBasesStillFits() {
        assertEquals(shares(limit / 2, limit / 2), split(limit, limit, limit))
        assertEquals(shares(-limit, 0), split(-limit, limit, 0))
    }

    private fun split(adjustment: Long, vararg bases: Long): AdjustmentSplit =
        OrderAdjustmentShares.split(Money(adjustment), bases.map(::Money))

    private fun shares(vararg shares: Long) = AdjustmentSplit.Shares(shares.map(::Money))
}
