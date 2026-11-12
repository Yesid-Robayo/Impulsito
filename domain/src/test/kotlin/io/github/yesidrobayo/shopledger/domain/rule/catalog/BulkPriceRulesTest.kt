package io.github.yesidrobayo.shopledger.domain.rule.catalog

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Margin
import io.github.yesidrobayo.shopledger.domain.model.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** La actualización masiva por porcentaje, con los ejemplos de la regla. */
class BulkPriceRulesTest {
    private fun change(price: Long, percent: Long, step: Long = 0L, cost: Long? = 0L) =
        BulkPriceRules.outcome(
            Money(price),
            cost?.let(::Money),
            BasisPoints(percent),
            Money(step),
        )

    @Test
    fun `plus 8 percent with step 100 takes 12350 to 13400`() {
        val outcome = change(12_350L, 800L, step = 100L, cost = 8_000L)

        val changes = outcome as BulkPriceOutcome.Changes
        // 12350 × 1,08 = 13338, que sube al paso de 100.
        assertEquals(Money(13_400L), changes.newPrice)
        assertEquals(Money(12_350L), changes.oldPrice)
        assertFalse(changes.negativeMargin)
        assertFalse(changes.distorted)
    }

    @Test
    fun `plus 80 instead of plus 8 is what undo protects from`() {
        val changes = change(12_350L, 8_000L, step = 100L) as BulkPriceOutcome.Changes

        assertEquals(Money(22_300L), changes.newPrice)
    }

    @Test
    fun `80 with plus 5 percent and step 500 is marked as distorted`() {
        val changes = change(80L, 500L, step = 500L) as BulkPriceOutcome.Changes

        assertEquals(Money(500L), changes.newPrice)
        assertTrue(changes.distorted)
    }

    @Test
    fun `without a step the result is rounded half away from zero`() {
        // 999 × 1,05 = 1048,95.
        assertEquals(Money(1_049L), (change(999L, 500L) as BulkPriceOutcome.Changes).newPrice)
        // 1000 × 0,9 = 900 exacto: un paso que ya es múltiplo no cambia nada.
        assertEquals(
            Money(900L),
            (change(1_000L, -1_000L, step = 100L) as BulkPriceOutcome.Changes).newPrice,
        )
    }

    @Test
    fun `a new price below the cost is a negative margin`() {
        val changes = change(10_000L, -2_500L, cost = 8_000L) as BulkPriceOutcome.Changes

        assertEquals(Money(7_500L), changes.newPrice)
        assertTrue(changes.negativeMargin)
        assertTrue(changes.margin is Margin.Defined)
    }

    @Test
    fun `without own cost there is no margin`() {
        val changes = change(10_000L, 1_000L, cost = null) as BulkPriceOutcome.Changes

        assertEquals(Margin.NoData, changes.margin)
        assertFalse(changes.negativeMargin)
    }

    @Test
    fun `no price, zero, above the limit and the same price are excluded or left alone`() {
        assertEquals(
            BulkPriceOutcome.Excluded(BulkExclusion.NO_PRICE),
            change(0L, 1_000L),
        )
        // Con −99,99 %, un precio menor que 5000 da cero, que es sin precio y no una rebaja.
        assertEquals(
            BulkPriceOutcome.Excluded(BulkExclusion.WOULD_BE_ZERO),
            change(4_999L, -9_999L),
        )
        assertEquals(
            BulkPriceOutcome.Excluded(BulkExclusion.ABOVE_LIMIT),
            change(1_000_000_000_000L, 1L),
        )
        assertEquals(
            BulkPriceOutcome.Excluded(BulkExclusion.ABOVE_LIMIT),
            change(999_999_999_999L, 1L, step = 999_999_999_999L),
        )
        // 1 × 1,0001 = 1,0001, que redondea a 1: igual que antes.
        assertEquals(BulkPriceOutcome.Same, change(1L, 1L))
    }

    @Test
    fun `a huge step or a price that does not fit is above the limit`() {
        assertEquals(
            BulkPriceOutcome.Excluded(BulkExclusion.ABOVE_LIMIT),
            change(1_000L, 1_000L, step = Long.MAX_VALUE),
        )
        assertEquals(
            BulkPriceOutcome.Excluded(BulkExclusion.ABOVE_LIMIT),
            change(Long.MAX_VALUE, 1L),
        )
    }

    @Test
    fun `the percent limits are valid and zero is not`() {
        assertTrue(BulkPriceRules.isValidPercent(BasisPoints.MIN_BULK_PERCENT))
        assertTrue(BulkPriceRules.isValidPercent(BasisPoints.MAX_BULK_PERCENT))
        assertFalse(BulkPriceRules.isValidPercent(BasisPoints.ZERO))
        assertFalse(BulkPriceRules.isValidPercent(BasisPoints(-10_000L)))
        assertFalse(BulkPriceRules.isValidPercent(BasisPoints(100_000L)))
        assertThrows(IllegalArgumentException::class.java) { change(1_000L, 0L) }
        assertThrows(IllegalArgumentException::class.java) { change(1_000L, 100L, step = -1L) }
    }

    @Test
    fun `the largest rise on the largest price still fits`() {
        val changes = change(999_999_999L, 99_999L) as BulkPriceOutcome.Changes

        assertEquals(Money(10_999_899_989L), changes.newPrice)
        assertFalse(changes.distorted)
    }
}
