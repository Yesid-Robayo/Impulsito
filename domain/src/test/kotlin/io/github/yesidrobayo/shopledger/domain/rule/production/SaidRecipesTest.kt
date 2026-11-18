package io.github.yesidrobayo.shopledger.domain.rule.production

import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.catalog.RecipeComponent
import io.github.yesidrobayo.shopledger.domain.model.catalog.RecipeLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Producir con lo que se dijo: los insumos de la tanda y la receta que queda, a mano. */
class SaidRecipesTest {
    private fun input(id: Long, milli: Long) = ProductionInput(id, QuantityMilli(milli))

    private fun row(id: Long, milli: Long) = RecipeLine(id, QuantityMilli(milli))

    @Test
    fun `what was said replaces the recipe input in its place and a new one goes last`() {
        // La receta pide 1 kg de harina y 10 huevos; se dijo 1,25 kg de harina y 0,1 kg de sal.
        val inputs = SaidRecipes.inputs(
            fromRecipe = listOf(input(FLOUR, 1_000), input(EGG, 10_000)),
            said = listOf(input(SALT, 100), input(FLOUR, 1_250)),
        )

        assertEquals(
            listOf(input(FLOUR, 1_250), input(EGG, 10_000), input(SALT, 100)),
            inputs,
        )
    }

    @Test
    fun `without a recipe, the batch is what was said`() {
        assertEquals(
            listOf(input(FLOUR, 400)),
            SaidRecipes.inputs(emptyList(), listOf(input(FLOUR, 400))),
        )
    }

    @Test
    fun `the recipe keeps what one unit takes, with what was said divided by the batch`() {
        // 2 pasteles con 0,400 kg de harina y 3 huevos: 0,200 kg y 1,5 huevos por pastel.
        assertEquals(
            listOf(row(FLOUR, 200), row(EGG, 1_500)),
            SaidRecipes.recipe(
                emptyList(),
                listOf(input(FLOUR, 400), input(EGG, 3_000)),
                QuantityMilli(2_000),
            ),
        )
    }

    @Test
    fun `a stored row that was not named stays, a named one changes and a new one is added`() {
        // 5 tortas con 1,25 kg de harina y 0,1 kg de sal: 0,250 kg y 0,020 kg por torta.
        val recipe = SaidRecipes.recipe(
            stored = listOf(row(FLOUR, 200), row(EGG, 2_000)),
            said = listOf(input(FLOUR, 1_250), input(SALT, 100)),
            produced = QuantityMilli(5_000),
        )

        assertEquals(listOf(row(FLOUR, 250), row(EGG, 2_000), row(SALT, 20)), recipe)
    }

    @Test
    fun `a per unit amount rounds half up and one above the line limit gives nothing`() {
        // 1 huevo para 3 panes: 333,33 milésimas, que quedan en 333; 2 para 3, en 667.
        assertEquals(
            listOf(row(EGG, 333), row(FLOUR, 667)),
            SaidRecipes.recipe(
                emptyList(),
                listOf(input(EGG, 1_000), input(FLOUR, 2_000)),
                QuantityMilli(3_000),
            ),
        )
        // 2000 kg para una milésima: dos millones de kilos por unidad.
        assertNull(
            SaidRecipes.recipe(emptyList(), listOf(input(FLOUR, 2_000_000)), QuantityMilli(1)),
        )
    }

    private companion object {
        const val FLOUR = 1L
        const val EGG = 2L
        const val SALT = 3L
        const val FILLING = 4L
    }

    // Un combo de la receta, contado una sola vez.

    private fun component(parent: Long, id: Long, milli: Long) =
        RecipeComponent(parent * 100 + id, parent, id, QuantityMilli(milli))

    /** Un combo de relleno: 0,100 kg de harina y 2 huevos por cada uno. */
    private val recipes =
        mapOf(FILLING to listOf(component(FILLING, FLOUR, 100), component(FILLING, EGG, 2_000)))
    private val modes = mapOf(
        FILLING to ProductStockMode.COMPONENTS,
        FLOUR to ProductStockMode.TRACKED,
        EGG to ProductStockMode.TRACKED,
    )

    @Test
    fun `repeated inputs are summed in the place they first appeared`() {
        assertEquals(
            listOf(input(FLOUR, 300), input(EGG, 2_000)),
            SaidRecipes.merged(listOf(input(FLOUR, 100), input(EGG, 2_000), input(FLOUR, 200))),
        )
    }

    @Test
    fun `a combo that brings a said input opens in its components`() {
        // La receta lleva 2 rellenos y 0,050 kg de harina suelta; se dijo cuánto huevo lleva.
        val stored = listOf(row(FILLING, 2_000), row(FLOUR, 50))
        assertEquals(
            listOf(row(FLOUR, 250), row(EGG, 4_000)),
            SaidRecipes.opened(stored, setOf(EGG), recipes, modes),
        )
        // Con eso, actualizar la receta con el huevo no lo cuenta dos veces.
        assertEquals(
            listOf(row(FLOUR, 250), row(EGG, 3_000)),
            SaidRecipes.recipe(
                SaidRecipes.opened(stored, setOf(EGG), recipes, modes).orEmpty(),
                listOf(input(EGG, 6_000)),
                QuantityMilli(2_000),
            ),
        )
    }

    @Test
    fun `a combo stays whole when nothing it brings was said alone`() {
        val stored = listOf(row(FILLING, 2_000), row(EGG, 1_000))
        // El combo mismo se dijo, o lo dicho ya va suelto en la receta, o no lo trae.
        assertEquals(stored, SaidRecipes.opened(stored, setOf(FILLING, EGG), recipes, modes))
        assertEquals(stored, SaidRecipes.opened(stored, setOf(EGG), recipes, modes))
        assertEquals(stored, SaidRecipes.opened(stored, setOf(SALT), recipes, modes))
        // Uno que no es combo, aunque tenga receta, no se abre.
        val tracked = modes + (FILLING to ProductStockMode.TRACKED)
        assertEquals(stored, SaidRecipes.opened(stored, setOf(FLOUR), recipes, tracked))
        assertEquals(
            listOf(row(SALT, 10)),
            SaidRecipes.opened(listOf(row(SALT, 10)), setOf(FLOUR), recipes, modes),
        )
        // Un combo sin receta leída no trae nada que abrir.
        assertEquals(stored, SaidRecipes.opened(stored, setOf(FLOUR), emptyMap(), modes))
    }

    @Test
    fun `a combo whose components round to zero cannot be opened`() {
        assertNull(SaidRecipes.opened(listOf(row(FILLING, 1)), setOf(FLOUR), recipes, modes))
    }
}
