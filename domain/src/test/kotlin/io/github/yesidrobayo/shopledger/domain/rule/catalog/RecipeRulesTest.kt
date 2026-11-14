package io.github.yesidrobayo.shopledger.domain.rule.catalog

import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.catalog.RecipeComponent
import io.github.yesidrobayo.shopledger.domain.model.catalog.RecipeIssue
import io.github.yesidrobayo.shopledger.domain.model.catalog.RecipeLine
import io.github.yesidrobayo.shopledger.domain.model.catalog.RecipeLineProblem
import io.github.yesidrobayo.shopledger.domain.rule.catalog.ComboTestProducts.product
import io.github.yesidrobayo.shopledger.domain.rule.catalog.ComboTestProducts.row
import org.junit.Assert.assertEquals
import org.junit.Test

// Los ids de producto son inventados: 1 es el combo que se edita, 10 a 19 componentes con stock,
// 20 a 29 otros combos.
class RecipeRulesTest {
    private val combo = 1L
    private val a = 10L
    private val b = 11L
    private val service = 12L
    private val inner = 20L
    private val outer = 21L
    private val other = 22L

    private val catalog: Map<Long, Product> = listOf(
        product(a),
        product(b),
        product(service, ProductStockMode.UNTRACKED),
        product(inner, ProductStockMode.COMPONENTS),
        product(outer, ProductStockMode.COMPONENTS),
        product(other, ProductStockMode.COMPONENTS),
    ).associateBy { it.id }

    @Test
    fun `the kit of two of A and one of B is valid`() {
        val check = check(listOf(line(a, 2000), line(b, 1000)))

        assertEquals(RecipeCheck.Valid, check)
    }

    @Test
    fun `an empty recipe is valid`() {
        assertEquals(RecipeCheck.Valid, check(emptyList()))
    }

    @Test
    fun `a combo inside a combo is valid when the inner one only holds leaves`() {
        val stored = listOf(row(inner, a, 1000), row(inner, service, 500))

        assertEquals(RecipeCheck.Valid, check(listOf(line(inner, 2000)), stored))
    }

    @Test
    fun `a leaf added to a combo that is already inside another is valid`() {
        val stored = listOf(row(outer, combo, 1000))

        assertEquals(RecipeCheck.Valid, check(listOf(line(a, 1000)), stored))
    }

    @Test
    fun `the combo itself is rejected as its own component`() {
        val check = check(listOf(line(combo, 1000)))

        assertIssues(listOf(lineIssue(0, RecipeLineProblem.SAME_PRODUCT)), check)
    }

    @Test
    fun `a component repeated in a later row is rejected on that row`() {
        val check = check(listOf(line(a, 1000), line(b, 1000), line(a, 3000)))

        assertIssues(listOf(lineIssue(2, RecipeLineProblem.REPEATED)), check)
    }

    @Test
    fun `a missing product is unavailable`() {
        val check = check(listOf(line(99L, 1000)))

        assertIssues(listOf(lineIssue(0, RecipeLineProblem.UNAVAILABLE)), check)
    }

    @Test
    fun `an archived product is unavailable unless it was already in the recipe`() {
        val products = catalog + (b to product(b, archived = true))

        val added = RecipeRules.check(combo, listOf(line(b, 1000)), products, emptyList())
        val kept = RecipeRules.check(
            combo,
            listOf(line(b, 2000)),
            products,
            listOf(row(combo, b, 1000)),
        )

        assertIssues(listOf(lineIssue(0, RecipeLineProblem.UNAVAILABLE)), added)
        assertEquals(RecipeCheck.Valid, kept)
    }

    @Test
    fun `a component that already holds the combo makes a cycle`() {
        val stored = listOf(row(other, combo, 1000))

        val check = check(listOf(line(other, 1000)), stored)

        assertIssues(listOf(lineIssue(0, RecipeLineProblem.CYCLE)), check)
    }

    @Test
    fun `a component that holds the combo through another makes a cycle`() {
        // other contiene a inner, e inner contiene al combo: agregar other al combo cerraría el
        // círculo combo, other, inner, combo.
        val stored = listOf(row(other, inner, 1000), row(inner, combo, 1000))

        val check = check(listOf(line(other, 1000)), stored)

        assertIssues(listOf(lineIssue(0, RecipeLineProblem.CYCLE)), check)
    }

    @Test
    fun `a combo whose component is another combo with components is a third level`() {
        val stored = listOf(row(outer, inner, 1000), row(inner, a, 1000))

        val check = check(listOf(line(outer, 1000)), stored)

        assertIssues(listOf(lineIssue(0, RecipeLineProblem.TOO_DEEP)), check)
    }

    @Test
    fun `a combo inside another cannot hold a combo with components`() {
        val stored = listOf(row(outer, combo, 1000), row(inner, a, 1000))

        val check = check(listOf(line(inner, 1000)), stored)

        assertIssues(listOf(lineIssue(0, RecipeLineProblem.TOO_DEEP)), check)
    }

    @Test
    fun `a combo two levels down cannot hold anything`() {
        // La guarda de la tabla solo mira un nivel a cada lado y dejaría pasar este tercer nivel:
        // outer contiene a other, other contiene al combo, y el combo recibiría una hoja.
        val stored = listOf(row(outer, other, 1000), row(other, combo, 1000))

        val check = check(listOf(line(a, 1000)), stored)

        assertIssues(listOf(lineIssue(0, RecipeLineProblem.TOO_DEEP)), check)
    }

    @Test
    fun `the deepest of several paths above the combo is the one that counts`() {
        // El combo entra en outer, a un nivel, y en other, que a su vez entra en inner: por
        // other ya está dos niveles abajo, aunque por outer esté a uno.
        val stored = listOf(
            row(outer, combo, 1000),
            row(other, combo, 1000),
            row(inner, other, 1000),
        )

        val check = check(listOf(line(a, 1000)), stored)

        assertIssues(listOf(lineIssue(0, RecipeLineProblem.TOO_DEEP)), check)
    }

    @Test
    fun `the stored rows of the combo itself do not count`() {
        // Antes el combo contenía a inner, que contiene a una hoja. La receta nueva lo reemplaza
        // por una hoja, y outer, que contiene al combo, queda con dos niveles.
        val stored = listOf(
            row(outer, combo, 1000),
            row(combo, inner, 1000),
            row(inner, a, 1000),
        )

        assertEquals(RecipeCheck.Valid, check(listOf(line(b, 1000)), stored))
    }

    @Test
    fun `a quantity of zero, negative or above a line is rejected`() {
        val check = check(
            listOf(
                line(a, 0),
                line(b, -1000),
                line(service, 1_000_000_001L),
                line(inner, 1_000_000_000L),
            ),
        )

        assertIssues(
            listOf(
                lineIssue(0, RecipeLineProblem.QUANTITY_OUT_OF_RANGE),
                lineIssue(1, RecipeLineProblem.QUANTITY_OUT_OF_RANGE),
                lineIssue(2, RecipeLineProblem.QUANTITY_OUT_OF_RANGE),
            ),
            check,
        )
    }

    @Test
    fun `a row can have a structure problem and a quantity problem at once`() {
        val check = check(listOf(line(99L, 0)))

        assertIssues(
            listOf(
                lineIssue(0, RecipeLineProblem.UNAVAILABLE),
                lineIssue(0, RecipeLineProblem.QUANTITY_OUT_OF_RANGE),
            ),
            check,
        )
    }

    @Test
    fun `more components than the lines of a document are rejected`() {
        val products = (100L..300L).associateWith { product(it) }
        val atLimit = (100L..299L).map { line(it, 1000) }
        val aboveLimit = (100L..300L).map { line(it, 1000) }

        assertEquals(RecipeCheck.Valid, RecipeRules.check(combo, atLimit, products, emptyList()))
        assertIssues(
            listOf(RecipeIssue.TooManyComponents),
            RecipeRules.check(combo, aboveLimit, products, emptyList()),
        )
    }

    @Test
    fun `a damaged cycle in other recipes does not loop forever`() {
        // Una base dañada con inner y other conteniéndose: la revisión termina igual.
        val stored = listOf(row(inner, other, 1000), row(other, inner, 1000))

        val check = check(listOf(line(inner, 1000)), stored)

        assertIssues(listOf(lineIssue(0, RecipeLineProblem.TOO_DEEP)), check)
    }

    private fun check(
        lines: List<RecipeLine>,
        stored: List<RecipeComponent> = emptyList(),
    ): RecipeCheck = RecipeRules.check(combo, lines, catalog, stored)

    private fun line(productId: Long, milli: Long) = RecipeLine(productId, QuantityMilli(milli))

    private fun lineIssue(index: Int, problem: RecipeLineProblem) = RecipeIssue.Line(index, problem)

    private fun assertIssues(expected: List<RecipeIssue>, actual: RecipeCheck) {
        assertEquals(RecipeCheck.Invalid(expected), actual)
    }

    @Test
    fun `a combo and a product with stock have a recipe, and a service does not`() {
        assertEquals(true, RecipeRules.allowsRecipe(ProductStockMode.COMPONENTS))
        assertEquals(true, RecipeRules.allowsRecipe(ProductStockMode.TRACKED))
        assertEquals(false, RecipeRules.allowsRecipe(ProductStockMode.UNTRACKED))
    }
}
