package io.github.yesidrobayo.shopledger.domain.rule.returns

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.returns.LineAmounts
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * El reparto de una línea vendida entre sus devoluciones, con los ejemplos de la regla escritos a
 * mano: de a una unidad, todo de una vez, con impuesto, y con una devolución anulada en medio.
 */
class ReturnSharesTest {
    private val sold = amounts(3_000, net = 10_000, tax = 1_900, cost = 6_000)

    private fun amounts(milli: Long, net: Long, tax: Long = 0, cost: Long = 0) =
        LineAmounts(QuantityMilli(milli), Money(net), Money(tax), Money(cost))

    private fun share(sold: LineAmounts, taken: LineAmounts, milli: Long): ReturnShare =
        (ReturnShares.of(sold, taken, QuantityMilli(milli)) as ReturnShareCheck.Share).share

    private fun LineAmounts.plus(share: ReturnShare, milli: Long) = LineAmounts(
        quantity + QuantityMilli(milli),
        net + share.net,
        tax + share.tax,
        cost + share.cost,
    )

    @Test
    fun returningOneUnitAtATimeGivesTheRuleExampleAndAddsUpExactly() {
        var taken = LineAmounts.NONE
        val nets = ArrayList<Long>()
        repeat(3) {
            val share = share(sold, taken, 1_000)
            nets += share.net.minor
            taken = taken.plus(share, 1_000)
        }
        assertEquals(listOf(3_333L, 3_334L, 3_333L), nets)
        assertEquals(sold, taken)
    }

    @Test
    fun eachShareCarriesItsTaxAndItsTotalIsNetPlusTax() {
        val share = share(sold, LineAmounts.NONE, 1_000)
        assertEquals(ReturnShare(Money(3_333), Money(633), Money(2_000)), share)
        assertEquals(Money(3_966), share.total)
    }

    @Test
    fun returningEverythingAtOnceTakesTheWholeLine() {
        assertEquals(
            ReturnShare(Money(10_000), Money(1_900), Money(6_000)),
            share(sold, LineAmounts.NONE, 3_000),
        )
    }

    @Test
    fun theRestAfterAPartialReturnIsExactlyWhatIsLeft() {
        val first = share(sold, LineAmounts.NONE, 1_000)
        val rest = share(sold, LineAmounts.NONE.plus(first, 1_000), 2_000)
        assertEquals(ReturnShare(Money(6_667), Money(1_267), Money(4_000)), rest)
    }

    @Test
    fun aVoidedReturnInBetweenNeverLetsTheActiveOnesPassTheLine() {
        // Se devuelven dos de a una, se anula la primera y se devuelve el resto: las activas, la
        // segunda y la última, suman exactamente la línea.
        val first = share(sold, LineAmounts.NONE, 1_000)
        val second = share(sold, LineAmounts.NONE.plus(first, 1_000), 1_000)
        val onlySecond = LineAmounts.NONE.plus(second, 1_000)
        val last = share(sold, onlySecond, 2_000)
        assertEquals(Money(3_334), second.net)
        assertEquals(Money(6_666), last.net)
        assertEquals(sold, onlySecond.plus(last, 2_000))
    }

    @Test
    fun aShareIsNeverNegativeEvenWhenTheActiveOnesTookMoreThanTheirPart() {
        // Una línea de 1 en tres unidades: la primera unidad se lleva 0 y la segunda el 1. Anulada
        // la primera, una milésima más da menos que lo ya llevado, y su parte queda en cero.
        val tiny = amounts(3_000, net = 1)
        val taken = amounts(1_000, net = 1)
        assertEquals(Money.ZERO, share(tiny, taken, 1).net)
        assertEquals(Money.ZERO, share(tiny, taken, 1_000).net)
    }

    @Test
    fun aShareNeverTakesMoreThanWhatIsLeftOfTheLine() {
        // Lo ya devuelto dice más dinero que la línea: solo puede venir de una copia dañada, y la
        // parte se queda en cero en vez de dejar el reparto negativo.
        val damaged = amounts(1_000, net = 20_000)
        assertEquals(Money.ZERO, share(sold, damaged, 1_000).net)
    }

    @Test
    fun aFractionOfAWeighedLineGetsItsProportionalShare() {
        val weighed = amounts(2_500, net = 12_500, cost = 7_500)
        assertEquals(
            ReturnShare(Money(1_250), Money.ZERO, Money(750)),
            share(weighed, LineAmounts.NONE, 250),
        )
    }

    @Test
    fun aQuantityThatIsNotPositiveIsRejected() {
        assertEquals(
            ReturnShareCheck.NotPositive,
            ReturnShares.of(sold, LineAmounts.NONE, QuantityMilli.ZERO),
        )
        assertEquals(
            ReturnShareCheck.NotPositive,
            ReturnShares.of(sold, LineAmounts.NONE, QuantityMilli(-1_000)),
        )
    }

    @Test
    fun moreThanWhatIsLeftIsRejectedWithWhatIsLeft() {
        val taken = amounts(2_000, net = 6_667)
        assertEquals(QuantityMilli(1_000), ReturnShares.remaining(sold, taken))
        assertEquals(
            ReturnShareCheck.AboveRemaining(QuantityMilli(1_000)),
            ReturnShares.of(sold, taken, QuantityMilli(1_001)),
        )
    }

    @Test
    fun theLargestLineSplitsWithoutOverflowing() {
        val max = 1_000_000_000_000_000L
        val huge = amounts(1_000_000_000L, net = max, cost = max)
        val half = share(huge, LineAmounts.NONE, 500_000_000L)
        assertEquals(Money(max / 2), half.net)
        assertEquals(Money(max / 2), half.cost)
    }
}
