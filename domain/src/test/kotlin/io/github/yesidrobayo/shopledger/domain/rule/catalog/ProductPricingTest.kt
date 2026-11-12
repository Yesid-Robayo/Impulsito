package io.github.yesidrobayo.shopledger.domain.rule.catalog

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.ExactUnitCost
import io.github.yesidrobayo.shopledger.domain.model.Margin
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.PricingContext
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxMode
import io.github.yesidrobayo.shopledger.domain.rule.PriceSuggestion
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CatalogFixture
import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El precio sugerido de un producto y el margen de un precio: parten del margen objetivo del
 * producto, de su categoría o del negocio, del costo sin redondear o del costo de reposición, y
 * miden sin el impuesto incluido.
 */
class ProductPricingTest {
    private val at = Instant.fromEpochMilliseconds(1_700_000_000_000L)

    private val context = PricingContext(
        defaultTargetMargin = BasisPoints(3_000L),
        categoryTargets = mapOf(CATEGORY_WITH_TARGET to BasisPoints(4_000L)),
        taxMode = TaxMode.NONE,
        defaultTaxRateId = null,
        taxRates = emptyMap(),
        roundingStep = Money.ZERO,
    )

    @Test
    fun `the suggestion starts from the target and rounds up to the step`() {
        val pricing = ProductPricing.of(product(average = 1_355L), context)

        assertEquals(BasisPoints(3_000L), pricing.targetMargin)
        assertEquals(suggested(1_936L), pricing.suggest())

        val stepped = ProductPricing.of(
            product(average = 1_355L),
            context.copy(roundingStep = Money(100L)),
        )
        assertEquals(suggested(2_000L), stepped.suggest())
        // El paso deja 32,25 %, por encima del objetivo.
        assertEquals(defined(3_225L), stepped.marginOf(Money(2_000L)))
    }

    @Test
    fun `a margin saved in the business is where new suggestions start`() {
        val pricing =
            ProductPricing.of(
                product(average = 1_000L),
                context.copy(defaultTargetMargin = BasisPoints(4_000L)),
            )

        assertEquals(BasisPoints(4_000L), pricing.targetMargin)
        assertEquals(suggested(1_667L), pricing.suggest())
    }

    @Test
    fun `the person can try another target without saving it`() {
        val pricing = ProductPricing.of(product(average = 1_300L), context)

        assertEquals(suggested(2_000L), pricing.suggest(BasisPoints(3_500L)))
        assertEquals(PriceSuggestion.TargetMarginOutOfRange, pricing.suggest(BasisPoints(9_901L)))
        assertEquals(PriceSuggestion.TargetMarginOutOfRange, pricing.suggest(BasisPoints(-1L)))
    }

    @Test
    fun `the replacement cost comes before the average cost`() {
        val pricing = ProductPricing.of(product(average = 1_000L, replacement = 1_500L), context)

        assertEquals(suggested(2_143L), pricing.suggest())
        // El margen de un precio se mide contra el costo del libro, no contra el de reposición.
        assertEquals(defined(5_334L), pricing.marginOf(Money(2_143L)))
    }

    @Test
    fun `without any cost there is no suggestion and no margin`() {
        val pricing = ProductPricing.of(product(), context)

        assertFalse(pricing.canSuggest)
        assertNull(pricing.cost)
        assertEquals(PriceSuggestion.NoCost, pricing.suggest())
        assertEquals(Margin.NoData, pricing.marginOf(Money(1_000L)))
    }

    @Test
    fun `a box of a thousand units for 400 suggests 1 and does not ask for a cost`() {
        val pricing =
            ProductPricing.of(product(onHand = 1_000_000L, value = 400L), context)

        assertTrue(pricing.canSuggest)
        assertEquals(suggested(1L), pricing.suggest())
        assertEquals(defined(6_000L), pricing.marginOf(Money(1L)))
    }

    @Test
    fun `the box of 24 at 90 percent shows its target and not one point less`() {
        val pricing =
            ProductPricing.of(product(onHand = 24_000L, value = 10_000L), context)

        assertEquals(suggested(4_167L), pricing.suggest(BasisPoints(9_000L)))
        assertEquals(defined(9_000L), pricing.marginOf(Money(4_167L)))
    }

    @Test
    fun `with the tax included the price carries it and the margin is measured without it`() {
        val taxed = context.copy(
            taxMode = TaxMode.INCLUDED,
            defaultTaxRateId = DEFAULT_RATE,
            taxRates = mapOf(DEFAULT_RATE to BasisPoints(2_100L), OWN_RATE to BasisPoints(500L)),
        )
        val pricing = ProductPricing.of(product(average = 1_000L), taxed)

        assertEquals(BasisPoints(2_100L), pricing.taxRate)
        assertEquals(suggested(1_730L), pricing.suggest())
        // 1730 sin el 21 % son 1430: 30,07 %.
        assertEquals(Money(1_430L), pricing.netOf(Money(1_730L)))
        assertEquals(defined(3_007L), pricing.marginOf(Money(1_730L)))

        val own = ProductPricing.of(product(average = 1_000L, taxRateId = OWN_RATE), taxed)
        assertEquals(BasisPoints(500L), own.taxRate)
    }

    @Test
    fun `a tax added at checkout does not change the price or the margin`() {
        val added = context.copy(
            taxMode = TaxMode.ADDED,
            defaultTaxRateId = DEFAULT_RATE,
            taxRates = mapOf(DEFAULT_RATE to BasisPoints(2_100L)),
        )
        val pricing = ProductPricing.of(product(average = 1_000L), added)

        assertEquals(suggested(1_429L), pricing.suggest())
        assertEquals(defined(3_002L), pricing.marginOf(Money(1_429L)))
    }

    @Test
    fun `without tax or with a rate that no longer exists the price carries none`() {
        assertEquals(BasisPoints.ZERO, context.taxRate(DEFAULT_RATE))

        val included = context.copy(taxMode = TaxMode.INCLUDED)
        assertEquals(BasisPoints.ZERO, included.taxRate(null))
        assertEquals(BasisPoints.ZERO, included.taxRate(DEFAULT_RATE))
    }

    @Test
    fun `the target comes from the product, then its category, then the business`() {
        assertEquals(
            BasisPoints(2_500L),
            context.targetMargin(BasisPoints(2_500L), CATEGORY_WITH_TARGET),
        )
        assertEquals(BasisPoints(4_000L), context.targetMargin(null, CATEGORY_WITH_TARGET))
        assertEquals(BasisPoints(3_000L), context.targetMargin(null, CATEGORY_WITHOUT_TARGET))
        assertEquals(BasisPoints(3_000L), context.targetMargin(null, null))

        val inCategory = ProductPricing.of(
            product(average = 1_000L, categoryId = CATEGORY_WITH_TARGET),
            context,
        )
        assertEquals(BasisPoints(4_000L), inCategory.targetMargin)
    }

    @Test
    fun `a service uses its fixed cost per unit sold`() {
        val service = ProductPricing.of(
            product(mode = ProductStockMode.UNTRACKED, fixed = 500L),
            context,
        )
        assertEquals(ExactUnitCost.perUnit(Money(500L)), service.cost)
        assertEquals(suggested(715L), service.suggest())

        val free = ProductPricing.of(product(mode = ProductStockMode.UNTRACKED), context)
        assertNull(free.cost)
        assertFalse(free.canSuggest)
    }

    @Test
    fun `a product made of components has no cost of its own here`() {
        val combo = ProductPricing.of(
            product(mode = ProductStockMode.COMPONENTS, average = 1_000L, replacement = 1_500L),
            context,
        )

        assertNull(combo.cost)
        assertFalse(combo.canSuggest)
        assertEquals(Margin.NoData, combo.marginOf(Money(2_000L)))
    }

    @Test
    fun `a draft suggests from what is being typed`() {
        val draft = ProductPricing.draft(
            context = context,
            cost = ExactUnitCost.perUnit(Money(1_355L)),
            replacementCost = Money.ZERO,
            productTarget = null,
            categoryId = CATEGORY_WITH_TARGET,
            taxRateId = null,
        )

        assertEquals(BasisPoints(4_000L), draft.targetMargin)
        assertEquals(suggested(2_259L), draft.suggest())
    }

    private fun product(
        mode: ProductStockMode = ProductStockMode.TRACKED,
        average: Long = 0L,
        value: Long = 0L,
        onHand: Long = 0L,
        fixed: Long = 0L,
        replacement: Long = 0L,
        categoryId: Long? = null,
        taxRateId: Long? = null,
    ): Product = Product(
        id = 1L,
        details = CatalogFixture.details("Producto", stockMode = mode)
            .copy(categoryId = categoryId, fixedUnitCost = Money(fixed), taxRateId = taxRateId),
        salePrice = Money.ZERO,
        initialCost = Money.ZERO,
        averageCost = Money(average),
        stockValue = Money(value),
        onHand = QuantityMilli(onHand),
        replacementCost = Money(replacement),
        replacementCostAt = null,
        priceReviewSince = null,
        archivedAt = null,
        createdAt = at,
        updatedAt = at,
    )

    private fun suggested(price: Long): PriceSuggestion = PriceSuggestion.Suggested(Money(price))

    private fun defined(bp: Long): Margin = Margin.Defined(BasisPoints(bp))

    private companion object {
        const val CATEGORY_WITH_TARGET = 7L
        const val CATEGORY_WITHOUT_TARGET = 8L
        const val DEFAULT_RATE = 3L
        const val OWN_RATE = 4L
    }
}
