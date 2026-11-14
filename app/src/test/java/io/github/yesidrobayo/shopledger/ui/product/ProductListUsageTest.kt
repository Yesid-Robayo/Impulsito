package io.github.yesidrobayo.shopledger.ui.product

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Las filas de la lista con el uso del producto: cada una lleva su uso, para su etiqueta, y el
 * costo que muestra la de un insumo puro en vez de precio y margen.
 */
class ProductListUsageTest {
    private fun usedAs(usage: ProductUsage, stockMode: ProductStockMode, fixed: Long = 0L) =
        product(
            id = 1L,
            name = "Harina",
            salePrice = 3_000L,
            averageCost = 1_200L,
            stockValue = 12_000L,
            onHand = 10_000L,
            stockMode = stockMode,
            fixedUnitCost = fixed,
        ).let { it.copy(details = it.details.copy(usage = usage)) }

    @Test
    fun `each row carries its usage`() {
        for (usage in ProductUsage.entries) {
            val item = ProductListItems.itemOf(usedAs(usage, ProductStockMode.TRACKED), COUNT_UNIT)
            assertEquals(usage, item.usage)
        }
    }

    @Test
    fun `an input with stock shows its average cost`() {
        val input = usedAs(ProductUsage.INPUT, ProductStockMode.TRACKED)
        val item = ProductListItems.itemOf(input, COUNT_UNIT)

        assertEquals(Money(1_200L), item.cost)
    }

    @Test
    fun `without stock it shows the fixed cost, and by components none`() {
        val loose = usedAs(ProductUsage.INPUT, ProductStockMode.UNTRACKED, fixed = 450L)
        assertEquals(Money(450L), ProductListItems.costOf(loose))

        val unknown = usedAs(ProductUsage.INPUT, ProductStockMode.UNTRACKED)
        assertNull(ProductListItems.costOf(unknown))

        val made = usedAs(ProductUsage.INPUT, ProductStockMode.COMPONENTS)
        assertNull(ProductListItems.costOf(made))
    }
}
