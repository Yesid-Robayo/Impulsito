package io.github.yesidrobayo.shopledger.domain.usecase.catalog

import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductForm
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CatalogFixture.Companion.ARCHIVED_UNIT_ID
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CatalogFixture.Companion.UNIT_ID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ObserveProductCatalogUseCaseTest {
    private val fixture = CatalogFixture()
    private val observe = ObserveProductCatalogUseCase(fixture.products, fixture.units)

    @Test
    fun `the active list comes by normalized name with every unit, archived ones too`() = runTest {
        fixture.seedProduct(id = 1L, name = "Zanahoria")
        fixture.seedProduct(id = 2L, name = "arroz")
        fixture.seedProduct(id = 3L, name = "Viejo", archived = true)

        val catalog = observe().first()

        assertEquals(listOf(2L, 1L), catalog.products.map { it.id })
        assertEquals(
            setOf(UNIT_ID, ARCHIVED_UNIT_ID),
            catalog.units.keys.intersect(setOf(UNIT_ID, ARCHIVED_UNIT_ID)),
        )
        assertEquals(listOf(3L), observe(ArchiveFilter.ARCHIVED).first().products.map { it.id })
    }

    @Test
    fun `a product saved after reading arrives in the next emission`() = runTest {
        assertEquals(emptyList<Long>(), observe().first().products.map { it.id })

        fixture.create(ProductForm(name = "Arroz"))

        assertEquals(listOf(1L), observe().first().products.map { it.id })
    }
}
