package io.github.yesidrobayo.shopledger.domain.model.catalog

import org.junit.Assert.assertEquals
import org.junit.Test

class UnitKindTest {
    @Test
    fun `the stored names never change`() {
        // Se guardan por nombre en la base, y la guarda de la tabla solo admite estos.
        assertEquals(
            listOf("COUNT", "MASS", "VOLUME", "LENGTH", "OTHER"),
            UnitKind.entries.map { it.name },
        )
    }
}
