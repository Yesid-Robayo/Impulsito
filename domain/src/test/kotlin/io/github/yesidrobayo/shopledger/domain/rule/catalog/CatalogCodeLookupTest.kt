package io.github.yesidrobayo.shopledger.domain.rule.catalog

import io.github.yesidrobayo.shopledger.domain.model.catalog.ConversionDetails
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CatalogFixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Lo que escribe un lector externo se busca en el catálogo leído, como el escáner. */
class CatalogCodeLookupTest {
    private val fixture = CatalogFixture().apply {
        seedProduct(id = 1L, name = "Aceite 900 ml", barcode = "2900000123458")
        seedProduct(id = 2L, name = "Arroz 1 kg", barcode = "012345678905")
        seedProduct(id = 3L, name = "Jabón de avena")
    }
    private val products = fixture.tables.products.values.sortedBy { it.id }
    private val conversions =
        mapOf(
            1L to mapOf(BOX to box("2900000999992")),
            3L to mapOf(BOX to box(null)),
            // Un producto que ya no se vende: su caja no encuentra nada.
            9L to mapOf(BOX to box("2900000555551")),
        )

    private fun find(code: String) = CatalogCodeLookup.find(products, conversions, code)

    @Test
    fun `a product code finds the product without a unit`() {
        assertEquals(CodeHit(1L, null), find("2900000123458"))
    }

    @Test
    fun `a box code finds its product in boxes`() {
        assertEquals(CodeHit(1L, BOX), find(" 2900000999992 "))
    }

    @Test
    fun `a UPC-A with its leading zero finds the same product`() {
        assertEquals(CodeHit(2L, null), find("0012345678905"))
    }

    @Test
    fun `a name, a blank, an unknown code or a product not for sale find nothing`() {
        assertNull(find("Aceite"))
        assertNull(find("   "))
        assertNull(find("P00000042"))
        assertNull(find("2900000555551"))
    }

    private companion object {
        const val BOX = 5L

        fun box(barcode: String?) =
            ConversionDetails(BOX, 12_000L, null, barcode, forSale = true, forPurchase = false)
    }
}
