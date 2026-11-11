package io.github.yesidrobayo.shopledger.domain.rule.sale

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.ConversionDetails
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.usecase.sale.product
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * La venta en edición con líneas en otra unidad: cobra por unidad entera de esa unidad, dice cuánto
 * sale del stock en la unidad base y marca la línea cuya unidad ya no se vende. Montos inventados.
 */
class SaleCartUnitsTest {
    private val box = 3L
    private val gram = 4L
    private val kilogram = 2L
    private val soap = product(1, salePrice = 500)
    private val cheese = product(2, salePrice = 20_000, unitId = kilogram)
    private val products = mapOf(soap.id to soap, cheese.id to cheese)
    private val conversions = mapOf(
        soap.id to mapOf(box to ConversionDetails(box, 24_000, null, null, true, false)),
        cheese.id to mapOf(gram to ConversionDetails(gram, 1, null, null, true, false)),
    )

    private fun preview(vararg lines: SaleLineRequest) =
        SaleCart.preview(lines.toList(), products, setOf(kilogram), conversions = conversions)

    @Test
    fun aBoxIsChargedByTheBoxAndCountsAsItsBoxes() {
        val result = preview(SaleLineRequest(soap.id, QuantityMilli(2_000), unitId = box))

        val line = result.lines.single()
        assertEquals(Money(12_000), line.unitPrice)
        assertEquals(QuantityMilli(48_000), line.baseQuantity)
        assertEquals(Money(24_000), result.total)
        assertEquals(2L, result.itemCount)
    }

    @Test
    fun gramsOfAProductByTheKiloCountAsOneProduct() {
        val result = preview(SaleLineRequest(cheese.id, QuantityMilli(250_000), unitId = gram))

        assertEquals(QuantityMilli(250), result.lines.single().baseQuantity)
        assertEquals(Money(5_000), result.total)
        assertEquals(1L, result.itemCount)
    }

    @Test
    fun aUnitNoLongerSoldMarksTheLineAndTheSaleIsNotCharged() {
        val result = preview(SaleLineRequest(soap.id, QuantityMilli(1_000), unitId = gram))

        assertEquals(CartLineIssue.UNIT_UNAVAILABLE, result.lines.single().issue)
        assertNull(result.lines.single().baseQuantity)
        assertNull(result.total)
    }

    @Test
    fun aComputedPriceOverTheLimitOrAQuantityThatRoundsToNothingIsMarked() {
        val dear = product(5, salePrice = 1_000_000_000_000)
        val tooDear = SaleCart.preview(
            listOf(SaleLineRequest(dear.id, QuantityMilli(1_000), unitId = box)),
            mapOf(dear.id to dear),
            emptySet(),
            conversions = mapOf(dear.id to conversions.getValue(soap.id)),
        )
        val nothing = preview(SaleLineRequest(cheese.id, QuantityMilli(400), unitId = gram))

        assertEquals(CartLineIssue.AMOUNT_TOO_LARGE, tooDear.lines.single().issue)
        assertEquals(CartLineIssue.AMOUNT_TOO_LARGE, nothing.lines.single().issue)
    }

    @Test
    fun aWrittenPriceSellsWhatTheComputedOneCannotAndIsKeptOnAnUnavailableUnit() {
        val dear = product(5, salePrice = 1_000_000_000_000)
        val written = SaleCart.preview(
            listOf(SaleLineRequest(dear.id, QuantityMilli(1_000), Money(9_000), unitId = box)),
            mapOf(dear.id to dear),
            emptySet(),
            conversions = mapOf(dear.id to conversions.getValue(soap.id)),
        )
        val unavailable =
            preview(SaleLineRequest(soap.id, QuantityMilli(1_000), Money(700), unitId = gram))

        assertEquals(Money(9_000), written.total)
        assertEquals(Money(700), unavailable.lines.single().unitPrice)
    }

    @Test
    fun aMissingProductCountsOnceAndKeepsItsWrittenPrice() {
        val result = preview(SaleLineRequest(9, QuantityMilli(3_000), Money(700)))

        assertEquals(Money(700), result.lines.single().unitPrice)
        assertEquals(CartLineIssue.PRODUCT_UNAVAILABLE, result.lines.single().issue)
        assertEquals(1L, result.itemCount)
    }
}
