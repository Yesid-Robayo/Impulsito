package io.github.yesidrobayo.shopledger.domain.rule

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.ExactUnitCost
import io.github.yesidrobayo.shopledger.domain.model.Margin
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El margen contra el costo sin redondear: un precio sugerido nunca muestra un margen por debajo del
 * pedido, y con un costo entero da lo mismo que el margen de siempre.
 */
class ExactMarginTest {
    @Test
    fun `the box of 24 at its suggested price shows the target and not one point less`() {
        val box = ExactUnitCost(Money(10_000L), QuantityMilli(24_000L))

        assertEquals(defined(9_000L), MarginCalculator.exactMargin(Money(4_167L), box))
        // Con el costo redondeado a 417 se veía 89,99 %.
        assertEquals(defined(8_999L), MarginCalculator.margin(Money(4_167L), Money(417L)))
    }

    @Test
    fun `a cost below one minor unit is a cost and not a full margin`() {
        val box = ExactUnitCost(Money(400L), QuantityMilli(1_000_000L))

        // 0,4 por unidad contra un precio de 1: 60 %, no el 100 % de un costo redondeado a cero.
        assertEquals(defined(6_000L), MarginCalculator.exactMargin(Money(1L), box))
    }

    @Test
    fun `a whole cost gives the same margin as always`() {
        val cost = ExactUnitCost.perUnit(Money(1_355L))

        assertEquals(defined(967L), MarginCalculator.exactMargin(Money(1_500L), cost))
        assertEquals(
            MarginCalculator.margin(Money(1_500L), Money(1_355L)),
            MarginCalculator.exactMargin(Money(1_500L), cost),
        )
    }

    @Test
    fun `selling below cost is negative and selling at cost is zero`() {
        val cost = ExactUnitCost.perUnit(Money(1_200L))

        assertEquals(defined(-2_000L), MarginCalculator.exactMargin(Money(1_000L), cost))
        assertEquals(defined(0L), MarginCalculator.exactMargin(Money(1_200L), cost))
    }

    @Test
    fun `without a price there is no margin`() {
        val cost = ExactUnitCost.perUnit(Money(100L))

        assertEquals(Margin.NoData, MarginCalculator.exactMargin(Money.ZERO, cost))
        assertEquals(Margin.NoData, MarginCalculator.exactMargin(Money(-1L), cost))
    }

    @Test
    fun `figures that do not fit are out of range`() {
        val small = ExactUnitCost.perUnit(Money(1L))
        val huge = ExactUnitCost.perUnit(Money(Long.MAX_VALUE / 2))

        assertEquals(Margin.OutOfRange, MarginCalculator.exactMargin(Money(Long.MAX_VALUE), small))
        assertEquals(Margin.OutOfRange, MarginCalculator.exactMargin(Money(1_000L), huge))
    }

    @Test
    fun `a suggested price never shows a margin below the target`() {
        val random = Random(SEED)
        repeat(RUNS) {
            val cost = ExactUnitCost(
                Money(random.nextLong(1L, 1_000_000_000L)),
                QuantityMilli(random.nextLong(1L, 10_000_000L)),
            )
            val target = BasisPoints(random.nextLong(0L, BasisPoints.MAX_TARGET_MARGIN.bp + 1))
            val suggestion =
                MarginCalculator.suggestedPrice(cost, target, BasisPoints.ZERO, Money.ZERO)
            if (suggestion is PriceSuggestion.Suggested) {
                val margin = MarginCalculator.exactMargin(suggestion.price, cost)
                assertTrue(
                    "Costo $cost, objetivo $target, precio ${suggestion.price}: $margin",
                    margin is Margin.Defined && margin.value >= target,
                )
            }
        }
    }

    private fun defined(bp: Long): Margin = Margin.Defined(BasisPoints(bp))

    private companion object {
        const val SEED = 157L
        const val RUNS = 2_000
    }
}
