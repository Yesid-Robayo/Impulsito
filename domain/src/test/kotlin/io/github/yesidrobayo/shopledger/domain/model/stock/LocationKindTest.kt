package io.github.yesidrobayo.shopledger.domain.model.stock

import org.junit.Assert.assertEquals
import org.junit.Test

class LocationKindTest {
    @Test
    fun `the stored names never change`() {
        // Se guardan por nombre en la base, y la guarda de la tabla solo admite estos.
        assertEquals(
            listOf("STORE", "WAREHOUSE", "STALL", "OTHER"),
            LocationKind.entries.map { it.name },
        )
    }
}
