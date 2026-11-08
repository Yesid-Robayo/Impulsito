package io.github.yesidrobayo.shopledger.domain.rule.catalog

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductForm
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductFormIssue
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.rule.TextNormalizer
import org.junit.Assert.assertEquals
import org.junit.Test

class ProductFormRulesTest {
    private fun check(
        form: ProductForm,
        initialStock: Long = 0L,
        allowsFraction: Boolean = false,
    ): ProductFormCheck = ProductFormRules.check(form, QuantityMilli(initialStock), allowsFraction)

    private fun issues(
        form: ProductForm,
        initialStock: Long = 0L,
        allowsFraction: Boolean = false,
    ): Set<ProductFormIssue> =
        (check(form, initialStock, allowsFraction) as ProductFormCheck.Invalid).issues

    @Test
    fun `a full valid form keeps the trimmed name, its normalized form and the trimmed barcode`() {
        val form = ProductForm(
            name = "  Jabón   Líquido ",
            salePrice = Money(3_500L),
            cost = Money(1_200L),
            minStock = QuantityMilli(5_000L),
            barcode = " 7701234 ",
        )

        assertEquals(
            ProductFormCheck.Valid("Jabón   Líquido", "jabon liquido", "7701234"),
            check(form, initialStock = 24_000L),
        )
    }

    @Test
    fun `only a name is enough, with zero prices and stock`() {
        assertEquals(
            ProductFormCheck.Valid("Arroz", "arroz", null),
            check(ProductForm(name = "Arroz")),
        )
    }

    @Test
    fun `a blank barcode is no barcode`() {
        assertEquals(
            ProductFormCheck.Valid("Arroz", "arroz", null),
            check(ProductForm(name = "Arroz", barcode = "   ")),
        )
    }

    @Test
    fun `a name of spaces or of combining marks alone is empty`() {
        assertEquals(setOf(ProductFormIssue.NAME_EMPTY), issues(ProductForm(name = "   ")))
        assertEquals(setOf(ProductFormIssue.NAME_EMPTY), issues(ProductForm(name = "́̃")))
    }

    @Test
    fun `the name allows 80 characters and an emoji counts as one`() {
        val eighty = "a".repeat(79) + "😀"
        assertEquals(
            ProductFormCheck.Valid(eighty, TextNormalizer.normalize(eighty), null),
            check(ProductForm(name = eighty)),
        )
        assertEquals(
            setOf(ProductFormIssue.NAME_TOO_LONG),
            issues(ProductForm(name = "a".repeat(81))),
        )
    }

    @Test
    fun `prices and costs go from zero to the unit money limit`() {
        val top = Money(1_000_000_000_000L)
        assertEquals(
            ProductFormCheck.Valid("Arroz", "arroz", null),
            check(ProductForm(name = "Arroz", salePrice = top, cost = top)),
        )
        assertEquals(
            setOf(ProductFormIssue.SALE_PRICE_OUT_OF_RANGE, ProductFormIssue.COST_OUT_OF_RANGE),
            issues(
                ProductForm(name = "Arroz", salePrice = Money(top.minor + 1L), cost = Money(-1L)),
            ),
        )
        assertEquals(
            setOf(ProductFormIssue.SALE_PRICE_OUT_OF_RANGE, ProductFormIssue.COST_OUT_OF_RANGE),
            issues(
                ProductForm(name = "Arroz", salePrice = Money(-1L), cost = Money(top.minor + 1L)),
            ),
        )
    }

    @Test
    fun `a product made of components carries no cost of its own`() {
        val components = ProductForm(name = "Combo", stockMode = ProductStockMode.COMPONENTS)
        assertEquals(ProductFormCheck.Valid("Combo", "combo", null), check(components))
        assertEquals(
            setOf(ProductFormIssue.COST_NOT_APPLICABLE),
            issues(components.copy(cost = Money(10L))),
        )
    }

    @Test
    fun `a service takes a fixed cost but no stock`() {
        val service =
            ProductForm(name = "Corte", stockMode = ProductStockMode.UNTRACKED, cost = Money(800L))
        assertEquals(ProductFormCheck.Valid("Corte", "corte", null), check(service))
        assertEquals(
            setOf(ProductFormIssue.STOCK_NOT_APPLICABLE),
            issues(service, initialStock = 1_000L),
        )
        assertEquals(
            setOf(ProductFormIssue.STOCK_NOT_APPLICABLE),
            issues(service.copy(minStock = QuantityMilli(1_000L))),
        )
    }

    @Test
    fun `initial stock goes up to the line quantity limit`() {
        val form = ProductForm(name = "Arroz")
        assertEquals(
            ProductFormCheck.Valid("Arroz", "arroz", null),
            check(form, initialStock = 1_000_000_000L),
        )
        assertEquals(
            setOf(ProductFormIssue.INITIAL_STOCK_OUT_OF_RANGE),
            issues(form, initialStock = 1_000_001_000L),
        )
        assertEquals(
            setOf(ProductFormIssue.INITIAL_STOCK_OUT_OF_RANGE),
            issues(form, initialStock = -1_000L),
        )
    }

    @Test
    fun `a counted unit takes no fractions and a weighed one does`() {
        val form = ProductForm(name = "Queso", minStock = QuantityMilli(500L))
        assertEquals(
            setOf(
                ProductFormIssue.INITIAL_STOCK_FRACTION_NOT_ALLOWED,
                ProductFormIssue.MIN_STOCK_FRACTION_NOT_ALLOWED,
            ),
            issues(form, initialStock = 2_500L),
        )
        assertEquals(
            ProductFormCheck.Valid("Queso", "queso", null),
            check(form, 2_500L, allowsFraction = true),
        )
    }

    @Test
    fun `the minimum goes up to the on hand limit`() {
        val top = ProductForm(name = "Arroz", minStock = QuantityMilli(8_000_000_000L))
        assertEquals(ProductFormCheck.Valid("Arroz", "arroz", null), check(top))
        assertEquals(
            setOf(ProductFormIssue.MIN_STOCK_OUT_OF_RANGE),
            issues(top.copy(minStock = QuantityMilli(8_000_000_001L))),
        )
        assertEquals(
            setOf(ProductFormIssue.MIN_STOCK_OUT_OF_RANGE),
            issues(top.copy(minStock = QuantityMilli(-1L))),
        )
    }

    @Test
    fun `the barcode allows 64 characters`() {
        val form = ProductForm(name = "Arroz", barcode = "1".repeat(64))
        assertEquals(ProductFormCheck.Valid("Arroz", "arroz", "1".repeat(64)), check(form))
        assertEquals(
            setOf(ProductFormIssue.BARCODE_TOO_LONG),
            issues(form.copy(barcode = "1".repeat(65))),
        )
    }

    @Test
    fun `every problem is reported at once`() {
        val form = ProductForm(name = "", salePrice = Money(-1L), barcode = "1".repeat(65))
        assertEquals(
            setOf(
                ProductFormIssue.NAME_EMPTY,
                ProductFormIssue.SALE_PRICE_OUT_OF_RANGE,
                ProductFormIssue.BARCODE_TOO_LONG,
            ),
            issues(form),
        )
    }

    @Test
    fun `an opening balance must move something within its limits`() {
        assertEquals(
            emptySet<ProductFormIssue>(),
            ProductFormRules.openingIssues(QuantityMilli(24_000L), Money(1_200L), false),
        )
        assertEquals(
            setOf(ProductFormIssue.INITIAL_STOCK_OUT_OF_RANGE),
            ProductFormRules.openingIssues(QuantityMilli.ZERO, Money(1_200L), false),
        )
        assertEquals(
            setOf(
                ProductFormIssue.INITIAL_STOCK_FRACTION_NOT_ALLOWED,
                ProductFormIssue.COST_OUT_OF_RANGE,
            ),
            ProductFormRules.openingIssues(QuantityMilli(1_500L), Money(-1L), false),
        )
        assertEquals(
            emptySet<ProductFormIssue>(),
            ProductFormRules.openingIssues(QuantityMilli(1_500L), Money.ZERO, true),
        )
    }
}
