package io.github.yesidrobayo.shopledger.domain.rule.catalog

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.BulkPriceContext
import io.github.yesidrobayo.shopledger.domain.model.catalog.BulkPriceRequest
import io.github.yesidrobayo.shopledger.domain.model.catalog.BulkPriceScope
import io.github.yesidrobayo.shopledger.domain.model.catalog.PricingContext
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxMode
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CatalogFixture
import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** La vista previa de la actualización masiva: el alcance, lo que queda igual y el costo de cada uno. */
class BulkPricePreviewerTest {
    private val at = Instant.fromEpochMilliseconds(1_700_000_000_000L)

    private fun product(
        id: Long,
        price: Long,
        mode: ProductStockMode = ProductStockMode.TRACKED,
        average: Long = 0L,
        value: Long = 0L,
        onHand: Long = 0L,
        fixed: Long = 0L,
        categoryId: Long? = null,
    ): Product {
        val details = CatalogFixture.details("Producto $id", stockMode = mode)
            .copy(categoryId = categoryId, fixedUnitCost = Money(fixed))
        return Product(
            id = id,
            details = details,
            salePrice = Money(price),
            initialCost = Money.ZERO,
            averageCost = Money(average),
            stockValue = Money(value),
            onHand = QuantityMilli(onHand),
            replacementCost = Money.ZERO,
            replacementCostAt = null,
            priceReviewSince = null,
            archivedAt = null,
            createdAt = at,
            updatedAt = at,
        )
    }

    @Test
    fun `a price that stays the same is counted, not listed`() {
        val preview = BulkPricePreviewer.preview(
            listOf(product(1L, price = 1L)),
            BulkPriceRequest(BulkPriceScope.All, BasisPoints(1L), Money.ZERO),
        )

        assertEquals(1, preview.unchanged)
        assertTrue(preview.changes.isEmpty())
    }

    @Test
    fun `the subject reads the own price of the list and the tax inside the price`() {
        val soap = product(1L, price = 1_000L)
        val context = BulkPriceContext(
            pricing = PricingContext(
                defaultTargetMargin = BasisPoints(3_000L),
                categoryTargets = emptyMap(),
                taxMode = TaxMode.INCLUDED,
                defaultTaxRateId = 9L,
                taxRates = mapOf(9L to BasisPoints(2_100L)),
                roundingStep = Money.ZERO,
            ),
            ownPrices = mapOf(1L to mapOf(4L to Money(800L))),
        )

        val own = BulkPricePreviewer.subjectOf(soap, BulkTarget.OwnPrice(4L), context)
        val missing = BulkPricePreviewer.subjectOf(soap, BulkTarget.OwnPrice(5L), context)
        val excluded = context.copy(pricing = context.pricing?.copy(taxMode = TaxMode.ADDED))
        val outside = BulkPricePreviewer.subjectOf(soap, BulkTarget.SalePrice(null), excluded)
        val bare = BulkPricePreviewer.subjectOf(
            soap,
            BulkTarget.SalePrice(null),
            BulkPriceContext(),
        )

        assertEquals(Money(800L), own.price)
        assertEquals(BasisPoints(2_100L), own.includedTaxRate)
        // Sin precio propio en esa lista, la lista fija vende al precio de venta.
        assertEquals(Money(1_000L), missing.price)
        val withoutOwn = product(2L, price = 700L)
        assertEquals(
            Money(700L),
            BulkPricePreviewer.subjectOf(withoutOwn, BulkTarget.OwnPrice(4L), context).price,
        )
        assertEquals(BasisPoints.ZERO, outside.includedTaxRate)
        assertEquals(BulkSubject(Money(1_000L), bare.cost), bare)
    }

    @Test
    fun `a tag takes only the products that carry it`() {
        val tagged = BulkPriceScope.Tag(tagId = 3L, productIds = setOf(1L))

        assertTrue(BulkPricePreviewer.inScope(product(1L, price = 1_000L), tagged))
        assertTrue(!BulkPricePreviewer.inScope(product(2L, price = 1_000L), tagged))
    }

    @Test
    fun `the category scope takes only its products`() {
        val inCategory = product(1L, price = 1_000L, categoryId = 7L)
        val other = product(2L, price = 1_000L, categoryId = 8L)

        assertTrue(BulkPricePreviewer.inScope(inCategory, BulkPriceScope.Category(7L)))
        assertTrue(!BulkPricePreviewer.inScope(other, BulkPriceScope.Category(7L)))
        assertTrue(!BulkPricePreviewer.inScope(other, BulkPriceScope.Supplier(1L)))
        val withSupplier = other.copy(details = other.details.copy(defaultSupplierId = 2L))
        assertTrue(!BulkPricePreviewer.inScope(withSupplier, BulkPriceScope.Supplier(1L)))
        assertTrue(BulkPricePreviewer.inScope(withSupplier, BulkPriceScope.Supplier(2L)))
    }

    @Test
    fun `the cost of each stock mode`() {
        // Con stock, el costo de catálogo exacto: 12000 entre 10 unidades.
        assertEquals(
            Money(1_200L),
            BulkPricePreviewer.catalogCost(product(1L, 1L, value = 12_000L, onHand = 10_000L)),
        )
        // Con costo promedio y sin unidades, el promedio.
        assertEquals(Money(900L), BulkPricePreviewer.catalogCost(product(1L, 1L, average = 900L)))
        // Un valor que no cabe al dividirlo entre muy pocas unidades cae al promedio.
        assertEquals(
            Money(5L),
            BulkPricePreviewer.catalogCost(
                product(1L, 1L, average = 5L, value = Long.MAX_VALUE / 2, onHand = 1L),
            ),
        )
        assertNull(BulkPricePreviewer.catalogCost(product(1L, 1L)))
        assertEquals(
            Money(300L),
            BulkPricePreviewer.catalogCost(
                product(1L, 1L, ProductStockMode.UNTRACKED, fixed = 300L),
            ),
        )
        assertNull(BulkPricePreviewer.catalogCost(product(1L, 1L, ProductStockMode.UNTRACKED)))
        assertNull(BulkPricePreviewer.catalogCost(product(1L, 1L, ProductStockMode.COMPONENTS)))
    }
}
