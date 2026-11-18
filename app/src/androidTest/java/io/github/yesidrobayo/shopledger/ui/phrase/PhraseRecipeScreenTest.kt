package io.github.yesidrobayo.shopledger.ui.phrase

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.catalog.UnitKind
import io.github.yesidrobayo.shopledger.domain.model.phrase.DraftLine
import io.github.yesidrobayo.shopledger.domain.model.phrase.DraftTarget
import io.github.yesidrobayo.shopledger.domain.model.phrase.NumberStyle
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseCatalog
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseComponent
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseDraft
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseLanguage
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseName
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseProduct
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseUnit
import io.github.yesidrobayo.shopledger.domain.model.region.DigitGrouping
import io.github.yesidrobayo.shopledger.domain.model.region.NumberSymbols
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseInterpreter
import io.github.yesidrobayo.shopledger.domain.rule.phrase.correctedBy
import io.github.yesidrobayo.shopledger.domain.rule.phrase.followedBy
import io.github.yesidrobayo.shopledger.domain.rule.phrase.toDraft
import io.github.yesidrobayo.shopledger.domain.usecase.phrase.PhraseReadResult
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Producir por frase en el teléfono, con el intérprete de verdad y un catálogo inventado: un
 * pastel sin receta que pide decir qué lleva, lo que se dice después y el registro; y una torta con
 * receta que muestra en qué se aparta lo dicho, con `Actualizar la receta`. El registro es falso:
 * la prueba ve la tarjeta que llega a registrarse.
 */
@RunWith(AndroidJUnit4::class)
class PhraseRecipeScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources

    private val registered = mutableListOf<PhraseDraft>()

    private fun text(id: Int, vararg args: Any) = resources.getString(id, *args)

    @Test
    fun aProductWithoutRecipeAsksWhatItTakesAndRegistersItWithTheBatch() {
        val model = viewModel()
        show(model)

        say("hice un pastel")
        compose.waitUntil(TIMEOUT_MS) { has(text(R.string.phrase_inputs_missing)) }
        say("lleva 200 g de harina y 3 huevos")
        compose.waitUntil(TIMEOUT_MS) { has(text(R.string.phrase_saves_recipe)) }
        compose.onNodeWithText("Harina").assertIsDisplayed()
        assertTrue(!has(text(R.string.phrase_inputs_missing)))

        compose.onNodeWithText(text(R.string.phrase_register)).performClick()
        compose.waitUntil(TIMEOUT_MS) { registered.isNotEmpty() }
        assertEquals(
            listOf(
                DraftLine(DraftTarget.Existing(FLOUR), QuantityMilli(200_000), GRAM),
                DraftLine(DraftTarget.Existing(EGG), QuantityMilli(3_000), null),
            ),
            registered.single().inputs,
        )
    }

    @Test
    fun theDifferenceWithTheRecipeShowsAndTheSwitchUpdatesIt() {
        val model = viewModel()
        show(model)

        say("hice 2 tortas con 500 g de harina")
        val update = text(R.string.phrase_update_recipe)
        compose.waitUntil(TIMEOUT_MS) { has(update) }
        // La receta pide 400 g para dos tortas: se muestra junto a lo dicho.
        val recipe = text(R.string.phrase_input_recipe, "")
        assertTrue(
            compose.onAllNodes(hasText(recipe, substring = true), useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty(),
        )
        compose.onNodeWithText(text(R.string.phrase_update_recipe_off)).assertIsDisplayed()

        compose.onNodeWithText(update).performScrollTo().performClick()
        compose.waitUntil(TIMEOUT_MS) { has(text(R.string.phrase_update_recipe_on)) }
        compose.onNodeWithText(text(R.string.phrase_register)).performClick()
        compose.waitUntil(TIMEOUT_MS) { registered.isNotEmpty() }
        assertTrue(registered.single().updatesRecipe)
    }

    private fun show(model: PhraseViewModel) {
        compose.setContent {
            PreviewTheme {
                val state by model.uiState.collectAsState()
                PhraseScreen(state, model::onAction)
            }
        }
    }

    private fun say(phrase: String) {
        compose.onNodeWithText(text(R.string.phrase_field_label)).performTextInput(phrase)
        compose.onNodeWithText(text(R.string.phrase_understand)).performClick()
        compose.waitForIdle()
    }

    /** Si algo en pantalla dice exactamente [value]. */
    private fun has(value: String): Boolean = compose
        .onAllNodes(hasText(value), useUnmergedTree = true)
        .fetchSemanticsNodes()
        .isNotEmpty()

    private fun viewModel(): PhraseViewModel = PhraseViewModel(
        read = { said ->
            val reading = PhraseInterpreter.read(said, LANGUAGE, STYLE, 0, CATALOG)
            PhraseReadResult(
                reading = reading,
                catalog = CATALOG,
                draft = reading.toDraft(),
                statesOperation = PhraseInterpreter.statesOperation(said, LANGUAGE),
            )
        },
        correct = { draft, said, catalog -> draft.correctedBy(said, LANGUAGE, STYLE, 0, catalog) },
        follow = { draft, said, result ->
            draft.followedBy(said, result.reading, LANGUAGE, STYLE, 0, result.catalog)
        },
        register = {
            registered += it
            PhraseOutcome.Registered(it.intent, documentId = 1L)
        },
        savedState = SavedStateHandle(),
        numbers = PhraseInputFormat(SYMBOLS, currencyDecimals = 0, SYMBOLS),
        computation = Dispatchers.Default,
    )

    private companion object {
        const val TIMEOUT_MS = 5_000L
        val LANGUAGE = PhraseLanguage.ES
        val STYLE = NumberStyle(decimal = ',', grouping = '.')
        val SYMBOLS = NumberSymbols(
            decimalSeparator = ',',
            minusSign = '-',
            grouping = DigitGrouping('.', 3, 3),
        )

        const val UNIT = 1L
        const val KILOGRAM = 2L
        const val GRAM = 3L
        const val PIE = 100L
        const val CAKE = 101L
        const val FLOUR = 102L
        const val EGG = 103L
        const val CASH = 400L

        fun product(
            id: Long,
            name: String,
            base: Long,
            recipe: List<PhraseComponent> = emptyList(),
        ) = PhraseProduct(
            id = id,
            name = name,
            baseUnitId = base,
            conversions = emptyList(),
            forSale = true,
            forPurchase = true,
            hasRecipe = recipe.isNotEmpty(),
            stockMode = ProductStockMode.TRACKED,
            recipe = recipe,
        )

        val CATALOG = PhraseCatalog(
            products = listOf(
                product(PIE, "Pastel", UNIT),
                // Una torta lleva 0,200 kg de harina y 2 huevos.
                product(
                    CAKE,
                    "Torta",
                    UNIT,
                    listOf(
                        PhraseComponent(FLOUR, QuantityMilli(200)),
                        PhraseComponent(EGG, QuantityMilli(2_000)),
                    ),
                ),
                product(FLOUR, "Harina", KILOGRAM),
                product(EGG, "Huevo", UNIT),
            ),
            units = listOf(
                PhraseUnit(UNIT, "unit", "Unidad", "und", UnitKind.COUNT, null),
                PhraseUnit(KILOGRAM, "kilogram", "Kilogramo", "kg", UnitKind.MASS, 1_000_000L),
                PhraseUnit(GRAM, "gram", "Gramo", "g", UnitKind.MASS, 1_000L),
            ),
            paymentMethods = listOf(PhraseName(CASH, "cash", "Efectivo")),
            suppliers = emptyList(),
            customers = emptyList(),
            expenseCategories = emptyList(),
        )
    }
}
