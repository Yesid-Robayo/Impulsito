package io.github.yesidrobayo.shopledger.domain.rule.catalog

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.ExactUnitCost
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.catalog.BulkPriceAdjustment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Los modos de la actualización masiva y el redondeo a múltiplos, con los ejemplos de las reglas. */
class BulkPriceModesTest {
    private val noScale: ReferenceScale? = null

    private fun outcome(
        price: Long,
        adjustment: BulkPriceAdjustment,
        step: Long = 0L,
        subject: BulkSubject = BulkSubject(Money(price), cost = null),
        scale: ReferenceScale? = noScale,
    ) = BulkPriceRules.outcomeOf(subject, adjustment, Money(step), scale)

    private fun newPrice(outcome: BulkPriceOutcome): Money =
        (outcome as BulkPriceOutcome.Changes).newPrice

    @Test
    fun `3500 plus 250 is 3750`() {
        assertEquals(
            Money(3_750L),
            newPrice(outcome(3_500L, BulkPriceAdjustment.Amount(Money(250L)))),
        )
    }

    @Test
    fun `a target margin of 30 percent on a cost of 1500 is 2143`() {
        val subject = BulkSubject(Money(1_800L), null, ExactUnitCost.perUnit(Money(1_500L)))

        val changes =
            outcome(
                1_800L,
                BulkPriceAdjustment.TargetMargin(BasisPoints(3_000L)),
                subject = subject,
            )

        assertEquals(Money(2_143L), newPrice(changes))
        assertFalse((changes as BulkPriceOutcome.Changes).distorted)
    }

    @Test
    fun `with the tax inside the price, 1429 at 21 percent is 1730`() {
        val subject = BulkSubject(
            Money(1_000L),
            null,
            ExactUnitCost.perUnit(Money(1_000L)),
            includedTaxRate = BasisPoints(2_100L),
        )

        val changes =
            outcome(
                1_000L,
                BulkPriceAdjustment.TargetMargin(BasisPoints(3_000L)),
                subject = subject,
            )

        assertEquals(Money(1_730L), newPrice(changes))
    }

    @Test
    fun `1250 of reference at 4150,50 with a book without decimals is 51881`() {
        val subject = BulkSubject(Money(40_000L), null, referencePrice = Money(1_250L))

        val changes = outcome(
            40_000L,
            BulkPriceAdjustment.ReferenceRate(4_150_500_000L),
            subject = subject,
            scale = ReferenceScale(currencyDecimals = 0, referenceDecimals = 2),
        )

        assertEquals(Money(51_881L), newPrice(changes))
    }

    @Test
    fun `the step rounds up to its multiple for each currency`() {
        // Dos decimales y paso 5: 1234 da 1235. Cero decimales y paso 100: 13338 da 13400. Tres
        // decimales y paso 250: 1501 da 1750.
        assertEquals(
            Money(1_235L),
            newPrice(outcome(1_000L, BulkPriceAdjustment.Amount(Money(234L)), step = 5L)),
        )
        assertEquals(
            Money(13_400L),
            newPrice(outcome(12_350L, BulkPriceAdjustment.Percent(BasisPoints(800L)), step = 100L)),
        )
        assertEquals(
            Money(1_750L),
            newPrice(outcome(1_500L, BulkPriceAdjustment.Amount(Money(1L)), step = 250L)),
        )
    }

    @Test
    fun `each reason to leave a price out`() {
        assertEquals(
            BulkPriceOutcome.Excluded(BulkExclusion.WOULD_BE_ZERO),
            outcome(3_500L, BulkPriceAdjustment.Amount(Money(-4_000L))),
        )
        assertEquals(
            BulkPriceOutcome.Excluded(BulkExclusion.ABOVE_LIMIT),
            outcome(999_999_999_999L, BulkPriceAdjustment.Amount(Money(2L))),
        )
        assertEquals(
            BulkPriceOutcome.Excluded(BulkExclusion.NO_COST),
            outcome(3_500L, BulkPriceAdjustment.TargetMargin(BasisPoints(3_000L))),
        )
        val huge = BulkSubject(Money(10L), null, ExactUnitCost.perUnit(Money(999_999_999_999L)))
        assertEquals(
            BulkPriceOutcome.Excluded(BulkExclusion.ABOVE_LIMIT),
            outcome(10L, BulkPriceAdjustment.TargetMargin(BasisPoints(9_900L)), subject = huge),
        )
        val scale = ReferenceScale(0, 2)
        val rate = BulkPriceAdjustment.ReferenceRate(4_150_500_000L)
        assertEquals(
            BulkPriceOutcome.Excluded(BulkExclusion.NO_REFERENCE_PRICE),
            outcome(10L, rate, scale = scale),
        )
        assertEquals(
            BulkPriceOutcome.Excluded(BulkExclusion.NO_REFERENCE_PRICE),
            outcome(
                10L,
                rate,
                subject = BulkSubject(Money(10L), null, referencePrice = Money.ZERO),
                scale = scale,
            ),
        )
        assertEquals(
            BulkPriceOutcome.Excluded(BulkExclusion.NO_REFERENCE_PRICE),
            outcome(10L, rate, subject = BulkSubject(Money(10L), null, referencePrice = Money(1L))),
        )
        assertEquals(
            BulkPriceOutcome.Excluded(BulkExclusion.ABOVE_LIMIT),
            outcome(
                10L,
                BulkPriceAdjustment.ReferenceRate(1_000_000_000_000_000L),
                subject = BulkSubject(Money(10L), null, referencePrice = Money(999_999_999_999L)),
                scale = ReferenceScale(4, 0),
            ),
        )
        assertEquals(
            BulkPriceOutcome.Excluded(BulkExclusion.NO_PRICE),
            outcome(0L, BulkPriceAdjustment.Amount(Money(1L))),
        )
    }

    @Test
    fun `only a percent can be distorted, and a new price equal to the old one is the same`() {
        val amount = outcome(
            80L,
            BulkPriceAdjustment.Amount(Money(5L)),
            step = 500L,
        ) as BulkPriceOutcome.Changes
        assertFalse(amount.distorted)
        // 2000 − 1 = 1999, que sube al paso de 1000: queda igual.
        assertEquals(
            BulkPriceOutcome.Same,
            outcome(2_000L, BulkPriceAdjustment.Amount(Money(-1L)), step = 1_000L),
        )
    }

    @Test
    fun `an adjustment out of its range or a negative step is a programming error`() {
        val subject = BulkSubject(Money(1_000L), null)
        val tooBig = BulkPriceAdjustment.Percent(BasisPoints(Long.MAX_VALUE))

        assertThrows(IllegalArgumentException::class.java) {
            BulkPriceRules.outcomeOf(subject, tooBig, Money.ZERO, null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BulkPriceRules.outcomeOf(
                subject,
                BulkPriceAdjustment.Percent(BasisPoints(100L)),
                Money(-1L),
                null,
            )
        }
    }

    @Test
    fun `each calculation has its limits`() {
        assertTrue(BulkPriceRules.isValid(BulkPriceAdjustment.Amount(Money(-1_000_000_000_000L))))
        assertFalse(BulkPriceRules.isValid(BulkPriceAdjustment.Amount(Money.ZERO)))
        assertFalse(BulkPriceRules.isValid(BulkPriceAdjustment.Amount(Money(1_000_000_000_001L))))
        assertFalse(BulkPriceRules.isValid(BulkPriceAdjustment.Amount(Money(-1_000_000_000_001L))))
        assertTrue(BulkPriceRules.isValid(BulkPriceAdjustment.TargetMargin(BasisPoints.ZERO)))
        assertTrue(BulkPriceRules.isValid(BulkPriceAdjustment.TargetMargin(BasisPoints(9_900L))))
        assertFalse(BulkPriceRules.isValid(BulkPriceAdjustment.TargetMargin(BasisPoints(-1L))))
        assertFalse(BulkPriceRules.isValid(BulkPriceAdjustment.TargetMargin(BasisPoints(9_901L))))
        assertTrue(BulkPriceRules.isValid(BulkPriceAdjustment.ReferenceRate(1L)))
        assertFalse(BulkPriceRules.isValid(BulkPriceAdjustment.ReferenceRate(0L)))
        assertFalse(
            BulkPriceRules.isValid(BulkPriceAdjustment.ReferenceRate(1_000_000_000_000_001L)),
        )
        assertFalse(BulkPriceRules.isValid(BulkPriceAdjustment.Percent(BasisPoints.ZERO)))
    }
}
