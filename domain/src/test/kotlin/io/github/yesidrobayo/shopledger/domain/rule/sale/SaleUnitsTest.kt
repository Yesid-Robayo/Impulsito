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
 * La unidad de una línea de venta: la base, o una conversión de venta con su factor y su precio, y
 * la cantidad del libro en la unidad base. Los ejemplos de la conversión, con montos inventados.
 */
class SaleUnitsTest {
    private val unitBase = 1L
    private val box = 3L
    private val gram = 4L
    private val soap = product(1, salePrice = 500, unitId = unitBase)

    private fun details(unitId: Long, factor: Long, price: Long? = null, forSale: Boolean = true) =
        ConversionDetails(unitId, factor, price?.let(::Money), null, forSale, true)

    private fun line(unitId: Long?) = SaleLineRequest(1, QuantityMilli(1_000), unitId = unitId)

    @Test
    fun withoutAUnitOrWithTheBaseItIsTheUsualLine() {
        val expected = SaleUnitResult.Resolved(SaleUnit(unitBase, 1_000, Money(500)))

        assertEquals(expected, SaleUnits.of(line(null), soap, emptyMap()))
        assertEquals(expected, SaleUnits.of(line(unitBase), soap, emptyMap()))
    }

    @Test
    fun aBoxWithoutItsOwnPriceCostsTheProductPriceTimesItsFactor() {
        val result = SaleUnits.of(line(box), soap, mapOf(box to details(box, 24_000)))

        assertEquals(SaleUnitResult.Resolved(SaleUnit(box, 24_000, Money(12_000))), result)
    }

    @Test
    fun aBoxWithItsOwnPriceUsesIt() {
        val result = SaleUnits.of(line(box), soap, mapOf(box to details(box, 24_000, 10_000)))

        assertEquals(SaleUnitResult.Resolved(SaleUnit(box, 24_000, Money(10_000))), result)
    }

    @Test
    fun aGramOfAProductByTheKiloRoundsItsPrice() {
        // 20555 el kilo da 20,555 el gramo, que redondea a 21.
        val cheese = product(2, salePrice = 20_555, unitId = 2)

        val result = SaleUnits.of(line(gram), cheese, mapOf(gram to details(gram, 1)))

        assertEquals(SaleUnitResult.Resolved(SaleUnit(gram, 1, Money(21))), result)
    }

    @Test
    fun aUnitWithoutASaleConversionIsUnavailable() {
        assertEquals(SaleUnitResult.Unavailable, SaleUnits.of(line(box), soap, emptyMap()))
        assertEquals(
            SaleUnitResult.Unavailable,
            SaleUnits.of(line(box), soap, mapOf(box to details(box, 24_000, forSale = false))),
        )
    }

    @Test
    fun aComputedPriceOverTheLimitLeavesNoListPrice() {
        val dear = product(1, salePrice = 1_000_000_000_000)

        assertEquals(
            SaleUnitResult.Resolved(SaleUnit(box, 24_000, null)),
            SaleUnits.of(line(box), dear, mapOf(box to details(box, 24_000))),
        )
    }

    @Test
    fun theLedgerQuantityIsInTheBaseUnit() {
        val base = SaleUnit(unitBase, 1_000, Money(500))
        val boxOf24 = SaleUnit(box, 24_000, Money(12_000))
        val oneGram = SaleUnit(gram, 1, Money(20))

        assertEquals(QuantityMilli(2_000), SaleUnits.baseQuantity(QuantityMilli(2_000), base))
        assertNull(SaleUnits.baseQuantity(QuantityMilli.ZERO, base))
        // Tres cajas de 24 son 72 unidades, y 250 gramos son 0,250 kg.
        assertEquals(QuantityMilli(72_000), SaleUnits.baseQuantity(QuantityMilli(3_000), boxOf24))
        assertEquals(QuantityMilli(250), SaleUnits.baseQuantity(QuantityMilli(250_000), oneGram))
        // Menos de medio gramo no llega a una milésima de kilo: no se vende nada.
        assertNull(SaleUnits.baseQuantity(QuantityMilli(400), oneGram))
    }
}
