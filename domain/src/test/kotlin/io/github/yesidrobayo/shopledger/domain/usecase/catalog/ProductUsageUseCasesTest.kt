package io.github.yesidrobayo.shopledger.domain.usecase.catalog

import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductForm
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductUsage
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * El uso de un producto: nace vendiéndose salvo que se diga otro, y cambia libremente, también con
 * historia, porque no es historia. Productos inventados.
 */
class ProductUsageUseCasesTest {
    private val fixture = CatalogFixture()

    private fun savedId(result: SaveProductResult): Long =
        (result as SaveProductResult.Saved).productId

    @Test
    fun `a new product is sold unless it says it is an input`() = runTest {
        val sold = savedId(fixture.create(ProductForm(name = "Pan")))
        val input = savedId(
            fixture.create(ProductForm(name = "Harina", usage = ProductUsage.INPUT)),
        )

        assertEquals(ProductUsage.SALE, fixture.product(sold).details.usage)
        assertEquals(ProductUsage.INPUT, fixture.product(input).details.usage)
    }

    @Test
    fun `the usage changes even with history, and saying nothing keeps it`() = runTest {
        val id = savedId(fixture.create(ProductForm(name = "Azúcar"), QuantityMilli(2_000L)))

        val result = fixture.edit(id, ProductForm(name = "Azúcar", usage = ProductUsage.BOTH))

        assertEquals(SaveProductResult.Saved(id), result)
        assertEquals(ProductUsage.BOTH, fixture.product(id).details.usage)

        fixture.edit(id, ProductForm(name = "Azúcar blanca"))
        assertEquals(ProductUsage.BOTH, fixture.product(id).details.usage)

        fixture.edit(id, ProductForm(name = "Azúcar blanca", usage = ProductUsage.INPUT))
        assertEquals(ProductUsage.INPUT, fixture.product(id).details.usage)
        // El libro no cambia: el uso no es historia.
        assertEquals(1, fixture.movementsOf(id).size)
    }
}
