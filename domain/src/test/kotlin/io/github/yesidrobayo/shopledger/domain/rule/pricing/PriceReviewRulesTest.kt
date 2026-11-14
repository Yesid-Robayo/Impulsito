package io.github.yesidrobayo.shopledger.domain.rule.pricing

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.Margin
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.PricingContext
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductUsage
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceReview
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceReviewReason
import io.github.yesidrobayo.shopledger.domain.model.pricing.StaleCostCheck
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxMode
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CatalogFixture
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lo que junta `Revisar`: margen bajo el objetivo heredado, costo viejo según los días fijados y
 * costo nuevo sin revisar, con los ejemplos de las reglas escritos a mano. Productos y montos
 * inventados, en una moneda de prueba.
 */
class PriceReviewRulesTest {
    @Test
    fun aMarginBelowTheTargetIsLowAndOneEqualIsNot() {
        assertTrue(PriceReviewRules.isLowMargin(Margin.Defined(BasisPoints(2_999)), TARGET))
        assertFalse(PriceReviewRules.isLowMargin(Margin.Defined(BasisPoints(3_000)), TARGET))
        assertFalse(PriceReviewRules.isLowMargin(Margin.Defined(BasisPoints(4_000)), TARGET))
        // Un margen negativo siempre queda por debajo, aunque el objetivo sea cero.
        assertTrue(
            PriceReviewRules.isLowMargin(Margin.Defined(BasisPoints(-2_000)), BasisPoints.ZERO),
        )
    }

    @Test
    fun withoutAMeasuredMarginThereIsNoLowMargin() {
        assertFalse(PriceReviewRules.isLowMargin(Margin.NoData, TARGET))
        assertFalse(PriceReviewRules.isLowMargin(Margin.OutOfRange, TARGET))
    }

    @Test
    fun aCostFromAugustFirstIsOldOnNovemberFirstWithNinetyDays() {
        // El ejemplo de la regla: 92 días, más que 90.
        assertTrue(PriceReviewRules.isStale(date(2026, 8, 1), date(2026, 11, 1), 90))
    }

    @Test
    fun exactlyTheDaysIsNotOldYetAndOneMoreIs() {
        assertFalse(PriceReviewRules.isStale(date(2026, 8, 1), date(2026, 10, 30), 90))
        assertTrue(PriceReviewRules.isStale(date(2026, 8, 1), date(2026, 10, 31), 90))
    }

    @Test
    fun zeroDaysNeverEvaluatesTheCost() {
        assertFalse(PriceReviewRules.isStale(date(2000, 1, 1), date(2026, 11, 1), 0))
    }

    @Test
    fun theCostDateIsTheReplacementOneOrTheCreationOne() {
        val withReplacement =
            product(replacementCost = Money(700), replacementCostAt = AUGUST_FIRST)
        assertEquals(AUGUST_FIRST, PriceReviewRules.costInstantOf(withReplacement))

        assertEquals(CREATED, PriceReviewRules.costInstantOf(product(averageCost = Money(700))))
        assertEquals(
            CREATED,
            PriceReviewRules.costInstantOf(
                product(stockValue = Money(1_400), onHand = QuantityMilli(2_000)),
            ),
        )
        // Un costo de reposición sin fecha no debería existir; si llega, cuenta desde la creación.
        assertEquals(
            CREATED,
            PriceReviewRules.costInstantOf(product(replacementCost = Money(700))),
        )
    }

    @Test
    fun withoutOwnStockOrWithoutAnyCostTheCostIsNeverOld() {
        assertNull(PriceReviewRules.costInstantOf(product()))
        assertNull(
            PriceReviewRules.costInstantOf(
                product(stockMode = ProductStockMode.UNTRACKED, averageCost = Money(700)),
            ),
        )
        assertNull(
            PriceReviewRules.costInstantOf(
                product(stockMode = ProductStockMode.COMPONENTS, averageCost = Money(700)),
            ),
        )
    }

    @Test
    fun theTargetIsInheritedFromTheCategory() {
        // 10000 con costo 6500 deja 35 %: bajo el 40 % de la categoría, sobre el 30 % del negocio.
        val inCategory =
            product(salePrice = Money(10_000), averageCost = Money(6_500), category = 7)
        val review = requireNotNull(PriceReviewRules.reviewOf(inCategory, context(), null))

        assertEquals(listOf(PriceReviewReason.LOW_MARGIN), review.reasons)
        assertEquals(BasisPoints(4_000), review.target)
        assertEquals(Margin.Defined(BasisPoints(3_500)), review.margin)
        assertNull(review.costSince)

        val withoutCategory = inCategory.copy(details = inCategory.details.copy(categoryId = null))
        assertNull(PriceReviewRules.reviewOf(withoutCategory, context(), null))

        // Una categoría sin margen propio deja el del negocio.
        val otherCategory = inCategory.copy(details = inCategory.details.copy(categoryId = 8))
        assertNull(PriceReviewRules.reviewOf(otherCategory, context(), null))
    }

    @Test
    fun theProductTargetPrecedesTheCategoryOne() {
        val own = product(
            salePrice = Money(10_000),
            averageCost = Money(6_500),
            category = 7,
            target = BasisPoints(2_000),
        )

        assertNull(PriceReviewRules.reviewOf(own, context(), null))
    }

    @Test
    fun theMarginUsesTheExactCostOfTheStockNotTheRoundedOne() {
        // Una caja de 24 que costó 1000: 41,67 por unidad. Con precio 60 el margen exacto es
        // 30,56 %, sobre el 30,50 % pedido; con el costo redondeado a 42 sería 30 % y avisaría.
        val box = product(
            salePrice = Money(60),
            averageCost = Money(42),
            stockValue = Money(1_000),
            onHand = QuantityMilli(24_000),
            target = BasisPoints(3_050),
        )
        assertNull(PriceReviewRules.reviewOf(box, context(), null))

        // Mil unidades que costaron 1001: 1,001 por unidad. Con precio 2 el margen exacto es
        // 49,95 %, bajo el 50 %; con el costo redondeado a 1 sería 50 % y no avisaría.
        val thousand = product(
            salePrice = Money(2),
            averageCost = Money(1),
            stockValue = Money(1_001),
            onHand = QuantityMilli(1_000_000),
            target = BasisPoints(5_000),
        )
        val review = requireNotNull(PriceReviewRules.reviewOf(thousand, context(), null))
        assertEquals(Margin.Defined(BasisPoints(4_995)), review.margin)
    }

    @Test
    fun theMarginIsMeasuredWithoutTheIncludedTax() {
        // 11900 con 19 % incluido es 10000 sin impuesto: con costo 7500 el margen es 25 %, bajo el
        // 30 %, aunque contra el precio con impuesto parecería 37 %.
        val taxed = product(salePrice = Money(11_900), averageCost = Money(7_500))
        val included = context().copy(
            taxMode = TaxMode.INCLUDED,
            defaultTaxRateId = 3,
            taxRates = mapOf(3L to BasisPoints(1_900)),
        )

        val review = requireNotNull(PriceReviewRules.reviewOf(taxed, included, null))

        assertEquals(Margin.Defined(BasisPoints(2_500)), review.margin)
        assertEquals(listOf(PriceReviewReason.LOW_MARGIN), review.reasons)
    }

    @Test
    fun withoutPriceOrCostThereIsNoMarginReason() {
        assertNull(PriceReviewRules.reviewOf(product(averageCost = Money(700)), context(), null))
        assertNull(PriceReviewRules.reviewOf(product(salePrice = Money(1_000)), context(), null))
    }

    @Test
    fun aPureInputNeverAsksForAPriceAndOneAlsoSoldDoes() {
        fun withEveryReason(usage: ProductUsage) = product(
            salePrice = Money(1_000),
            averageCost = Money(900),
            replacementCost = Money(900),
            replacementCostAt = AUGUST_FIRST,
            reviewSince = AUGUST_FIRST,
            usage = usage,
        )

        val input = withEveryReason(ProductUsage.INPUT)
        val both = withEveryReason(ProductUsage.BOTH)

        assertNull(PriceReviewRules.reviewOf(input, context(), check(90)))
        val review = requireNotNull(PriceReviewRules.reviewOf(both, context(), check(90)))
        assertEquals(3, review.reasons.size)
    }

    @Test
    fun anOldCostAppearsWithItsBusinessDate() {
        val old = product(
            salePrice = Money(1_000),
            averageCost = Money(500),
            replacementCost = Money(500),
            replacementCostAt = AUGUST_FIRST,
        )

        val review = requireNotNull(PriceReviewRules.reviewOf(old, context(), check(90)))

        assertEquals(listOf(PriceReviewReason.OLD_COST), review.reasons)
        assertEquals(date(2026, 8, 1), review.costSince)
    }

    @Test
    fun zeroDaysOrAnUnknownZoneTurnOffTheOldCost() {
        val old = product(
            salePrice = Money(1_000),
            averageCost = Money(500),
            replacementCost = Money(500),
            replacementCostAt = AUGUST_FIRST,
        )

        assertNull(PriceReviewRules.reviewOf(old, context(), check(0)))
        assertNull(PriceReviewRules.reviewOf(old, context(), null))
        // Un costo reciente tampoco es viejo.
        assertNull(PriceReviewRules.reviewOf(old, context(), check(120)))
        // Un servicio no se compra: su costo nunca es viejo.
        val service = old.copy(
            details = old.details.copy(
                stockMode = ProductStockMode.UNTRACKED,
                fixedUnitCost = Money(500),
            ),
        )
        assertNull(PriceReviewRules.reviewOf(service, context(), check(90)))
    }

    @Test
    fun aProductCanHaveTheThreeReasonsInOrder() {
        val all = product(
            salePrice = Money(1_000),
            averageCost = Money(900),
            replacementCost = Money(950),
            replacementCostAt = AUGUST_FIRST,
            reviewSince = AUGUST_FIRST,
        )

        val review = requireNotNull(PriceReviewRules.reviewOf(all, context(), check(90)))

        assertEquals(
            listOf(
                PriceReviewReason.NEW_COST,
                PriceReviewReason.LOW_MARGIN,
                PriceReviewReason.OLD_COST,
            ),
            review.reasons,
        )
    }

    @Test
    fun theListPutsNewCostsFirstThenTheLowestMarginAndLeavesArchivedOut() {
        val products = listOf(
            // Costo nuevo, el más reciente.
            product(id = 1, salePrice = Money(1_000), reviewSince = OCTOBER_FIRST),
            // Costo nuevo, el más antiguo.
            product(id = 2, salePrice = Money(1_000), reviewSince = AUGUST_FIRST),
            // Margen de 10 %.
            product(id = 3, salePrice = Money(1_000), averageCost = Money(900)),
            // Margen de −20 %: va antes.
            product(id = 4, salePrice = Money(1_000), averageCost = Money(1_200)),
            // Costo viejo sin precio, sin margen medido: al final.
            product(id = 5, averageCost = Money(500), replacementCostAt = AUGUST_FIRST),
            // Costo viejo sin precio con el mismo lugar: por id.
            product(id = 6, averageCost = Money(500), replacementCostAt = AUGUST_FIRST),
            // Sin motivo: buen margen y costo de hace un mes.
            product(
                id = 7,
                salePrice = Money(1_000),
                averageCost = Money(100),
                replacementCostAt = OCTOBER_FIRST,
            ),
            // Archivado con margen bajo: no aparece.
            product(id = 8, salePrice = Money(1_000), averageCost = Money(900), archived = true),
            // Margen de 10 % también: va después del 3 por id.
            product(id = 9, salePrice = Money(1_000), averageCost = Money(900)),
        )

        val reviews: List<PriceReview> = PriceReviewRules.reviewsOf(products, context(), check(90))

        assertEquals(listOf(2L, 1L, 4L, 3L, 9L, 5L, 6L), reviews.map { it.product.id })
    }

    private fun check(days: Int) = StaleCostCheck(date(2026, 11, 1), UTC, days)

    private fun context() = PricingContext(
        defaultTargetMargin = TARGET,
        categoryTargets = mapOf(7L to BasisPoints(4_000)),
        taxMode = TaxMode.NONE,
        defaultTaxRateId = null,
        taxRates = emptyMap(),
        roundingStep = Money.ZERO,
    )

    private fun product(
        id: Long = 1,
        salePrice: Money = Money.ZERO,
        averageCost: Money = Money.ZERO,
        stockValue: Money = Money.ZERO,
        onHand: QuantityMilli = QuantityMilli.ZERO,
        replacementCost: Money = Money.ZERO,
        replacementCostAt: Instant? = null,
        reviewSince: Instant? = null,
        stockMode: ProductStockMode = ProductStockMode.TRACKED,
        category: Long? = null,
        target: BasisPoints? = null,
        archived: Boolean = false,
        usage: ProductUsage = ProductUsage.SALE,
    ) = Product(
        id = id,
        details = CatalogFixture.details("Producto $id", stockMode)
            .copy(categoryId = category, targetMargin = target, usage = usage),
        salePrice = salePrice,
        initialCost = Money.ZERO,
        averageCost = averageCost,
        stockValue = stockValue,
        onHand = onHand,
        replacementCost = replacementCost,
        replacementCostAt = replacementCostAt,
        priceReviewSince = reviewSince,
        archivedAt = if (archived) CREATED else null,
        createdAt = CREATED,
        updatedAt = CREATED,
    )

    private companion object {
        val TARGET = BasisPoints(3_000)
        val UTC = requireNotNull(BusinessTimeZone.of("UTC"))
        val CREATED: Instant = Instant.parse("2026-05-02T15:00:00Z")
        val AUGUST_FIRST: Instant = Instant.parse("2026-08-01T15:00:00Z")
        val OCTOBER_FIRST: Instant = Instant.parse("2026-10-01T15:00:00Z")

        fun date(year: Int, month: Int, day: Int) = BusinessDate(LocalDate(year, month, day))
    }
}
