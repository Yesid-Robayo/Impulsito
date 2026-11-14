package io.github.yesidrobayo.shopledger.ui.sale

import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleCatalog
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxMode
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxSettings
import io.github.yesidrobayo.shopledger.ui.product.COUNT_UNIT
import io.github.yesidrobayo.shopledger.ui.product.product
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** El selector de variantes de la venta y la tarjeta del principal. Datos inventados. */
class VariantChoicesTest {
    private val shirt = product(1L, "Camiseta")
    private val smallRed = variant(2L, "S", "Rojo", onHand = 3_000L)
    private val smallBlue = variant(3L, "S", "Azul", onHand = 0L)
    private val mediumRed = variant(4L, "M", "Rojo", onHand = 0L)
    private val catalog = SaleCatalog(
        products = listOf(shirt, smallRed, smallBlue, mediumRed),
        frequent = listOf(shirt),
        archived = emptyList(),
        units = mapOf(COUNT_UNIT.id to COUNT_UNIT),
        methods = emptyList(),
        taxes = TaxSettings(TaxMode.NONE, null, purchaseTaxRecoverable = false),
        taxRates = emptyMap(),
        today = null,
        weekStartDay = null,
    )

    @Test
    fun `each axis offers its values and strikes the ones without stock`() {
        val sheet = VariantChoices.sheet(catalog, shirt, emptyList())

        assertEquals(listOf("Talla", "Color"), sheet.axes.map { it.name })
        assertEquals(listOf("S", "M"), sheet.axes[0].values)
        assertEquals(setOf("M"), sheet.axes[0].outOfStock)
        // Sin talla elegida, el azul solo existe en S, que está agotada.
        assertEquals(listOf("Rojo", "Azul"), sheet.axes[1].values)
        assertEquals(setOf("Azul"), sheet.axes[1].outOfStock)
    }

    @Test
    fun `the second axis follows the first value chosen`() {
        val sheet = VariantChoices.sheet(catalog, shirt, listOf("S"))

        assertEquals(listOf("Rojo", "Azul"), sheet.axes[1].values)
        assertEquals(setOf("Azul"), sheet.axes[1].outOfStock)
        val medium = VariantChoices.sheet(catalog, shirt, listOf("M"))
        assertEquals(listOf("Rojo"), medium.axes[1].values)
    }

    @Test
    fun `the variant is found only when every axis has a value`() {
        assertNull(VariantChoices.match(catalog, shirt.id, listOf("S"), 2))
        assertNull(VariantChoices.match(catalog, shirt.id, listOf(null, "Rojo"), 2))
        assertEquals(smallBlue, VariantChoices.match(catalog, shirt.id, listOf("S", "Azul"), 2))
    }

    @Test
    fun `the tile of the main product shows the sum of its variants`() {
        val content = SaleContentBuilder.content(catalog, emptyList(), "")

        val tile = (content as SaleContent.Products).tiles.single()
        assertEquals(TileStock.Normal(QuantityMilli(3_000L)), tile.stock)
    }

    private fun variant(id: Long, size: String, color: String, onHand: Long): Product {
        val base = product(id, "Camiseta · $size · $color", onHand = onHand)
        return base.copy(
            details = base.details.copy(
                parentProductId = 1L,
                variantAxis1 = "Talla",
                variantAxis2 = "Color",
                variantValue1 = size,
                variantValue2 = color,
            ),
        )
    }
}
