package io.github.yesidrobayo.shopledger.domain.rule.catalog

import io.github.yesidrobayo.shopledger.domain.model.Checked
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.Scale
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.catalog.RecipeComponent
import io.github.yesidrobayo.shopledger.domain.model.catalog.RecipeLine
import io.github.yesidrobayo.shopledger.domain.model.stock.CostState

/**
 * Cuántos combos enteros alcanzan con el stock de sus componentes.
 *
 * [units] es `null` si ningún componente lleva stock: nada lo limita. [limitingProductId] es el
 * componente que alcanza para menos, el primero de la receta si empatan, para nombrarlo en
 * pantalla.
 */
data class ComboAvailability(val units: Long?, val limitingProductId: Long?)

/**
 * Lo que cuesta y cuánto alcanza una unidad de un combo con una receta dada, como la ve la persona
 * mientras la arma.
 *
 * [rowCosts] es el costo de cada fila de la receta, por id de su componente: el de la hoja, o la
 * suma de las hojas de un combo de adentro. [unitCost] es la suma, la que congelaría vender una
 * unidad hoy. Los dos faltan si la receta no se puede expandir o una cifra no cabe: la pantalla
 * muestra un guion en vez de un costo inventado. [hasUncostedLeaf] avisa que alguna hoja cuesta
 * cero, así que el margen puede verse mejor de lo que es.
 */
data class RecipePreview(
    val rowCosts: Map<Long, Money>,
    val unitCost: Money?,
    val availability: ComboAvailability?,
    val hasUncostedLeaf: Boolean,
)

/**
 * Las cifras de un combo que salen de sus componentes: cuántos alcanzan y qué cuesta uno.
 *
 * Un combo no tiene libro propio, así que no tiene saldo ni costo guardados: los dos se calculan
 * cada vez desde sus hojas. Ninguna de estas cifras bloquea una venta, porque vender nunca se
 * detiene por falta de stock.
 */
object ComboFigures {
    /**
     * Cuántos combos enteros alcanzan, según [leavesForOne], las hojas de una unidad del combo, y
     * [products], los productos de esas hojas.
     *
     * Cada componente con stock alcanza para su saldo dividido por lo que consume un combo,
     * redondeado hacia abajo, y el combo alcanza para el menor de ellos. Un componente que aparece
     * por dos caminos suma lo que consume en los dos. Uno con saldo negativo o archivado alcanza
     * para cero: archivado ya no se repone. Los componentes sin stock no limitan.
     */
    fun availability(
        leavesForOne: List<ExpandedLeaf>,
        products: Map<Long, Product>,
    ): ComboAvailability {
        val needs = LinkedHashMap<Long, Long>()
        for (leaf in leavesForOne) {
            val product = requireLeaf(products, leaf.productId)
            if (product.details.stockMode != ProductStockMode.TRACKED) continue
            needs[leaf.productId] = Math.addExact(needs[leaf.productId] ?: 0L, leaf.quantity.milli)
        }
        var units: Long? = null
        var limiting: Long? = null
        for ((productId, need) in needs) {
            val product = products.getValue(productId)
            val onHand = if (product.isArchived) 0L else product.onHand.milli
            val reach = if (onHand <= 0L) 0L else onHand / need
            if (units == null || reach < units) {
                units = reach
                limiting = productId
            }
        }
        return ComboAvailability(units, limiting)
    }

    /**
     * Lo que la regla de modos de stock necesita para costear [leaves], ya expandidas: cada hoja
     * según su modo y el estado de costo de cada componente con stock, tal como está guardado en
     * [products].
     */
    fun subjectOf(
        leaves: List<ExpandedLeaf>,
        products: Map<Long, Product>,
    ): LineStockSubject.Components {
        val states = LinkedHashMap<Long, CostState>()
        val componentLeaves = leaves.map { leaf ->
            val product = requireLeaf(products, leaf.productId)
            when (product.details.stockMode) {
                ProductStockMode.TRACKED -> {
                    states[product.id] =
                        CostState(product.onHand, product.averageCost, product.stockValue)
                    ComponentLeaf.Tracked(product.id, leaf.quantity)
                }

                ProductStockMode.UNTRACKED ->
                    ComponentLeaf.Untracked(
                        product.id,
                        leaf.quantity,
                        product.details.fixedUnitCost,
                    )

                ProductStockMode.COMPONENTS ->
                    error("Una hoja expandida nunca es un combo")
            }
        }
        return LineStockSubject.Components(componentLeaves, states)
    }

    /**
     * Las cifras de una unidad del combo [comboId] si su receta fuera [lines]. [stored] son las
     * recetas guardadas, de las que salen las de los combos de adentro; la del propio combo se
     * reemplaza por [lines]. [products] trae todos los productos de las recetas, también los
     * archivados.
     */
    fun preview(
        comboId: Long,
        lines: List<RecipeLine>,
        stored: List<RecipeComponent>,
        products: Map<Long, Product>,
    ): RecipePreview {
        val draft = lines.map { RecipeComponent(0L, comboId, it.componentProductId, it.quantity) }
        val recipes = stored.filter { it.parentProductId != comboId }
            .groupBy { it.parentProductId } + (comboId to draft)
        val modes = products.mapValues { it.value.details.stockMode }
        val one = QuantityMilli(Scale.MILLI)
        val leaves = when (val result = ComboExpansion.expand(comboId, one, recipes, modes)) {
            is ComboExpansionResult.Leaves -> result.leaves

            is ComboExpansionResult.LeafRoundsToZero,
            ComboExpansionResult.QuantityOutOfRange,
            ComboExpansionResult.TooDeep,
            -> return RecipePreview(emptyMap(), null, null, hasUncostedLeaf = false)
        }
        val availability = availability(leaves, products)
        val plan = when (val result = StockModeRules.planLine(subjectOf(leaves, products))) {
            is Checked.Value -> result.value

            Checked.Overflow, is Checked.AboveLimit ->
                return RecipePreview(emptyMap(), null, availability, hasUncostedLeaf = false)
        }
        val planned = (plan as LineStockPlan.Components).leaves
        val rowCosts = LinkedHashMap<Long, Money>()
        leaves.zip(planned).forEach { (leaf, cost) ->
            rowCosts[leaf.viaComponentId] =
                (rowCosts[leaf.viaComponentId] ?: Money.ZERO) + cost.lineCost
        }
        return RecipePreview(
            rowCosts = rowCosts,
            unitCost = plan.lineCost,
            availability = availability,
            hasUncostedLeaf = planned.any { it.lineCost == Money.ZERO },
        )
    }

    private fun requireLeaf(products: Map<Long, Product>, productId: Long): Product =
        requireNotNull(products[productId]) { "Falta el producto de una hoja del combo" }
}
