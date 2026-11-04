package io.github.yesidrobayo.shopledger.domain.model.catalog

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Money
import org.junit.Assert.assertEquals
import org.junit.Test

class BulkPriceAdjustmentTest {
    @Test
    fun `each adjustment stores the mode of its kind`() {
        assertEquals(
            BulkPriceMode.PERCENT,
            BulkPriceAdjustment.Percent(BasisPoints(1_000L)).mode,
        )
        assertEquals(BulkPriceMode.AMOUNT, BulkPriceAdjustment.Amount(Money(-500L)).mode)
        assertEquals(
            BulkPriceMode.TARGET_MARGIN,
            BulkPriceAdjustment.TargetMargin(BasisPoints(3_000L)).mode,
        )
        assertEquals(
            BulkPriceMode.REFERENCE_RATE,
            BulkPriceAdjustment.ReferenceRate(fxRateMicro = 1_250_000L).mode,
        )
    }
}
