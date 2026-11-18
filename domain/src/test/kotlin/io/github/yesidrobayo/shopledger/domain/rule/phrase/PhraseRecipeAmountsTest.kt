package io.github.yesidrobayo.shopledger.domain.rule.phrase

import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.phrase.DraftLine
import io.github.yesidrobayo.shopledger.domain.model.phrase.DraftTarget
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseCatalog
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseComponent
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseDraft
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseIntent
import io.github.yesidrobayo.shopledger.domain.model.phrase.RecipeAmount
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.COOKIES
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.DOZEN
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.EGG
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.EMPANADA
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.GRAM
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.KILOGRAM
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.LITER
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.MILK
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.SILO
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.UNIT
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.WHEAT_FLOUR
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Lo que la tarjeta de una producción muestra de la receta junto a cada insumo dicho. Una empanada
 * lleva 0,100 kg de harina de trigo y un huevo; los valores esperados están calculados a mano.
 */
class PhraseRecipeAmountsTest {
    private val catalog: PhraseCatalog = PhraseCatalogs.spanish.let { spanish ->
        spanish.copy(
            products = spanish.products.map { product ->
                when (product.id) {
                    EMPANADA -> product.copy(
                        recipe = listOf(
                            PhraseComponent(WHEAT_FLOUR, QuantityMilli(100)),
                            PhraseComponent(EGG, QuantityMilli(1_000)),
                            // Un insumo que ya no está en el catálogo vigente.
                            PhraseComponent(GONE, QuantityMilli(1_000)),
                            // Uno que por tanda pasaría del tope de una línea.
                            PhraseComponent(SILO, QuantityMilli(1_000_000_000)),
                        ),
                    )

                    else -> product
                }
            },
        )
    }

    private fun line(productId: Long?, milli: Long?, unitId: Long? = null) = DraftLine(
        product = productId?.let { DraftTarget.Existing(it) },
        quantity = milli?.let(::QuantityMilli),
        unitId = unitId,
    )

    private fun made(
        vararg inputs: DraftLine,
        lines: List<DraftLine> = listOf(line(EMPANADA, 10_000)),
    ) = PhraseDraft(PhraseIntent.PRODUCTION, lines = lines, inputs = inputs.toList())

    @Test
    fun `each said input is compared with what the recipe takes for this batch`() {
        val amounts = made(
            // 1250 g de harina: la receta pide 1 kg para 10 empanadas, 1000 g.
            line(WHEAT_FLOUR, 1_250_000, GRAM),
            // Sin cantidad todavía, la receta se muestra igual.
            line(WHEAT_FLOUR, null, GRAM),
            // 10 huevos, lo mismo que la receta, dichos en su unidad base o sin unidad.
            line(EGG, 10_000, UNIT),
            line(EGG, 10_000),
            // Una docena de huevos: la receta pide 10, que son 0,833 docenas.
            line(EGG, 1_000, DOZEN),
            // Litros de harina no se llevan a kilos: la receta se muestra en kilos.
            line(WHEAT_FLOUR, 1_000, LITER),
            line(MILK, 1_000),
            line(GONE, 1_000),
            line(SILO, 1_000),
            DraftLine(DraftTarget.New("Mantequilla"), QuantityMilli(100), null),
            line(null, 1_000),
        ).recipeAmounts(catalog)

        assertEquals(
            listOf(
                RecipeAmount.InRecipe(QuantityMilli(1_000_000), GRAM, same = false),
                RecipeAmount.InRecipe(QuantityMilli(1_000_000), GRAM, same = false),
                RecipeAmount.InRecipe(QuantityMilli(10_000), UNIT, same = true),
                RecipeAmount.InRecipe(QuantityMilli(10_000), UNIT, same = true),
                RecipeAmount.InRecipe(QuantityMilli(833), DOZEN, same = false),
                RecipeAmount.InRecipe(QuantityMilli(1_000), KILOGRAM, same = false),
                RecipeAmount.NotInRecipe,
                RecipeAmount.None,
                RecipeAmount.None,
                RecipeAmount.NotInRecipe,
                RecipeAmount.None,
            ),
            amounts,
        )
    }

    @Test
    fun `without a recipe, a quantity or a single product there is nothing to compare`() {
        val input = line(EGG, 1_000)
        val none = listOf(RecipeAmount.None)

        assertEquals(none, made(input, lines = listOf(line(COOKIES, 1_000))).recipeAmounts(catalog))
        assertEquals(none, made(input, lines = listOf(line(EMPANADA, null))).recipeAmounts(catalog))
        assertEquals(none, made(input, lines = listOf(line(null, 1_000))).recipeAmounts(catalog))
        assertEquals(
            none,
            made(input, lines = listOf(line(EMPANADA, 1_000, DOZEN))).recipeAmounts(catalog),
        )
        assertEquals(
            none,
            made(input, lines = listOf(line(EMPANADA, 1_000), line(EMPANADA, 1_000)))
                .recipeAmounts(catalog),
        )
        assertEquals(none, made(input, lines = listOf(line(GONE, 1_000))).recipeAmounts(catalog))
    }

    private companion object {
        /** Un producto que no está en el catálogo vigente. */
        const val GONE = 999L
    }
}
