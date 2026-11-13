package io.github.yesidrobayo.shopledger.domain.rule.catalog

import io.github.yesidrobayo.shopledger.domain.model.Money
import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** El costo de reposición: qué deja una compra, cuándo la pisa y qué fecha lleva. */
class ReplacementCostRulesTest {
    @Test
    fun `a purchase leaves the last line with cost of each product, in purchase order`() {
        val costs = ReplacementCostRules.fromPurchase(
            listOf(1L to Money(1_200), 2L to Money(300), 1L to Money(1_250)),
        )

        assertEquals(mapOf(1L to Money(1_250), 2L to Money(300)), costs)
        assertEquals(listOf(1L, 2L), costs.keys.toList())
    }

    @Test
    fun `a free line does not erase what the purchase says, nor sets a cost alone`() {
        val costs = ReplacementCostRules.fromPurchase(
            listOf(1L to Money(1_200), 1L to Money.ZERO, 2L to Money.ZERO),
        )

        assertEquals(mapOf(1L to Money(1_200)), costs)
    }

    @Test
    fun `a purchase replaces an unknown or older replacement, never a newer one`() {
        val known = Instant.fromEpochMilliseconds(1_000)

        assertTrue(ReplacementCostRules.purchaseReplaces(null, known))
        assertTrue(ReplacementCostRules.purchaseReplaces(known, known))
        assertTrue(
            ReplacementCostRules.purchaseReplaces(known, Instant.fromEpochMilliseconds(1_001)),
        )
        assertFalse(
            ReplacementCostRules.purchaseReplaces(known, Instant.fromEpochMilliseconds(999)),
        )
    }

    @Test
    fun `a manual replacement is dated, and without a value it has no date`() {
        val now = Instant.fromEpochMilliseconds(5_000)

        assertEquals(now, ReplacementCostRules.manualStamp(Money(1), now))
        assertNull(ReplacementCostRules.manualStamp(Money.ZERO, now))
    }
}
