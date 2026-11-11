package io.github.yesidrobayo.shopledger.domain.rule.catalog

import io.github.yesidrobayo.shopledger.domain.model.Checked
import io.github.yesidrobayo.shopledger.domain.model.ExactUnitCost
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.ConversionDetails
import io.github.yesidrobayo.shopledger.domain.model.catalog.ConversionDraft
import io.github.yesidrobayo.shopledger.domain.model.catalog.ConversionInputError
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductDetails
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.catalog.UnitDetails
import io.github.yesidrobayo.shopledger.domain.model.catalog.UnitDraft
import io.github.yesidrobayo.shopledger.domain.model.catalog.UnitInputError
import io.github.yesidrobayo.shopledger.domain.model.catalog.UnitKind
import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Las reglas de la hoja de una unidad y de una conversión, y el costo por unidad base que muestra. */
class UnitRulesTest {
    @Test
    fun `a unit of the person is trimmed and normalized, and a blank symbol is no symbol`() {
        val check = UnitFormRules.check(UnitDraft("  Bulto  ", "   ", UnitKind.MASS, true), false)

        assertEquals(
            UnitFormCheck.Valid(UnitDetails("Bulto", "bulto", null, UnitKind.MASS, true)),
            check,
        )
    }

    @Test
    fun `a unit of the person needs a name, an initial one keeps its translated name`() {
        val draft = UnitDraft("   ", "cj", UnitKind.COUNT, false)

        assertEquals(
            UnitFormCheck.Invalid(setOf(UnitInputError.NAME_EMPTY)),
            UnitFormRules.check(draft, isInitial = false),
        )
        assertEquals(
            UnitFormCheck.Valid(UnitDetails(null, null, "cj", UnitKind.COUNT, false)),
            UnitFormRules.check(draft, isInitial = true),
        )
    }

    @Test
    fun `the name reaches 40 characters counting an emoji as one, and the symbol 10`() {
        val forty = "🧺" + "a".repeat(39)
        val ten = "🧺" + "b".repeat(9)

        val valid = UnitFormRules.check(UnitDraft(forty, ten, UnitKind.OTHER, false), false)
        val invalid =
            UnitFormRules.check(UnitDraft(forty + "a", ten + "b", UnitKind.OTHER, false), true)

        assertEquals(forty, (valid as UnitFormCheck.Valid).details.name)
        assertEquals(
            UnitFormCheck.Invalid(
                setOf(UnitInputError.NAME_TOO_LONG, UnitInputError.SYMBOL_TOO_LONG),
            ),
            invalid,
        )
    }

    @Test
    fun `a box of 24 for a product counted by unit is a valid conversion`() {
        val check = ConversionRules.check(draft(factor = 24_000L, barcode = " 7701 "), BASE)

        assertEquals(
            ConversionCheck.Valid(
                ConversionDetails(BOX, 24_000L, null, "7701", forSale = true, forPurchase = true),
            ),
            check,
        )
    }

    @Test
    fun `the factor goes from one thousandth to the line maximum`() {
        assertEquals(1L, valid(ConversionRules.check(draft(factor = 1L), BASE)).factorMilli)
        assertEquals(
            1_000_000_000L,
            valid(ConversionRules.check(draft(factor = 1_000_000_000L), BASE)).factorMilli,
        )
        assertEquals(
            ConversionCheck.Invalid(setOf(ConversionInputError.FACTOR_NOT_POSITIVE)),
            ConversionRules.check(draft(factor = 0L), BASE),
        )
        assertEquals(
            ConversionCheck.Invalid(setOf(ConversionInputError.FACTOR_TOO_HIGH)),
            ConversionRules.check(draft(factor = 1_000_000_001L), BASE),
        )
    }

    @Test
    fun `every problem of a conversion comes at once`() {
        val check =
            ConversionRules.check(
                draft(unit = BASE, factor = -5L, price = Money(-1L), barcode = "9".repeat(65)),
                BASE,
            )

        assertEquals(
            ConversionCheck.Invalid(
                setOf(
                    ConversionInputError.FACTOR_NOT_POSITIVE,
                    ConversionInputError.SAME_AS_BASE,
                    ConversionInputError.PRICE_TOO_HIGH,
                    ConversionInputError.BARCODE_TOO_LONG,
                ),
            ),
            check,
        )
    }

    @Test
    fun `an own price goes up to the unit maximum, and a blank code is no code`() {
        val atLimit = draft(price = Money(1_000_000_000_000L), barcode = "  ")

        val details = valid(ConversionRules.check(atLimit, BASE))

        assertEquals(Money(1_000_000_000_000L), details.salePrice)
        assertNull(details.barcode)
        assertEquals(
            ConversionCheck.Invalid(setOf(ConversionInputError.PRICE_TOO_HIGH)),
            ConversionRules.check(draft(price = Money(1_000_000_000_001L)), BASE),
        )
    }

    @Test
    fun `a box of 24 for 10000 costs 417 per unit, from the stock value`() {
        // 10000 / 24 = 416,67, que redondea a 417.
        val cost = requireNotNull(UnitCostDisplay.costOf(product(24_000L, 10_000L, 417L)))

        assertEquals(Checked.Value(ScaledUnitCost(417L, 0)), UnitCostDisplay.scaled(cost, 2))
    }

    @Test
    fun `a box of 1000 for 400 shows 0,4 per unit and not zero`() {
        // 400 / 1000 = 0,4: con un decimal más son 4 décimas de la unidad menor.
        val cost = requireNotNull(UnitCostDisplay.costOf(product(1_000_000L, 400L, 0L)))

        assertEquals(Checked.Value(ScaledUnitCost(4L, 1)), UnitCostDisplay.scaled(cost, 4))
        assertEquals(Checked.Value(ScaledUnitCost(0L, 0)), UnitCostDisplay.scaled(cost, 0))
    }

    @Test
    fun `a cost too small for the decimals of the format stays at zero with all of them`() {
        // 1 / 1.000.000 de unidades: con 4 decimales más sigue siendo 0,01 de la menor.
        val cost = ExactUnitCost(Money(1L), QuantityMilli(1_000_000_000L))

        assertEquals(Checked.Value(ScaledUnitCost(0L, 4)), UnitCostDisplay.scaled(cost, 4))
    }

    @Test
    fun `a cost that does not fit in Long is rejected`() {
        val cost = ExactUnitCost(Money(9_000_000_000_000_000_000L), QuantityMilli(1L))

        assertEquals(Checked.Overflow, UnitCostDisplay.scaled(cost, 1))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `negative extra decimals are a programming error`() {
        UnitCostDisplay.scaled(ExactUnitCost.perUnit(Money(1L)), -1)
    }

    @Test
    fun `the cost to show follows the stock mode`() {
        // Sin stock en el libro, el costo es el promedio.
        assertEquals(
            ExactUnitCost.perUnit(Money(3_800L)),
            UnitCostDisplay.costOf(product(0L, 0L, 3_800L)),
        )
        // Un servicio usa su costo fijo por unidad vendida.
        assertEquals(
            ExactUnitCost.perUnit(Money(2_500L)),
            UnitCostDisplay.costOf(product(0L, 0L, 0L, ProductStockMode.UNTRACKED, Money(2_500L))),
        )
        // Sin costo, o por componentes, no hay costo que mostrar.
        assertNull(UnitCostDisplay.costOf(product(0L, 0L, 0L)))
        assertNull(
            UnitCostDisplay.costOf(product(5_000L, 900L, 180L, ProductStockMode.COMPONENTS)),
        )
    }

    private fun valid(check: ConversionCheck): ConversionDetails =
        (check as ConversionCheck.Valid).details

    private fun draft(
        unit: Long = BOX,
        factor: Long = 24_000L,
        price: Money? = null,
        barcode: String? = null,
    ) = ConversionDraft(PRODUCT, unit, factor, price, barcode, forSale = true, forPurchase = true)

    private fun product(
        onHand: Long,
        stockValue: Long,
        averageCost: Long,
        mode: ProductStockMode = ProductStockMode.TRACKED,
        fixedCost: Money = Money.ZERO,
    ) = Product(
        id = PRODUCT,
        details =
            ProductDetails(
                name = "Arroz 1 kg",
                normalizedName = "arroz 1 kg",
                categoryId = null,
                defaultSupplierId = null,
                baseUnitId = BASE,
                stockMode = mode,
                tracksLots = false,
                fixedUnitCost = fixedCost,
                minStock = QuantityMilli.ZERO,
                barcode = null,
                parentProductId = null,
                variantAxis1 = null,
                variantAxis2 = null,
                variantValue1 = null,
                variantValue2 = null,
                photoMediaId = null,
                taxRateId = null,
                targetMargin = null,
                referenceCost = null,
                referencePrice = null,
                expiryNoticeDays = null,
            ),
        salePrice = Money.ZERO,
        initialCost = Money.ZERO,
        averageCost = Money(averageCost),
        stockValue = Money(stockValue),
        onHand = QuantityMilli(onHand),
        replacementCost = Money.ZERO,
        replacementCostAt = null,
        priceReviewSince = null,
        archivedAt = null,
        createdAt = Instant.fromEpochMilliseconds(0L),
        updatedAt = Instant.fromEpochMilliseconds(0L),
    )

    private companion object {
        const val PRODUCT = 5L
        const val BASE = 1L
        const val BOX = 10L
    }
}
