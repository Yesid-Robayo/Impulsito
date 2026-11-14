package io.github.yesidrobayo.shopledger.domain.rule.catalog

import io.github.yesidrobayo.shopledger.domain.model.Checked
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.stock.CostState
import io.github.yesidrobayo.shopledger.domain.rule.catalog.ComboTestProducts.product
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

// Montos en unidad menor de una moneda sin decimales y cantidades en milésimas. Los valores
// esperados salen a mano de los ejemplos de las reglas de combos.
class ComboFiguresTest {
    private val a = 10L
    private val b = 11L
    private val service = 12L
    private val combo = 20L

    @Test
    fun `nine of A and three of B are enough for three kits, limited by B`() {
        val products = catalog(product(a, onHand = 9000), product(b, onHand = 3000))

        val availability =
            ComboFigures.availability(listOf(leaf(a, 2000), leaf(b, 1000)), products)

        assertEquals(ComboAvailability(units = 3, limitingProductId = b), availability)
    }

    @Test
    fun `a component reached by two paths adds both needs`() {
        // A va directo (1) y dentro de otro combo (1): 5 de A alcanzan para 2 combos, no para 5.
        val products = catalog(product(a, onHand = 5000))

        val availability = ComboFigures.availability(
            listOf(leaf(a, 1000), leaf(a, 1500, via = combo)),
            products,
        )

        assertEquals(ComboAvailability(units = 2, limitingProductId = a), availability)
    }

    @Test
    fun `negative or archived stock is enough for none`() {
        val negative = catalog(product(a, onHand = -2000), product(b, onHand = 4000))
        val archived =
            catalog(product(a, onHand = 4000, archived = true), product(b, onHand = 4000))
        val leaves = listOf(leaf(a, 1000), leaf(b, 1000))

        assertEquals(ComboAvailability(0, a), ComboFigures.availability(leaves, negative))
        assertEquals(ComboAvailability(0, a), ComboFigures.availability(leaves, archived))
    }

    @Test
    fun `the first component wins a tie`() {
        val products = catalog(product(a, onHand = 2000), product(b, onHand = 2000))

        val availability =
            ComboFigures.availability(listOf(leaf(a, 1000), leaf(b, 1000)), products)

        assertEquals(ComboAvailability(units = 2, limitingProductId = a), availability)
    }

    @Test
    fun `components without stock never limit`() {
        val products = catalog(product(service, ProductStockMode.UNTRACKED))

        assertEquals(
            ComboAvailability(units = null, limitingProductId = null),
            ComboFigures.availability(listOf(leaf(service, 1000)), products),
        )
        assertEquals(
            ComboAvailability(units = null, limitingProductId = null),
            ComboFigures.availability(emptyList(), products),
        )
    }

    @Test
    fun `two kits of the business rules example cost 9600`() {
        // A a 1000 por unidad, B a 2500 y un servicio de costo fijo 300: 4000 + 5000 + 600.
        val products = catalog(
            product(a, onHand = 10000, averageCost = 1000, stockValue = 10_000),
            product(b, onHand = 10000, averageCost = 2500, stockValue = 25_000),
            product(service, ProductStockMode.UNTRACKED, fixedUnitCost = 300),
        )
        val leaves = listOf(leaf(a, 4000), leaf(b, 2000), leaf(service, 2000))

        val subject = ComboFigures.subjectOf(leaves, products)
        val plan = StockModeRules.planLine(subject)

        assertEquals(
            listOf(
                ComponentLeaf.Tracked(a, QuantityMilli(4000)),
                ComponentLeaf.Tracked(b, QuantityMilli(2000)),
                ComponentLeaf.Untracked(service, QuantityMilli(2000), Money(300)),
            ),
            subject.leaves,
        )
        assertEquals(
            mapOf(
                a to CostState(QuantityMilli(10000), Money(1000), Money(10_000)),
                b to CostState(QuantityMilli(10000), Money(2500), Money(25_000)),
            ),
            subject.costStates,
        )
        val components = (plan as Checked.Value).value as LineStockPlan.Components
        assertEquals(Money(9600), components.lineCost)
        assertEquals(
            listOf(Money(4000), Money(5000), Money(600)),
            components.leaves.map { it.lineCost },
        )
    }

    @Test
    fun `a combo is never a leaf`() {
        val products = catalog(product(combo, ProductStockMode.COMPONENTS))

        assertThrows(IllegalStateException::class.java) {
            ComboFigures.subjectOf(listOf(leaf(combo, 1000)), products)
        }
    }

    @Test
    fun `a leaf without its product is a programming error`() {
        assertThrows(IllegalArgumentException::class.java) {
            ComboFigures.availability(listOf(leaf(a, 1000)), emptyMap())
        }
    }

    private fun catalog(vararg products: Product): Map<Long, Product> =
        products.associateBy { it.id }

    private fun leaf(productId: Long, milli: Long, via: Long = productId) =
        ExpandedLeaf(productId, QuantityMilli(milli), via)
}
