package io.github.yesidrobayo.shopledger.domain.rule.catalog

import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CatalogFixture
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ProductSearchTest {
    private val fixture = CatalogFixture().apply {
        seedProduct(id = 1L, name = "Jabón Líquido", barcode = "7701234")
        seedProduct(id = 2L, name = "Piña")
        seedProduct(id = 3L, name = "Pina")
    }

    private fun ids(query: String): List<Long> =
        ProductSearch.filter(fixture.tables.products.values.sortedBy { it.id }, query).map { it.id }

    @Test
    fun `jabon finds Jabon Liquido without case or accents`() = runTest {
        assertEquals(listOf(1L), ids("jabon"))
        assertEquals(listOf(1L), ids("  LÍQUIDO "))
    }

    @Test
    fun `the enie is its own letter`() = runTest {
        assertEquals(listOf(3L), ids("pina"))
        assertEquals(listOf(2L), ids("PIÑA"))
    }

    @Test
    fun `a full barcode finds its product and a blank search filters nothing`() = runTest {
        assertEquals(listOf(1L), ids(" 7701234 "))
        assertEquals(emptyList<Long>(), ids("770123"))
        assertEquals(listOf(1L, 2L, 3L), ids("   "))
    }
}
