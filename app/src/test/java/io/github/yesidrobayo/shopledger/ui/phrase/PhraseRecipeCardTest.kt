package io.github.yesidrobayo.shopledger.ui.phrase

import androidx.lifecycle.SavedStateHandle
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.catalog.UnitKind
import io.github.yesidrobayo.shopledger.domain.model.phrase.DraftGap
import io.github.yesidrobayo.shopledger.domain.model.phrase.DraftLine
import io.github.yesidrobayo.shopledger.domain.model.phrase.DraftTarget
import io.github.yesidrobayo.shopledger.domain.model.phrase.NumberStyle
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseCatalog
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseComponent
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseDraft
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseIntent
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseIssue
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Producir por frase con la tarjeta: lo que lleva un producto sin receta, la diferencia con la
 * receta y el interruptor `Actualizar la receta`. Se dicta como lo hace una persona, con el
 * intérprete y la corrección de verdad sobre un catálogo inventado; el registro solo anota la
 * tarjeta que llegó.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PhraseRecipeCardTest {
    private val dispatcher = StandardTestDispatcher()
    private val registered = mutableListOf<PhraseDraft>()

    @Before
    fun useTestMain() = Dispatchers.setMain(dispatcher)

    @After
    fun resetTestMain() = Dispatchers.resetMain()

    @Test
    fun `a product without a recipe asks what it takes and registers it with the batch`() =
        runTest(dispatcher) {
            val model = viewModel()

            model.say("hice un pastel")
            assertTrue(DraftGap.INPUTS in model.card().gaps)
            // La tarjeta ya lo pide en su lugar: no se repite como aviso.
            assertFalse(PhraseIssue.NO_RECIPE in model.card().notices)
            model.onAction(PhraseAction.Register)
            advanceUntilIdle()
            assertEquals(PhraseFailure.Incomplete, model.stage().failure)

            model.say("lleva 200 g de harina y 3 huevos")
            val card = model.card()
            assertEquals(listOf("Harina" to 200_000L, "Huevo" to 3_000L), model.inputs())
            assertEquals(emptySet<DraftGap>(), card.gaps)
            assertTrue(card.savesRecipe)
            assertNull(card.updatesRecipe)
            assertEquals(listOf<CardRecipe?>(null, null), card.inputs.map { it.recipe })

            model.onAction(PhraseAction.Register)
            advanceUntilIdle()
            val draft = registered.single()
            assertEquals(PhraseIntent.PRODUCTION, draft.intent)
            assertEquals(
                listOf(
                    DraftLine(DraftTarget.Existing(FLOUR), QuantityMilli(200_000), GRAM),
                    DraftLine(DraftTarget.Existing(EGG), QuantityMilli(3_000), null),
                ),
                draft.inputs,
            )
            assertEquals(PhraseStage.Empty, model.uiState.value.stage)
        }

    @Test
    fun `what was said shows how it differs from the recipe and the switch starts off`() =
        runTest(dispatcher) {
            val model = viewModel()

            // Dos tortas llevan 0,400 kg de harina por receta; se dijo 500 g.
            model.say("hice 2 tortas con 500 g de harina")
            val card = model.card()
            assertEquals(false, card.updatesRecipe)
            assertFalse(card.savesRecipe)
            val recipe = card.inputs.single().recipe as CardRecipe.InRecipe
            assertEquals(QuantityMilli(400_000), recipe.quantity)
            assertEquals(GRAM, recipe.unit?.id)
            assertFalse(recipe.same)

            model.say("y 100 g de mantequilla")
            assertEquals(CardRecipe.NotInRecipe, model.card().inputs.last().recipe)
            model.onAction(PhraseAction.UpdateRecipe(true))
            assertEquals(true, model.card().updatesRecipe)

            model.onAction(PhraseAction.Register)
            advanceUntilIdle()
            assertTrue(registered.single().updatesRecipe)
        }

    @Test
    fun `an input is corrected by hand and leaves the card when removed`() = runTest(dispatcher) {
        val model = viewModel()
        model.say("hice un pastel")
        model.say("lleva 200 g de harina y 3 huevos")

        model.onAction(PhraseAction.OpenInput(1))
        val sheet = model.uiState.value.sheet as PhraseSheet.Line
        assertTrue(sheet.input)
        assertFalse(sheet.showsPrice)
        model.onAction(PhraseAction.LineQuantityChange("4"))
        model.onAction(PhraseAction.SaveLine)
        assertEquals(listOf("Harina" to 200_000L, "Huevo" to 4_000L), model.inputs())

        model.onAction(PhraseAction.OpenInput(0))
        model.onAction(PhraseAction.RemoveLine)
        assertEquals(listOf("Huevo" to 4_000L), model.inputs())
        assertEquals(
            listOf("Pastel" to 1_000L),
            model.card().lines.map { line ->
                (line.product as CardProduct.Chosen).name to line.quantity?.milli
            },
        )
    }

    private suspend fun PhraseViewModel.say(text: String) {
        onAction(PhraseAction.TextChange(text))
        onAction(PhraseAction.Understand)
        dispatcher.scheduler.advanceUntilIdle()
    }

    private fun PhraseViewModel.stage(): PhraseStage.Card =
        requireNotNull(uiState.value.stage as? PhraseStage.Card) { "Sin tarjeta" }

    private fun PhraseViewModel.card(): PhraseCard = stage().card

    private fun PhraseViewModel.inputs(): List<Pair<String, Long?>> = card().inputs.map { line ->
        val product = requireNotNull(line.product as? CardProduct.Chosen) { "Insumo sin elegir" }
        product.name to line.quantity?.milli
    }

    private fun viewModel(): PhraseViewModel = PhraseViewModel(
        read = { text ->
            val reading = PhraseInterpreter.read(text, LANGUAGE, STYLE, DECIMALS, CATALOG)
            PhraseReadResult(
                reading = reading,
                catalog = CATALOG,
                draft = reading.toDraft(),
                statesOperation = PhraseInterpreter.statesOperation(text, LANGUAGE),
            )
        },
        correct = { draft, text, catalog ->
            draft.correctedBy(text, LANGUAGE, STYLE, DECIMALS, catalog)
        },
        follow = { draft, text, result ->
            draft.followedBy(text, result.reading, LANGUAGE, STYLE, DECIMALS, result.catalog)
        },
        register = { draft ->
            registered += draft
            PhraseOutcome.Registered(draft.intent, documentId = 1L)
        },
        savedState = SavedStateHandle(),
        numbers = PhraseInputFormat(SYMBOLS, currencyDecimals = DECIMALS, SYMBOLS),
        computation = dispatcher,
    )

    private companion object {
        val LANGUAGE = PhraseLanguage.ES
        val STYLE = NumberStyle(decimal = ',', grouping = '.')
        const val DECIMALS = 0
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
        const val BUTTER = 104L
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
                product(BUTTER, "Mantequilla", KILOGRAM),
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
