package io.github.yesidrobayo.shopledger.domain.rule.production

import io.github.yesidrobayo.shopledger.domain.model.Checked
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.Scale
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.catalog.RecipeComponent
import io.github.yesidrobayo.shopledger.domain.model.stock.CostState
import io.github.yesidrobayo.shopledger.domain.model.valueOrNull
import io.github.yesidrobayo.shopledger.domain.rule.WeightedAverageCost
import io.github.yesidrobayo.shopledger.domain.rule.catalog.ComboExpansion
import io.github.yesidrobayo.shopledger.domain.rule.catalog.ComboExpansionResult
import io.github.yesidrobayo.shopledger.domain.rule.catalog.ComboFigures
import io.github.yesidrobayo.shopledger.domain.rule.catalog.ExpandedLeaf
import io.github.yesidrobayo.shopledger.domain.rule.catalog.LeafStockPlan
import io.github.yesidrobayo.shopledger.domain.rule.catalog.LineStockPlan
import io.github.yesidrobayo.shopledger.domain.rule.catalog.StockModeRules
import io.github.yesidrobayo.shopledger.domain.rule.roundDiv

/** Un insumo de una producción: un producto que no es combo y cuánto se consume de él. */
data class ProductionInput(val productId: Long, val quantity: QuantityMilli)

/** Los insumos que pide la receta para una cantidad, o por qué no se pueden calcular. */
sealed interface RecipeInputsResult {
    /** Los insumos, en el orden de la receta, un solo renglón por producto. */
    data class Inputs(val inputs: List<ProductionInput>) : RecipeInputsResult

    /** El insumo [productId] consume menos de media milésima con esta cantidad. */
    data class InputRoundsToZero(val productId: Long) : RecipeInputsResult

    /** La cantidad de un insumo no cabe o pasa del tope de una línea. */
    data object QuantityOutOfRange : RecipeInputsResult

    /** La receta tiene un tercer nivel: solo llega con una base dañada. */
    data object TooDeep : RecipeInputsResult
}

/**
 * Lo que deja una producción: el costo de cada insumo, el total y el estado de costo de lo
 * producido. [inputs] va en el orden recibido; un insumo con stock trae su salida, y uno sin stock
 * su costo fijo. [totalCost] es la suma de los insumos, el valor con que entra lo producido, y
 * [unitCost] lo que costó cada unidad producida, redondeado, solo para mostrarlo.
 * [inputStatesAfter] es el estado de cada insumo con stock tras su salida.
 */
data class ProductionPlan(
    val inputs: List<LeafStockPlan>,
    val totalCost: Money,
    val unitCost: Money,
    val outputBefore: CostState,
    val outputAfter: CostState,
    val inputStatesAfter: Map<Long, CostState>,
)

/**
 * Cómo se produce un producto con su receta.
 *
 * Producir es vender la receta al propio negocio: cada insumo con stock sale con `costoDeSalida`,
 * igual que la hoja de un combo vendido, y uno sin stock, como un empaque o una hora de trabajo,
 * cuesta su costo fijo sin mover stock. Lo producido entra con `entrada` por la suma de todo, así
 * que su costo promedio sale de lo que de verdad se consumió. Un combo de la receta se expande a
 * sus componentes, como al venderlo.
 *
 * Nunca rechaza por falta de insumos: el stock de un insumo puede quedar negativo, y la pantalla
 * solo lo avisa.
 */
object ProductionRules {
    /**
     * Los insumos que pide la receta de [productId] para producir [quantity] milésimas.
     *
     * [recipes] trae la receta de cada producto que aparece, por id del padre, y [modes] el modo
     * de stock de cada componente. Un insumo que llega por dos caminos, suelto y dentro de un
     * combo de la receta, queda en un solo renglón con la suma, en el lugar donde apareció
     * primero.
     */
    fun recipeInputs(
        productId: Long,
        quantity: QuantityMilli,
        recipes: Map<Long, List<RecipeComponent>>,
        modes: Map<Long, ProductStockMode>,
    ): RecipeInputsResult {
        val expansion = ComboExpansion.expand(productId, quantity, recipes, modes)
        val leaves = when (val result = expansion) {
            is ComboExpansionResult.Leaves -> result.leaves

            is ComboExpansionResult.LeafRoundsToZero ->
                return RecipeInputsResult.InputRoundsToZero(result.productId)

            ComboExpansionResult.QuantityOutOfRange -> return RecipeInputsResult.QuantityOutOfRange

            ComboExpansionResult.TooDeep -> return RecipeInputsResult.TooDeep
        }
        val merged = LinkedHashMap<Long, Long>()
        for (leaf in leaves) {
            // Cada hoja está dentro del tope de una línea: la suma de dos cabe en Long.
            val total = (merged[leaf.productId] ?: 0L) + leaf.quantity.milli
            if (!Limit.LINE_QUANTITY_MILLI.allows(total)) {
                return RecipeInputsResult.QuantityOutOfRange
            }
            merged[leaf.productId] = total
        }
        return RecipeInputsResult.Inputs(
            merged.map { (id, milli) -> ProductionInput(id, QuantityMilli(milli)) },
        )
    }

    /**
     * El costo de producir [quantity] milésimas de lo que hoy está en [output], consumiendo
     * [inputs]. [products] trae cada insumo, con su modo, su stock y su costo de este momento.
     *
     * Rechaza si una cifra no cabe en `Long`, si el costo de un insumo o el total pasa del tope de
     * un documento, o si lo producido quedaría con su stock, su valor o su costo promedio sobre
     * sus topes.
     */
    fun plan(
        output: CostState,
        quantity: QuantityMilli,
        inputs: List<ProductionInput>,
        products: Map<Long, Product>,
    ): Checked<ProductionPlan> {
        val leaves = inputs.map { ExpandedLeaf(it.productId, it.quantity, it.productId) }
        val subject = ComboFigures.subjectOf(leaves, products)
        val planned = when (val result = StockModeRules.planLine(subject)) {
            // Una receta expandida siempre se planea como las hojas de un combo.
            is Checked.Value -> result.value as LineStockPlan.Components

            Checked.Overflow -> return Checked.Overflow

            is Checked.AboveLimit -> return result
        }
        val total = planned.lineCost
        // El total no pasa del tope de un documento, así que por mil todavía cabe en Long.
        val unitCost = roundDiv(Math.multiplyExact(total.minor, Scale.MILLI), quantity.milli)
        if (!Limit.UNIT_MONEY.allows(unitCost)) return Checked.AboveLimit(Limit.UNIT_MONEY)
        val after = WeightedAverageCost.entry(output, quantity, total).valueOrNull()
            ?: return Checked.Overflow
        val overLimit = when {
            !Limit.ON_HAND_MILLI.allows(after.onHand.milli) -> Limit.ON_HAND_MILLI
            !Limit.STOCK_VALUE.allows(after.stockValue.minor) -> Limit.STOCK_VALUE
            !Limit.UNIT_MONEY.allows(after.averageCost.minor) -> Limit.UNIT_MONEY
            else -> null
        }
        if (overLimit != null) return Checked.AboveLimit(overLimit)
        return Checked.Value(
            ProductionPlan(
                inputs = planned.leaves,
                totalCost = total,
                unitCost = Money(unitCost),
                outputBefore = output,
                outputAfter = after,
                inputStatesAfter = planned.costStatesAfter,
            ),
        )
    }
}
