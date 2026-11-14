package io.github.yesidrobayo.shopledger.domain.rule.catalog

import io.github.yesidrobayo.shopledger.domain.model.Checked
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.catalog.RecipeLine
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

    @Test
    fun `the preview of a recipe being built costs each row and the whole unit`() {
        // El combo lleva 1 de una caja (otro combo con 2 de A y el servicio) y 1 de B: la fila de
        // la caja cuesta 2000 + 300, la de B 2500, y la unidad 4800. A alcanza para 5 cajas y B
        // para 10, así que limita A.
        val box = 30L
        val products = catalog(
            product(a, onHand = 10000, averageCost = 1000, stockValue = 10_000),
            product(b, onHand = 10000, averageCost = 2500, stockValue = 25_000),
            product(service, ProductStockMode.UNTRACKED, fixedUnitCost = 300),
            product(box, ProductStockMode.COMPONENTS),
            product(combo, ProductStockMode.COMPONENTS),
        )
        val stored = listOf(
            ComboTestProducts.row(box, a, 2000),
            ComboTestProducts.row(box, service, 1000),
            // La receta guardada del propio combo se reemplaza por la que se arma.
            ComboTestProducts.row(combo, service, 5000),
        )

        val preview = ComboFigures.preview(
            combo,
            listOf(RecipeLine(box, QuantityMilli(1000)), RecipeLine(b, QuantityMilli(1000))),
            stored,
            products,
        )

        assertEquals(
            RecipePreview(
                rowCosts = mapOf(box to Money(2300), b to Money(2500)),
                unitCost = Money(4800),
                availability = ComboAvailability(units = 5, limitingProductId = a),
                hasUncostedLeaf = false,
            ),
            preview,
        )
    }

    @Test
    fun `the preview warns about a leaf without cost`() {
        val products = catalog(product(a), product(combo, ProductStockMode.COMPONENTS))

        val preview = ComboFigures.preview(
            combo,
            listOf(RecipeLine(a, QuantityMilli(1000))),
            emptyList(),
            products,
        )

        assertEquals(Money.ZERO, preview.unitCost)
        assertEquals(true, preview.hasUncostedLeaf)
    }

    @Test
    fun `a recipe that cannot be expanded has no figures`() {
        val products = catalog(product(a), product(combo, ProductStockMode.COMPONENTS))

        val preview = ComboFigures.preview(
            combo,
            listOf(RecipeLine(a, QuantityMilli(0))),
            emptyList(),
            products,
        )

        assertEquals(RecipePreview(emptyMap(), null, null, hasUncostedLeaf = false), preview)
    }

    @Test
    fun `a third level or a quantity out of range has no figures either`() {
        val inner = 31L
        val products = catalog(
            product(a),
            product(inner, ProductStockMode.COMPONENTS),
            product(combo, ProductStockMode.COMPONENTS),
        )
        // El combo de adentro contiene al propio combo: expandirlo daría un tercer nivel.
        val stored = listOf(ComboTestProducts.row(inner, combo, 1000))
        val empty = RecipePreview(emptyMap(), null, null, hasUncostedLeaf = false)

        val deep = ComboFigures.preview(
            combo,
            listOf(RecipeLine(inner, QuantityMilli(1000))),
            stored,
            products,
        )
        val large = ComboFigures.preview(
            combo,
            listOf(RecipeLine(a, QuantityMilli(2_000_000_000L))),
            emptyList(),
            products,
        )

        assertEquals(empty, deep)
        assertEquals(empty, large)
    }

    @Test
    fun `a cost that does not fit in a long drops the cost too`() {
        val products = catalog(
            product(a, averageCost = 9_000_000_000_000_000L),
            product(combo, ProductStockMode.COMPONENTS),
        )

        val preview = ComboFigures.preview(
            combo,
            listOf(RecipeLine(a, QuantityMilli(1_000_000_000L))),
            emptyList(),
            products,
        )

        assertEquals(null, preview.unitCost)
        assertEquals(ComboAvailability(0, a), preview.availability)
    }

    @Test
    fun `a cost above a line keeps what it is enough for and drops the cost`() {
        // Un millón de unidades a 10^12 cada una pasa del total de una línea.
        val products = catalog(
            product(a, onHand = 1000, averageCost = 1_000_000_000_000L),
            product(combo, ProductStockMode.COMPONENTS),
        )

        val preview = ComboFigures.preview(
            combo,
            listOf(RecipeLine(a, QuantityMilli(1_000_000_000L))),
            emptyList(),
            products,
        )

        assertEquals(
            RecipePreview(emptyMap(), null, ComboAvailability(0, a), hasUncostedLeaf = false),
            preview,
        )
    }

    private fun catalog(vararg products: Product): Map<Long, Product> =
        products.associateBy { it.id }

    private fun leaf(productId: Long, milli: Long, via: Long = productId) =
        ExpandedLeaf(productId, QuantityMilli(milli), via)
}
