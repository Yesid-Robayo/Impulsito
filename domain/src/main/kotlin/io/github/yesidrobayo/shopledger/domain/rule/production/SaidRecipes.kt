package io.github.yesidrobayo.shopledger.domain.rule.production

import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.Scale
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.catalog.RecipeComponent
import io.github.yesidrobayo.shopledger.domain.model.catalog.RecipeLine
import io.github.yesidrobayo.shopledger.domain.model.valueOrNull
import io.github.yesidrobayo.shopledger.domain.rule.catalog.ComboExpansion
import io.github.yesidrobayo.shopledger.domain.rule.catalog.ComboExpansionResult
import io.github.yesidrobayo.shopledger.domain.rule.mulDivRound

/**
 * Producir con lo que se dijo que llevó la tanda, como "hice 2 pasteles con 400 g de harina".
 *
 * - **Se produce con lo dicho**, solo en esta tanda: lo que la receta lleva y no se nombró sale de
 *   la receta, escalado a lo producido, y lo que se nombró y la receta no lleva se suma a la tanda.
 * - **La receta guarda lo que lleva una unidad**: lo dicho para la tanda se divide entre lo
 *   producido, redondeado. 400 g de harina para 2 pasteles son 200 g por pastel.
 *
 * - **Un combo de la receta se cuenta una sola vez.** Un insumo dicho que la receta solo lleva
 *   dentro de un combo reemplaza lo que el combo trae de él, y al actualizar la receta el combo se
 *   abre en sus componentes: si no, el insumo quedaría dos veces, suelto y dentro del combo.
 *
 * Todo va en milésimas de la unidad base de cada producto: las unidades ya se llevaron a ella.
 */
object SaidRecipes {
    /**
     * Los insumos de [inputs] con los repetidos en un solo renglón, sumados, en el lugar donde
     * aparecieron primero: así lo que trae un combo dicho y lo que lleva la receta suelto se juntan.
     */
    fun merged(inputs: List<ProductionInput>): List<ProductionInput> {
        val sums = LinkedHashMap<Long, Long>()
        for (input in inputs) {
            sums[input.productId] =
                (sums[input.productId] ?: 0L) + input.quantity.milli
        }
        return sums.map { (id, milli) -> ProductionInput(id, QuantityMilli(milli)) }
    }

    /**
     * Las filas de [stored] con cada combo abierto en sus componentes, por unidad, si trae un
     * insumo de [said] que la receta no lleva suelto y el combo mismo no se dijo: así actualizar la
     * receta con ese insumo no lo cuenta dos veces. [recipes] trae la receta de cada combo y
     * [modes] el modo de stock de cada componente, como en `ComboExpansion`. Un componente que ya
     * está suelto se suma a su fila. `null` si un componente redondea a cero o pasa de su tope.
     */
    fun opened(
        stored: List<RecipeLine>,
        said: Set<Long>,
        recipes: Map<Long, List<RecipeComponent>>,
        modes: Map<Long, ProductStockMode>,
    ): List<RecipeLine>? {
        val loose = stored.mapTo(HashSet()) { it.componentProductId }
        val rows = LinkedHashMap<Long, Long>()
        for (row in stored) {
            val id = row.componentProductId
            val opens = modes[id] == ProductStockMode.COMPONENTS && id !in said &&
                recipes[id].orEmpty().any {
                    it.componentProductId in said &&
                        it.componentProductId !in loose
                }
            val parts = if (opens) {
                when (val expanded = ComboExpansion.expand(id, row.quantity, recipes, modes)) {
                    is ComboExpansionResult.Leaves ->
                        expanded.leaves.map { it.productId to it.quantity.milli }

                    else -> return null
                }
            } else {
                listOf(id to row.quantity.milli)
            }
            for ((part, milli) in parts) rows[part] = (rows[part] ?: 0L) + milli
        }
        return rows.map { (id, milli) -> RecipeLine(id, QuantityMilli(milli)) }
    }

    /**
     * Los insumos de la tanda: los de la receta, [fromRecipe], ya escalados a lo producido, con lo
     * dicho, [said], encima. Un insumo dicho toma el lugar del de la receta, y uno que la receta no
     * lleva va al final, en el orden dicho.
     */
    fun inputs(
        fromRecipe: List<ProductionInput>,
        said: List<ProductionInput>,
    ): List<ProductionInput> {
        val byId = said.associateBy { it.productId }
        val known = fromRecipe.mapTo(HashSet()) { it.productId }
        return fromRecipe.map { byId[it.productId] ?: it } + said.filter { it.productId !in known }
    }

    /**
     * La receta que queda tras producir [produced] milésimas con lo dicho, [said]: las filas de
     * [stored] que no se nombraron quedan como estaban, y cada insumo dicho, dividido entre lo
     * producido, toma el lugar de su fila o se agrega al final. `null` si lo que lleva una unidad
     * pasa del tope de una línea.
     *
     * Una cantidad por unidad que redondea a cero queda en cero: la receta la rechaza al guardarla,
     * con su motivo.
     */
    fun recipe(
        stored: List<RecipeLine>,
        said: List<ProductionInput>,
        produced: QuantityMilli,
    ): List<RecipeLine>? {
        val perUnit = said.map { input ->
            val milli = mulDivRound(
                input.quantity.milli,
                Scale.MILLI,
                produced.milli,
                Limit.LINE_QUANTITY_MILLI,
            ).valueOrNull() ?: return null
            RecipeLine(input.productId, QuantityMilli(milli))
        }
        val byId = perUnit.associateBy { it.componentProductId }
        val known = stored.mapTo(HashSet()) { it.componentProductId }
        return stored.map { byId[it.componentProductId] ?: it } +
            perUnit.filter { it.componentProductId !in known }
    }
}
