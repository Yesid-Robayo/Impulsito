package io.github.yesidrobayo.shopledger.ui.product

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Margin
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.ui.components.ProductRowTrailing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProductListItemsTest {
    private val units = mapOf(COUNT_UNIT.id to COUNT_UNIT)

    private fun items(content: ProductListContent): List<ProductListItem> =
        (content as ProductListContent.Products).sections.flatMap { it.items }

    @Test
    fun `an empty catalog is the empty state, whatever is searched`() {
        assertEquals(
            ProductListContent.EmptyCatalog,
            ProductListItems.build(emptyList(), units, ""),
        )
        assertEquals(
            ProductListContent.EmptyCatalog,
            ProductListItems.build(emptyList(), units, "x"),
        )
    }

    @Test
    fun `search ignores case and accents but keeps the enie`() {
        val catalog = listOf(product(1L, "Jabón Líquido"), product(2L, "Piña"), product(3L, "Pina"))

        assertEquals(
            listOf(1L),
            items(ProductListItems.build(catalog, units, "jabon")).map {
                it.id
            },
        )
        assertEquals(
            listOf(1L),
            items(ProductListItems.build(catalog, units, " LÍQUIDO ")).map {
                it.id
            },
        )
        assertEquals(
            listOf(3L),
            items(ProductListItems.build(catalog, units, "pina")).map {
                it.id
            },
        )
        assertEquals(
            listOf(2L),
            items(ProductListItems.build(catalog, units, "piña")).map {
                it.id
            },
        )
    }

    @Test
    fun `a full barcode finds its product, and a blank search shows everything`() {
        val catalog = listOf(product(1L, "Arroz", barcode = "7701234"), product(2L, "Aceite"))

        assertEquals(
            listOf(1L),
            items(ProductListItems.build(catalog, units, " 7701234 ")).map {
                it.id
            },
        )
        assertEquals(
            listOf(1L, 2L),
            items(ProductListItems.build(catalog, units, "   ")).map {
                it.id
            },
        )
    }

    @Test
    fun `a result carries the search it was built for`() {
        val catalog = listOf(product(1L, "Arroz"))

        assertEquals(
            "arr",
            (ProductListItems.build(catalog, units, "arr") as ProductListContent.Products).query,
        )
        assertEquals(
            "",
            (ProductListItems.build(catalog, units, "") as ProductListContent.Products).query,
        )
    }

    @Test
    fun `no match offers the trimmed text to create the product`() {
        val catalog = listOf(product(1L, "Aceite"))

        assertEquals(
            ProductListContent.NoResults("acite"),
            ProductListItems.build(catalog, units, " acite "),
        )
    }

    @Test
    fun `the stock line says normal, low, out, negative or no stock`() {
        val catalog = listOf(
            product(1L, "A", onHand = 23_000L, minStock = 5_000L),
            product(2L, "B", onHand = 5_000L, minStock = 5_000L),
            product(3L, "C", onHand = 0L, minStock = 0L),
            product(4L, "D", onHand = -2_000L),
            product(5L, "E", stockMode = ProductStockMode.UNTRACKED),
            product(6L, "F", stockMode = ProductStockMode.COMPONENTS),
        )

        assertEquals(
            listOf(
                ProductListStock.Normal(QuantityMilli(23_000L)),
                ProductListStock.Low(QuantityMilli(5_000L)),
                ProductListStock.Out,
                ProductListStock.Negative(QuantityMilli(-2_000L)),
                ProductListStock.NoStock,
                ProductListStock.NoStock,
            ),
            items(ProductListItems.build(catalog, units, "")).map { it.stock },
        )
    }

    @Test
    fun `price and margin follow the catalog cost, and zero means no price or no cost`() {
        val catalog = listOf(
            // 5000 de precio y 3800 de costo: margen de 24,0 %.
            product(1L, "A", salePrice = 5_000L, averageCost = 3_800L),
            // Una caja de 24 por 10000: el costo exacto es 416,67, que redondea a 417, y el margen
            // sobre 1000 es 58,3 %.
            product(
                2L,
                "B",
                salePrice = 1_000L,
                averageCost = 417L,
                stockValue = 10_000L,
                onHand = 24_000L,
            ),
            product(3L, "C", averageCost = 1_200L),
            product(4L, "D", salePrice = 2_000L),
            product(
                5L,
                "E",
                salePrice = 8_000L,
                stockMode = ProductStockMode.UNTRACKED,
                fixedUnitCost = 2_000L,
            ),
            product(6L, "F", salePrice = 9_000L, stockMode = ProductStockMode.COMPONENTS),
        )

        val trailing = items(ProductListItems.build(catalog, units, "")).map { it.trailing }

        assertEquals(
            listOf(
                ProductRowTrailing.Catalog(
                    Money(5_000L),
                    Margin.Defined(BasisPoints(2_400L)),
                    true,
                ),
                ProductRowTrailing.Catalog(
                    Money(1_000L),
                    Margin.Defined(BasisPoints(5_830L)),
                    true,
                ),
                ProductRowTrailing.Catalog(null, Margin.NoData, true),
                ProductRowTrailing.Catalog(
                    Money(2_000L),
                    Margin.Defined(BasisPoints(10_000L)),
                    false,
                ),
                ProductRowTrailing.Catalog(
                    Money(8_000L),
                    Margin.Defined(BasisPoints(7_500L)),
                    true,
                ),
                ProductRowTrailing.Catalog(Money(9_000L), Margin.NoData, true),
            ),
            trailing,
        )
    }

    @Test
    fun `up to 50 products go without headers, and more go by initial`() {
        val fifty = (1L..50L).map { product(it, "Producto ${it.toString().padStart(2, '0')}") }
        val few = ProductListItems.build(fifty, units, "") as ProductListContent.Products
        assertEquals(1, few.sections.size)
        assertNull(few.sections.single().initial)

        val many = fifty + product(51L, "Ñame") + product(52L, "7 granos") + product(53L, "arroz")
        val sorted = many.sortedBy { it.details.normalizedName }
        val grouped = ProductListItems.build(sorted, units, "") as ProductListContent.Products
        assertEquals(listOf("#", "A", "P", "Ñ"), grouped.sections.map { it.initial })
        assertEquals(53, grouped.sections.sumOf { it.items.size })
    }

    @Test
    fun `a product keeps its unit, even an unknown one`() {
        val row = ProductListItems.itemOf(product(1L, "A"), COUNT_UNIT)
        assertEquals(COUNT_UNIT, row.unit)
        assertNull(ProductListItems.itemOf(product(1L, "A"), null).unit)
    }
}
