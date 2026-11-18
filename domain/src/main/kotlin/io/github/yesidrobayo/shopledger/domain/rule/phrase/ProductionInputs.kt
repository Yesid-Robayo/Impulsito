package io.github.yesidrobayo.shopledger.domain.rule.phrase

import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.Scale
import io.github.yesidrobayo.shopledger.domain.model.phrase.DraftLine
import io.github.yesidrobayo.shopledger.domain.model.phrase.DraftTarget
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseCatalog
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseComponent
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseDraft
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseProduct
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseUnit
import io.github.yesidrobayo.shopledger.domain.model.phrase.RecipeAmount
import io.github.yesidrobayo.shopledger.domain.model.valueOrNull
import io.github.yesidrobayo.shopledger.domain.rule.mulDivRound

/**
 * Lo que dice la receta de cada insumo dicho en una producción de un producto, en su orden, para
 * mostrar la diferencia en la tarjeta antes de registrar.
 *
 * La receta guarda lo que lleva una unidad, en la unidad base de cada insumo: para la tanda se
 * multiplica por lo producido, llevado a la unidad base de lo que se produce. Se muestra en la
 * unidad dicha si se puede llevar a ella, como 200 g de harina que se lleva por kilo, o en la base
 * del insumo. Lo dicho y la receta se comparan en la unidad base.
 */
fun PhraseDraft.recipeAmounts(catalog: PhraseCatalog): List<RecipeAmount> {
    val products = catalog.products.associateBy { it.id }
    val units = catalog.units.associateBy { it.id }
    val made = lines.singleOrNull()
    val product = (made?.product as? DraftTarget.Existing)?.let { products[it.productId] }
    val produced = product?.let { inBase(it, made, units) }
    if (product == null || produced == null || product.recipe.isEmpty()) {
        return inputs.map { RecipeAmount.None }
    }
    val recipe = product.recipe.associateBy { it.productId }
    return inputs.map { input -> amountOf(input, recipe, produced, products, units) }
}

private fun amountOf(
    input: DraftLine,
    recipe: Map<Long, PhraseComponent>,
    produced: QuantityMilli,
    products: Map<Long, PhraseProduct>,
    units: Map<Long, PhraseUnit>,
): RecipeAmount {
    val chosen = input.product ?: return RecipeAmount.None
    val row = (chosen as? DraftTarget.Existing)?.let { recipe[it.productId] }
        ?: return RecipeAmount.NotInRecipe
    val component = products[row.productId] ?: return RecipeAmount.None
    val batch = mulDivRound(
        row.quantity.milli,
        produced.milli,
        Scale.MILLI,
        Limit.LINE_QUANTITY_MILLI,
    ).valueOrNull()?.let(::QuantityMilli) ?: return RecipeAmount.None
    val said = inBase(component, input, units)
    val unitId = input.unitId?.takeIf { it != component.baseUnitId }
    val shown = unitId?.let { quantityIn(component, it, batch, units) }
    return if (unitId == null || shown == null) {
        RecipeAmount.InRecipe(batch, component.baseUnitId, same = said == batch)
    } else {
        RecipeAmount.InRecipe(shown, unitId, same = said == batch)
    }
}

/** La cantidad de [line] en la unidad base de [product], o `null` si falta o no se puede llevar. */
private fun inBase(
    product: PhraseProduct,
    line: DraftLine,
    units: Map<Long, PhraseUnit>,
): QuantityMilli? {
    val quantity = line.quantity ?: return null
    val unitId = line.unitId?.takeIf { it != product.baseUnitId } ?: return quantity
    return baseQuantity(product, unitId, quantity, units)
}
