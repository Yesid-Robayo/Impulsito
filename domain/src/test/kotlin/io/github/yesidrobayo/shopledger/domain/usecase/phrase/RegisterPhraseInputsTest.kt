package io.github.yesidrobayo.shopledger.domain.usecase.phrase

import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductUsage
import io.github.yesidrobayo.shopledger.domain.model.catalog.RecipeComponent
import io.github.yesidrobayo.shopledger.domain.model.catalog.RecipeLine
import io.github.yesidrobayo.shopledger.domain.model.phrase.DraftLine
import io.github.yesidrobayo.shopledger.domain.model.phrase.DraftTarget
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseDraft
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseIntent
import io.github.yesidrobayo.shopledger.domain.rule.production.ProductionInput
import io.github.yesidrobayo.shopledger.domain.usecase.phrase.PhraseWorld.Companion.GRAM
import io.github.yesidrobayo.shopledger.domain.usecase.phrase.PhraseWorld.Companion.KILOGRAM
import io.github.yesidrobayo.shopledger.domain.usecase.phrase.PhraseWorld.Companion.LITER
import io.github.yesidrobayo.shopledger.domain.usecase.production.ProductionRequest
import io.github.yesidrobayo.shopledger.domain.usecase.production.ProductionStep
import io.github.yesidrobayo.shopledger.domain.usecase.sale.product
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Producir con lo que se dijo que llevó, con los casos de uso de mentira: la tarjeta arma la
 * producción con lo dicho, lo que falta de la receta y, si toca, la receta que se guarda. Las
 * cantidades esperadas están calculadas a mano, en milésimas de la unidad base.
 */
class RegisterPhraseInputsTest {
    private val world = PhraseWorld().apply {
        addProducts(
            product(CAKE, "Torta"),
            product(BREAD, "Pan"),
            product(FLOUR, "Harina", unitId = KILOGRAM, usage = ProductUsage.INPUT),
            product(EGG, "Huevo"),
            product(BUTTER, "Mantequilla", unitId = KILOGRAM, usage = ProductUsage.INPUT),
            product(OLD, "Vieja", archived = true),
        )
        // Una torta lleva 0,200 kg de harina y 2 huevos.
        addRecipe(
            RecipeComponent(1L, CAKE, FLOUR, QuantityMilli(200)),
            RecipeComponent(2L, CAKE, EGG, QuantityMilli(2_000)),
        )
    }
    private val writers get() = world.writers
    private val register = world.register()

    private fun line(productId: Long, milli: Long?, unitId: Long? = null) = DraftLine(
        product = DraftTarget.Existing(productId),
        quantity = milli?.let(::QuantityMilli),
        unitId = unitId,
    )

    private fun made(
        productId: Long,
        milli: Long,
        vararg inputs: DraftLine,
        updatesRecipe: Boolean = false,
    ) = PhraseDraft(
        intent = PhraseIntent.PRODUCTION,
        lines = listOf(line(productId, milli)),
        inputs = inputs.toList(),
        updatesRecipe = updatesRecipe,
    )

    private fun input(productId: Long, milli: Long) =
        ProductionInput(productId, QuantityMilli(milli))

    private fun step(productId: Long, milli: Long, vararg inputs: ProductionInput) =
        ProductionRequest(productId, QuantityMilli(milli), inputs.toList(), note = null)

    private fun recipe(vararg rows: Pair<Long, Long>) =
        rows.map { (id, milli) -> RecipeLine(id, QuantityMilli(milli)) }

    @Test
    fun `without a recipe, what was said is produced and saved as the recipe per unit`() = runTest {
        // "hice 2 panes con 400 g de harina y 3 huevos": 0,400 kg y 3 huevos para 2 panes son
        // 0,200 kg y 1,5 huevos por pan.
        val result = register(made(BREAD, 2_000, line(FLOUR, 400_000, GRAM), line(EGG, 3_000)))

        assertEquals(RegisterPhraseResult.Registered(PhraseIntent.PRODUCTION, 14L), result)
        assertEquals(
            listOf(
                ProductionStep(
                    step(BREAD, 2_000, input(FLOUR, 400), input(EGG, 3_000)),
                    recipe(FLOUR to 200L, EGG to 1_500L),
                ),
            ),
            writers.requests,
        )
    }

    @Test
    fun `with a recipe, what was said is used only here and the rest comes from the recipe`() =
        runTest {
            // "hice 5 tortas con 1,25 kg de harina": la receta pide 1 kg; los 10 huevos salen de
            // ella, y la receta no cambia.
            register(made(CAKE, 5_000, line(FLOUR, 1_250)))

            assertEquals(
                listOf(ProductionStep(step(CAKE, 5_000, input(FLOUR, 1_250), input(EGG, 10_000)))),
                writers.requests,
            )
        }

    @Test
    fun `with the switch on, what was said also updates the recipe per unit`() = runTest {
        register(made(CAKE, 5_000, line(FLOUR, 1_250), updatesRecipe = true))

        assertEquals(
            listOf(
                ProductionStep(
                    step(CAKE, 5_000, input(FLOUR, 1_250), input(EGG, 10_000)),
                    recipe(FLOUR to 250L, EGG to 2_000L),
                ),
            ),
            writers.requests,
        )
    }

    @Test
    fun `an input the recipe does not have joins only this batch unless the switch is on`() =
        runTest {
            // "hice 2 tortas con 100 g de mantequilla": la mantequilla va al final.
            register(made(CAKE, 2_000, line(BUTTER, 100_000, GRAM)))
            register(made(CAKE, 2_000, line(BUTTER, 100_000, GRAM), updatesRecipe = true))

            val batch = step(CAKE, 2_000, input(FLOUR, 400), input(EGG, 4_000), input(BUTTER, 100))
            assertEquals(
                listOf(
                    ProductionStep(batch),
                    ProductionStep(batch, recipe(FLOUR to 200L, EGG to 2_000L, BUTTER to 50L)),
                ),
                writers.requests,
            )
        }

    @Test
    fun `an input in another class of unit, gone or for several products is refused`() = runTest {
        assertEquals(
            RegisterPhraseResult.Rejected(PhraseRejection.InputUnitNotConvertible(1)),
            register(made(BREAD, 1_000, line(EGG, 1_000), line(FLOUR, 1_000, LITER))),
        )
        assertEquals(
            RegisterPhraseResult.Rejected(PhraseRejection.ProductUnavailable(OLD)),
            register(made(BREAD, 1_000, line(OLD, 1_000))),
        )
        val twice = made(BREAD, 1_000, line(EGG, 1_000))
            .let { it.copy(lines = it.lines + line(CAKE, 1_000)) }
        assertEquals(
            RegisterPhraseResult.Rejected(PhraseRejection.OneProductAtATime),
            register(twice),
        )
        assertTrue(writers.requests.isEmpty())
    }

    @Test
    fun `a recipe per unit above the limit of a line is refused`() = runTest {
        // 2000 kg para una milésima de pan serían dos millones de kilos por pan.
        assertEquals(
            RegisterPhraseResult.Rejected(PhraseRejection.AmountNotApplicable),
            register(made(BREAD, 1, line(FLOUR, 2_000_000))),
        )
        assertTrue(writers.requests.isEmpty())
    }

    @Test
    fun `several products go in one call, each from its recipe`() = runTest {
        val draft = PhraseDraft(
            intent = PhraseIntent.PRODUCTION,
            lines = listOf(line(CAKE, 3_000), line(CAKE, 1_000)),
        )

        register(draft)

        assertEquals(
            listOf(
                ProductionStep(step(CAKE, 3_000, input(FLOUR, 600), input(EGG, 6_000))),
                ProductionStep(step(CAKE, 1_000, input(FLOUR, 200), input(EGG, 2_000))),
            ),
            writers.requests,
        )
    }

    private companion object {
        const val CAKE = 100L
        const val BREAD = 101L
        const val FLOUR = 102L
        const val EGG = 103L
        const val BUTTER = 104L
        const val OLD = 105L
    }
}
