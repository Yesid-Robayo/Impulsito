package io.github.yesidrobayo.shopledger.domain.rule.catalog

import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.catalog.RecipeComponent
import io.github.yesidrobayo.shopledger.domain.rule.catalog.ComboTestProducts.row
import org.junit.Assert.assertEquals
import org.junit.Test

// Cantidades en milésimas. Cada cantidad esperada está calculada a mano: lo vendido por cada
// factor del camino, dividido por mil por cada nivel, con un solo redondeo al final.
class ComboExpansionTest {
    private val kit = 1L
    private val outer = 2L
    private val inner = 3L
    private val deeper = 4L
    private val a = 10L
    private val b = 11L
    private val service = 12L

    private val modes = mapOf(
        kit to ProductStockMode.COMPONENTS,
        outer to ProductStockMode.COMPONENTS,
        inner to ProductStockMode.COMPONENTS,
        deeper to ProductStockMode.COMPONENTS,
        a to ProductStockMode.TRACKED,
        b to ProductStockMode.TRACKED,
        service to ProductStockMode.UNTRACKED,
    )

    @Test
    fun `two kits of the business rules example consume four of A, two of B and two services`() {
        val recipes = recipes(row(kit, a, 2000), row(kit, b, 1000), row(kit, service, 1000))

        val result = ComboExpansion.expand(kit, QuantityMilli(2000), recipes, modes)

        assertEquals(
            ComboExpansionResult.Leaves(
                listOf(leaf(a, 4000, a), leaf(b, 2000, b), leaf(service, 2000, service)),
            ),
            result,
        )
    }

    @Test
    fun `a combo inside a combo rounds each leaf only once`() {
        // 0,007 del combo de afuera, con 1,5 del de adentro y 1,5 de B en él:
        // 7 × 1500 × 1500 / 1000000 = 15,75, que redondea a 16. Redondear en cada nivel daría
        // 10,5 → 11 y luego 16,5 → 17. El servicio da 7 × 1500 × 2000 / 1000000 = 21 exacto.
        val recipes = recipes(
            row(outer, inner, 1500),
            row(outer, a, 1000),
            row(inner, b, 1500),
            row(inner, service, 2000),
        )

        val result = ComboExpansion.expand(outer, QuantityMilli(7), recipes, modes)

        assertEquals(
            ComboExpansionResult.Leaves(
                listOf(leaf(b, 16, inner), leaf(service, 21, inner), leaf(a, 7, a)),
            ),
            result,
        )
    }

    @Test
    fun `half a thousandth rounds away from zero`() {
        val recipes = recipes(row(kit, a, 500))

        val result = ComboExpansion.expand(kit, QuantityMilli(1), recipes, modes)

        assertEquals(ComboExpansionResult.Leaves(listOf(leaf(a, 1, a))), result)
    }

    @Test
    fun `a leaf that rounds to zero is rejected`() {
        // 0,001 × 0,4 = 0,0004, que redondea a cero en el primer nivel y en el segundo.
        val first = recipes(row(kit, a, 400))
        val second = recipes(row(outer, inner, 1000), row(inner, b, 400))

        assertEquals(
            ComboExpansionResult.LeafRoundsToZero(a),
            ComboExpansion.expand(kit, QuantityMilli(1), first, modes),
        )
        assertEquals(
            ComboExpansionResult.LeafRoundsToZero(b),
            ComboExpansion.expand(outer, QuantityMilli(1), second, modes),
        )
    }

    @Test
    fun `a leaf above the quantity of a line is rejected`() {
        // Un millón de combos con 2 de A son dos millones de A: más que el tope de una línea.
        val first = recipes(row(kit, a, 2000))
        val second = recipes(row(outer, inner, 1000), row(inner, b, 2000))

        assertEquals(
            ComboExpansionResult.QuantityOutOfRange,
            ComboExpansion.expand(kit, QuantityMilli(1_000_000_000L), first, modes),
        )
        assertEquals(
            ComboExpansionResult.QuantityOutOfRange,
            ComboExpansion.expand(outer, QuantityMilli(1_000_000_000L), second, modes),
        )
    }

    @Test
    fun `a leaf that does not fit in a long is rejected`() {
        val first = recipes(row(kit, a, 1_000_000_000L))
        val second = recipes(row(outer, inner, 1_000_000_000L), row(inner, b, 1_000_000_000L))
        val wide = recipes(row(outer, inner, 1_000_000_000L), row(inner, b, 1000))

        assertEquals(
            ComboExpansionResult.QuantityOutOfRange,
            ComboExpansion.expand(kit, QuantityMilli(Long.MAX_VALUE), first, modes),
        )
        assertEquals(
            ComboExpansionResult.QuantityOutOfRange,
            ComboExpansion.expand(outer, QuantityMilli(1_000_000_000L), second, modes),
        )
        assertEquals(
            ComboExpansionResult.QuantityOutOfRange,
            ComboExpansion.expand(outer, QuantityMilli(Long.MAX_VALUE / 2), wide, modes),
        )
    }

    @Test
    fun `a third level of combos is rejected`() {
        val recipes = recipes(row(outer, inner, 1000), row(inner, deeper, 1000))

        assertEquals(
            ComboExpansionResult.TooDeep,
            ComboExpansion.expand(outer, QuantityMilli(1000), recipes, modes),
        )
    }

    @Test
    fun `an inner combo without recipe adds no leaves`() {
        val recipes = recipes(row(outer, inner, 1000), row(outer, a, 1000))

        assertEquals(
            ComboExpansionResult.Leaves(listOf(leaf(a, 1000, a))),
            ComboExpansion.expand(outer, QuantityMilli(1000), recipes, modes),
        )
    }

    @Test
    fun `a combo without recipe has no leaves`() {
        assertEquals(
            ComboExpansionResult.Leaves(emptyList()),
            ComboExpansion.expand(kit, QuantityMilli(1000), emptyMap(), modes),
        )
    }

    private fun recipes(vararg rows: RecipeComponent): Map<Long, List<RecipeComponent>> =
        rows.toList().groupBy { it.parentProductId }

    private fun leaf(productId: Long, milli: Long, via: Long) =
        ExpandedLeaf(productId, QuantityMilli(milli), via)
}
