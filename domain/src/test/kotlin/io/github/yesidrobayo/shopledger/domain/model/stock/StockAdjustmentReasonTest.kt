package io.github.yesidrobayo.shopledger.domain.model.stock

import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import org.junit.Assert.assertEquals
import org.junit.Test

class StockAdjustmentReasonTest {
    @Test
    fun `the stored names never change`() {
        assertEquals(
            listOf(
                "COUNT_CORRECTION",
                "DAMAGED",
                "EXPIRED",
                "LOST_OR_STOLEN",
                "OWN_USE",
                "GIFT_OR_SAMPLE",
                "OTHER",
            ),
            StockAdjustmentReason.entries.map { it.name },
        )
    }

    @Test
    fun `only a count and another reason add units`() {
        assertEquals(
            listOf(StockAdjustmentReason.COUNT_CORRECTION, StockAdjustmentReason.OTHER),
            StockAdjustmentReason.entries.filter { it.allowsIncrease },
        )
    }

    @Test
    fun `only another reason requires a note`() {
        assertEquals(
            listOf(StockAdjustmentReason.OTHER),
            StockAdjustmentReason.entries.filter { it.requiresNote },
        )
    }

    @Test
    fun `a count is always a count correction`() {
        val count = StockAdjustmentRequest.Count(1L, QuantityMilli(1_000L))

        assertEquals(StockAdjustmentReason.COUNT_CORRECTION, count.reason)
        assertEquals(null, count.note)
    }
}
