package io.github.yesidrobayo.shopledger.domain.rule.catalog

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductFormIssue
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.catalog.VariantAxes
import io.github.yesidrobayo.shopledger.domain.model.catalog.VariantDraft
import io.github.yesidrobayo.shopledger.domain.model.catalog.VariantIssue
import io.github.yesidrobayo.shopledger.domain.model.catalog.VariantValues
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CatalogFixture
import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

/** Las reglas de las variantes: ejes, valores, combinaciones únicas y nombre. */
class VariantRulesTest {
    private val shirt = product(1L, "Camiseta")
    private val twoAxes = VariantAxes("Talla", "Color")

    @Test
    fun `two axes give one product per combination named after the parent`() {
        val check = VariantRules.check(
            shirt,
            VariantAxes("  Talla ", " Color"),
            listOf(
                draft(" S ", "Rojo", barcode = " 7701 "),
                draft("M", "Azul", barcode = "  "),
            ),
            existing = emptyList(),
            allowsFraction = false,
        )

        val valid = check as VariantPlanCheck.Valid
        assertEquals(twoAxes, valid.axes)
        assertEquals(
            listOf("Camiseta · S · Rojo", "Camiseta · M · Azul"),
            valid.variants.map { it.name },
        )
        assertEquals("camiseta · s · rojo", valid.variants[0].normalizedName)
        assertEquals(VariantValues("S", "Rojo"), valid.variants[0].values)
        assertEquals("7701", valid.variants[0].barcode)
        assertEquals(null, valid.variants[1].barcode)
    }

    @Test
    fun `one axis names the variant with its single value`() {
        val check = VariantRules.check(
            shirt,
            VariantAxes("Talla", null),
            listOf(draft("L", null)),
            existing = emptyList(),
            allowsFraction = false,
        )

        val valid = check as VariantPlanCheck.Valid
        assertEquals(VariantAxes("Talla", null), valid.axes)
        assertEquals("Camiseta · L", valid.variants.single().name)
    }

    @Test
    fun `an empty or long axis and no variants are axis issues`() {
        val longAxis = "x".repeat(41)

        val check = VariantRules.check(
            shirt,
            VariantAxes("  ", longAxis),
            emptyList(),
            existing = emptyList(),
            allowsFraction = false,
        )

        assertEquals(
            VariantPlanCheck.Invalid(
                setOf(
                    VariantIssue.AXIS_EMPTY,
                    VariantIssue.AXIS_TOO_LONG,
                    VariantIssue.NO_VARIANTS,
                ),
                emptyMap(),
                emptyMap(),
            ),
            check,
        )
    }

    @Test
    fun `a forty character axis and value are accepted`() {
        val forty = "y".repeat(40)

        val check = VariantRules.check(
            product(1L, "A"),
            VariantAxes(forty, null),
            listOf(draft(forty, null)),
            existing = emptyList(),
            allowsFraction = false,
        )

        assertEquals(forty, (check as VariantPlanCheck.Valid).variants.single().values.first)
    }

    @Test
    fun `new variants keep the axes the parent already uses, ignoring case and accents`() {
        val existing = listOf(variant(2L, "Talla", "Color", "S", "Rojo"))

        val same = VariantRules.check(
            shirt,
            VariantAxes("TALLA", "color"),
            listOf(draft("M", "Rojo")),
            existing,
            allowsFraction = false,
        )
        val changed = VariantRules.check(
            shirt,
            VariantAxes("Talla", null),
            listOf(draft("M", null)),
            existing,
            allowsFraction = false,
        )

        assertEquals("Camiseta · M · Rojo", (same as VariantPlanCheck.Valid).variants[0].name)
        assertEquals(
            setOf(VariantIssue.AXES_CHANGED),
            (changed as VariantPlanCheck.Invalid).axisIssues,
        )
    }

    @Test
    fun `a combination needs exactly one value per axis`() {
        val check = VariantRules.check(
            shirt,
            twoAxes,
            listOf(draft("S", null)),
            existing = emptyList(),
            allowsFraction = false,
        )
        val extra = VariantRules.check(
            shirt,
            VariantAxes("Talla", null),
            listOf(draft("S", "Rojo")),
            existing = emptyList(),
            allowsFraction = false,
        )

        val expected = mapOf(0 to setOf(VariantIssue.VALUES_DO_NOT_MATCH_AXES))
        assertEquals(expected, (check as VariantPlanCheck.Invalid).variantIssues)
        assertEquals(expected, (extra as VariantPlanCheck.Invalid).variantIssues)
    }

    @Test
    fun `an empty or long value marks its own row`() {
        val check = VariantRules.check(
            shirt,
            twoAxes,
            listOf(draft("S", "Rojo"), draft(" ", "z".repeat(41)), draft("́", "Azul")),
            existing = emptyList(),
            allowsFraction = false,
        )

        assertEquals(
            mapOf(
                1 to setOf(VariantIssue.VALUE_EMPTY, VariantIssue.VALUE_TOO_LONG),
                2 to setOf(VariantIssue.VALUE_EMPTY),
            ),
            (check as VariantPlanCheck.Invalid).variantIssues,
        )
    }

    @Test
    fun `a repeated combination is rejected, also against archived variants`() {
        val archived = variant(2L, "Talla", "Color", "M", "Azul").copy(
            archivedAt = Instant.fromEpochMilliseconds(1L),
        )

        val check = VariantRules.check(
            shirt,
            twoAxes,
            listOf(draft("S", "Rojo"), draft("s", "ROJO"), draft("M", "Azúl")),
            existing = listOf(archived),
            allowsFraction = false,
        )

        assertEquals(
            mapOf(
                1 to setOf(VariantIssue.DUPLICATE_COMBINATION),
                2 to setOf(VariantIssue.DUPLICATE_COMBINATION),
            ),
            (check as VariantPlanCheck.Invalid).variantIssues,
        )
    }

    @Test
    fun `a name over eighty characters is rejected`() {
        val longParent = product(1L, "p".repeat(75))

        val check = VariantRules.check(
            longParent,
            VariantAxes("Talla", null),
            listOf(draft("XS", null), draft("XXL", null)),
            existing = emptyList(),
            allowsFraction = false,
        )

        assertEquals(
            mapOf(1 to setOf(VariantIssue.NAME_TOO_LONG)),
            (check as VariantPlanCheck.Invalid).variantIssues,
        )
    }

    @Test
    fun `two new variants cannot share a barcode`() {
        val check = VariantRules.check(
            shirt,
            twoAxes,
            listOf(draft("S", "Rojo", barcode = "123"), draft("M", "Rojo", barcode = " 123 ")),
            existing = emptyList(),
            allowsFraction = false,
        )

        assertEquals(
            mapOf(
                0 to setOf(VariantIssue.BARCODE_REPEATED),
                1 to setOf(VariantIssue.BARCODE_REPEATED),
            ),
            (check as VariantPlanCheck.Invalid).variantIssues,
        )
    }

    @Test
    fun `amounts and quantities follow the rules of a product of the same stock mode`() {
        val check = VariantRules.check(
            shirt,
            twoAxes,
            listOf(
                draft("S", "Rojo").copy(salePrice = Money(-1L)),
                draft("M", "Rojo").copy(initialStock = QuantityMilli(1_500L)),
            ),
            existing = emptyList(),
            allowsFraction = false,
        )
        val service = VariantRules.check(
            product(1L, "Corte", ProductStockMode.UNTRACKED),
            VariantAxes("Largo", null),
            listOf(draft("Corto", null).copy(initialStock = QuantityMilli(1_000L))),
            existing = emptyList(),
            allowsFraction = true,
        )

        assertEquals(
            mapOf(
                0 to setOf(ProductFormIssue.SALE_PRICE_OUT_OF_RANGE),
                1 to setOf(ProductFormIssue.INITIAL_STOCK_FRACTION_NOT_ALLOWED),
            ),
            (check as VariantPlanCheck.Invalid).formIssues,
        )
        assertEquals(emptyMap<Int, Set<VariantIssue>>(), check.variantIssues)
        assertEquals(
            mapOf(0 to setOf(ProductFormIssue.STOCK_NOT_APPLICABLE)),
            (service as VariantPlanCheck.Invalid).formIssues,
        )
    }

    @Test
    fun `an existing variant without axis or values compares as empty`() {
        val bare = variant(2L, null, null, null, null)

        val check = VariantRules.check(
            shirt,
            VariantAxes("Talla", null),
            listOf(draft("S", null)),
            existing = listOf(bare),
            allowsFraction = false,
        )

        assertEquals(
            setOf(VariantIssue.AXES_CHANGED),
            (check as VariantPlanCheck.Invalid).axisIssues,
        )
    }

    private fun draft(first: String, second: String?, barcode: String? = null) =
        VariantDraft(values = VariantValues(first, second), barcode = barcode)

    private fun product(
        id: Long,
        name: String,
        stockMode: ProductStockMode = ProductStockMode.TRACKED,
    ) = Product(
        id = id,
        details = CatalogFixture.details(name, stockMode),
        salePrice = Money.ZERO,
        initialCost = Money.ZERO,
        averageCost = Money.ZERO,
        stockValue = Money.ZERO,
        onHand = QuantityMilli.ZERO,
        replacementCost = Money.ZERO,
        replacementCostAt = null,
        priceReviewSince = null,
        archivedAt = null,
        createdAt = CatalogFixture.EARLIER,
        updatedAt = CatalogFixture.EARLIER,
    )

    private fun variant(
        id: Long,
        axis1: String?,
        axis2: String?,
        value1: String?,
        value2: String?,
    ): Product {
        val base = product(id, "Camiseta $id")
        return base.copy(
            details = base.details.copy(
                parentProductId = 1L,
                variantAxis1 = axis1,
                variantAxis2 = axis2,
                variantValue1 = value1,
                variantValue2 = value2,
            ),
        )
    }
}
