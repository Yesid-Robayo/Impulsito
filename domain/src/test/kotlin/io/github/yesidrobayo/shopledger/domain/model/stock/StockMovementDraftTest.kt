package io.github.yesidrobayo.shopledger.domain.model.stock

import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import org.junit.Assert.assertEquals
import org.junit.Test

class StockMovementDraftTest {
    @Test
    fun `a recorded draft keeps every field and takes the id the database gave it`() {
        val key = StockKey(productId = 3L, locationId = 2L, lotId = 31L)
        val draft = StockMovementDraft(key, StockMovementType.SALE_VOID, QuantityMilli(4000L), 17L)

        assertEquals(
            StockMovement(
                id = 25L,
                key = key,
                type = StockMovementType.SALE_VOID,
                quantity = QuantityMilli(4000L),
                reversesMovementId = 17L,
            ),
            draft.recordedAs(25L),
        )
    }
}
