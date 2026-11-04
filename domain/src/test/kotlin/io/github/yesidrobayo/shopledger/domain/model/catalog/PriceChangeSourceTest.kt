package io.github.yesidrobayo.shopledger.domain.model.catalog

import org.junit.Assert.assertEquals
import org.junit.Test

class PriceChangeSourceTest {
    @Test
    fun `the stored names never change`() {
        assertEquals(
            listOf("INITIAL", "MANUAL", "BULK", "BULK_UNDO", "IMPORT"),
            PriceChangeSource.entries.map { it.name },
        )
        assertEquals(
            listOf("PERCENT", "AMOUNT", "TARGET_MARGIN", "REFERENCE_RATE"),
            BulkPriceMode.entries.map { it.name },
        )
    }

    @Test
    fun `only a bulk update and its undo belong to a batch`() {
        assertEquals(
            listOf(PriceChangeSource.BULK, PriceChangeSource.BULK_UNDO),
            PriceChangeSource.entries.filter { it.isBatch },
        )
    }
}
