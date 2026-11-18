package io.github.yesidrobayo.shopledger.domain.rule.production

import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.Scale
import io.github.yesidrobayo.shopledger.domain.model.catalog.RecipeLine
import io.github.yesidrobayo.shopledger.domain.model.valueOrNull
import io.github.yesidrobayo.shopledger.domain.rule.mulDivRound

/**
 * Producir con lo que se dijo que llevó la tanda, como "hice 2 pasteles con 400 g de harina".
 *
 * - **Se produce con lo dicho**, solo en esta tanda: lo que la receta lleva y no se nombró sale de
 *   la receta, escalado a lo producido, y lo que se nombró y la receta no lleva se suma a la tanda.
 * - **La receta guarda lo que lleva una unidad**: lo dicho para la tanda se divide entre lo
 *   producido, redondeado. 400 g de harina para 2 pasteles son 200 g por pastel.
 *
 * Todo va en milésimas de la unidad base de cada producto: las unidades ya se llevaron a ella.
 */
object SaidRecipes {
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
