package io.github.yesidrobayo.shopledger.domain.model.sale

import org.junit.Assert.assertEquals
import org.junit.Test

class LineStockEffectTest {
    @Test
    fun `the stored names never change`() {
        assertEquals(
            listOf("DIRECT", "NONE", "COMPONENTS"),
            LineStockEffect.entries.map { it.name },
        )
    }
}
