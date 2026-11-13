package io.github.yesidrobayo.shopledger.domain.rule.pricing

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.ConversionDetails
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductDetails
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceList
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceListPricing
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleCatalog
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxMode
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxSettings
import io.github.yesidrobayo.shopledger.domain.rule.sale.CartPricing
import io.github.yesidrobayo.shopledger.domain.rule.sale.SaleUnit
import io.github.yesidrobayo.shopledger.domain.rule.sale.SaleUnitResult
import io.github.yesidrobayo.shopledger.domain.rule.sale.SaleUnits
import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El precio de lista de una línea de venta, con el orden de la regla: en la unidad base el de la
 * lista; en otra unidad, el propio de la conversión solo con la inicial, y si no, el de la lista por
 * el factor. Los valores se escriben a mano.
 */
class SalePriceListTest {
    private val lists = listOf(
        PriceList(DEFAULT, "DEFAULT", null, PriceListPricing.Fixed, null),
        PriceList(
            RELATIVE,
            null,
            "Especial",
            PriceListPricing.Relative(DEFAULT, BasisPoints(-1_500L)),
            null,
        ),
        PriceList(LOOP, null, "Rota", PriceListPricing.Relative(LOOP, BasisPoints(0L)), null),
        PriceList(OLD, null, "Vieja", PriceListPricing.Fixed, Instant.fromEpochMilliseconds(1L)),
    )
    private val byId = lists.associateBy { it.id }
    private val rice = product(salePrice = 10_000L)

    @Test
    fun `the chosen list gives the price and says whether it is the initial one`() {
        assertEquals(
            LinePriceList(Money(8_500L), isDefault = false),
            SalePriceList(RELATIVE, byId, emptyMap()).priceOf(rice),
        )
        assertEquals(
            LinePriceList(Money(10_000L), isDefault = true),
            SalePriceList(DEFAULT, byId, emptyMap()).priceOf(rice),
        )
        // Una cadena rota no da precio: la línea solo se vende con uno escrito.
        assertEquals(
            LinePriceList(null, isDefault = false),
            SalePriceList(LOOP, byId, emptyMap()).priceOf(rice),
        )
        assertFalse(SalePriceList(99L, byId, emptyMap()).isDefault)
        assertEquals(LinePriceList(Money(10_000L), true), LinePriceList.default(rice))
    }

    @Test
    fun `a box has its own price only in the initial list, else the list price times the factor`() {
        val box = mapOf(BOX to ConversionDetails(BOX, 24_000L, Money(200_000L), null, true, true))
        val bare = mapOf(BOX to ConversionDetails(BOX, 24_000L, null, null, true, true))
        val line = SaleLineRequest(rice.id, QuantityMilli(1_000L), unitId = BOX)

        assertEquals(Money(200_000L), priceOf(SaleUnits.of(line, rice, box)))
        // 8500 × 24 = 204000.
        assertEquals(
            Money(204_000L),
            priceOf(SaleUnits.of(line, rice, box, LinePriceList(Money(8_500L), false))),
        )
        assertEquals(
            Money(204_000L),
            priceOf(SaleUnits.of(line, rice, bare, LinePriceList(Money(8_500L), true))),
        )
        assertNull(priceOf(SaleUnits.of(line, rice, bare, LinePriceList(null, false))))
        // En la base, el precio de la lista tal cual, también sin precio.
        val base = SaleLineRequest(rice.id, QuantityMilli(1_000L))
        assertEquals(
            Money(8_500L),
            priceOf(SaleUnits.of(base, rice, bare, LinePriceList(Money(8_500L), false))),
        )
        assertNull(priceOf(SaleUnits.of(base, rice, bare, LinePriceList(null, false))))
    }

    @Test
    fun `the cart pricing uses the chosen list or the sale price`() {
        assertEquals(LinePriceList(Money(10_000L), true), CartPricing().listOf(rice))
        assertEquals(
            LinePriceList(Money(8_500L), false),
            CartPricing(priceList = SalePriceList(RELATIVE, byId, emptyMap())).listOf(rice),
        )
    }

    @Test
    fun `the sale catalog offers the active lists and resolves only a usable one`() {
        val catalog = SaleCatalog(
            products = emptyList(),
            frequent = emptyList(),
            archived = emptyList(),
            units = emptyMap(),
            methods = emptyList(),
            taxes = TaxSettings(TaxMode.NONE, null, purchaseTaxRecoverable = false),
            taxRates = emptyMap(),
            today = null,
            weekStartDay = null,
            priceLists = lists,
            ownPrices = mapOf(rice.id to mapOf(RELATIVE to Money(1L))),
        )

        assertEquals(listOf(DEFAULT, RELATIVE, LOOP), catalog.activePriceLists.map { it.id })
        assertNull(catalog.salePriceList(null))
        assertNull(catalog.salePriceList(DEFAULT))
        assertNull(catalog.salePriceList(OLD))
        assertNull(catalog.salePriceList(99L))
        val chosen = requireNotNull(catalog.salePriceList(RELATIVE))
        assertEquals(RELATIVE, chosen.listId)
        assertTrue(chosen.ownPrices.containsKey(rice.id))
    }

    private fun priceOf(result: SaleUnitResult): Money? =
        ((result as SaleUnitResult.Resolved).unit as SaleUnit).listPrice

    private fun product(salePrice: Long) = Product(
        id = 7L,
        details = ProductDetails(
            "Arroz", "arroz", null, null, UNIT, ProductStockMode.TRACKED, false, Money.ZERO,
            QuantityMilli.ZERO, null, null, null, null, null, null, null, null, null, null, null,
            null,
        ),
        salePrice = Money(salePrice),
        initialCost = Money.ZERO,
        averageCost = Money.ZERO,
        stockValue = Money.ZERO,
        onHand = QuantityMilli.ZERO,
        replacementCost = Money.ZERO,
        replacementCostAt = null,
        priceReviewSince = null,
        archivedAt = null,
        createdAt = Instant.fromEpochMilliseconds(0L),
        updatedAt = Instant.fromEpochMilliseconds(0L),
    )

    private companion object {
        const val DEFAULT = 1L
        const val RELATIVE = 2L
        const val LOOP = 3L
        const val OLD = 4L
        const val UNIT = 11L
        const val BOX = 12L
    }
}
