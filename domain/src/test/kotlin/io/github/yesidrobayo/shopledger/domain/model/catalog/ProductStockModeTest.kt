package io.github.yesidrobayo.shopledger.domain.model.catalog

import org.junit.Assert.assertEquals
import org.junit.Test

class ProductStockModeTest {
    @Test
    fun `the stored names never change`() {
        // Se guardan por nombre en la base: cambiar uno dejaría filas que ninguna constante lee.
        assertEquals(
            listOf("TRACKED", "UNTRACKED", "COMPONENTS"),
            ProductStockMode.entries.map { it.name },
        )
    }
}
