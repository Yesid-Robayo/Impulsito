package io.github.yesidrobayo.shopledger.ui.phrase

import androidx.lifecycle.SavedStateHandle
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.catalog.UnitKind
import io.github.yesidrobayo.shopledger.domain.model.phrase.DraftTarget
import io.github.yesidrobayo.shopledger.domain.model.phrase.NumberStyle
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseCatalog
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseDraft
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseIntent
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseLanguage
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseName
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseProduct
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseUnit
import io.github.yesidrobayo.shopledger.domain.model.region.DigitGrouping
import io.github.yesidrobayo.shopledger.domain.model.region.NumberSymbols
import io.github.yesidrobayo.shopledger.domain.model.stock.StockAdjustmentReason
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
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Con la tarjeta abierta, lo dicho suma o corrige y nunca la borra en silencio. Cada prueba dicta
 * como lo hace una persona, de a poco, con el intérprete y la corrección de verdad sobre un catálogo
 * inventado; solo el registro falta, porque aquí nada se registra.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PhraseCardFollowUpTest {
    private val dispatcher = StandardTestDispatcher()

    /** Lo que llegó a registrarse, tal como salió de la tarjeta. */
    private val registered = mutableListOf<PhraseDraft>()

    @Before
    fun useTestMain() = Dispatchers.setMain(dispatcher)

    @After
    fun resetTestMain() = Dispatchers.resetMain()

    @Test
    fun `a count said little by little keeps every product`() = runTest(dispatcher) {
        val model = viewModel()

        model.say("tengo 1 kilo de queso y 1 kilo de azúcar")
        model.say("tengo un kilo de harina")

        assertEquals(PhraseIntent.STOCK_ADJUSTMENT, model.card().intent)
        assertEquals(StockAdjustmentReason.COUNT_CORRECTION, model.card().reason)
        assertEquals(
            listOf("Queso" to 1_000L, "Azúcar" to 1_000L, "Harina" to 1_000L),
            model.lines(),
        )
        assertEquals("", model.uiState.value.text)
    }

    @Test
    fun `a count said little by little registers every product at once`() = runTest(dispatcher) {
        val model = viewModel()

        model.say("tengo 1 kilo de queso y 1 kilo de azúcar")
        model.say("tengo un kilo de harina")
        model.onAction(PhraseAction.Register)
        advanceUntilIdle()

        val count = registered.single()
        assertEquals(StockAdjustmentReason.COUNT_CORRECTION, count.reason)
        assertEquals(
            listOf(CHEESE to 1_000L, SUGAR to 1_000L, FLOUR to 1_000L),
            count.lines.map {
                (it.product as DraftTarget.Existing).productId to it.quantity?.milli
            },
        )
        assertEquals(
            PhraseRegistered(PhraseIntent.STOCK_ADJUSTMENT, 1L),
            model.uiState.value.registered,
        )
        assertEquals(PhraseStage.Empty, model.uiState.value.stage)
    }

    @Test
    fun `the type of a product that exists still registers the count`() = runTest(dispatcher) {
        val model = viewModel()

        model.say("la harina, tengo 1 kg a 50000")
        model.say("la harina es un insumo con categoría materia prima")
        model.onAction(PhraseAction.Register)
        advanceUntilIdle()

        val count = registered.single()
        assertEquals(PhraseIntent.STOCK_ADJUSTMENT, count.intent)
        assertEquals(
            listOf(FLOUR to 1_000L),
            count.lines.map {
                (it.product as DraftTarget.Existing).productId to it.quantity?.milli
            },
        )
        assertEquals(PhraseStage.Empty, model.uiState.value.stage)
    }

    @Test
    fun `a product without a verb is one more line of the count`() = runTest(dispatcher) {
        val model = viewModel()

        model.say("tengo 1 kilo de queso")
        model.say("un kilo de harina")

        assertEquals(listOf("Queso" to 1_000L, "Harina" to 1_000L), model.lines())
        assertEquals(StockAdjustmentReason.COUNT_CORRECTION, model.card().reason)
    }

    @Test
    fun `the type of a product that exists keeps the card and says where it changes`() =
        runTest(dispatcher) {
            val model = viewModel()

            model.say("la harina tengo 1 kg a 50000")
            val before = model.card()
            model.say("la harina es un insumo con categoría materia prima")

            assertEquals(before, model.card())
            assertEquals(listOf("Harina" to 1_000L), model.lines())
            assertEquals(CardNotice.KnownProduct(FLOUR, "Harina"), model.stage().notice)
            assertNull(model.stage().failure)
        }

    @Test
    fun `the type of a product said with a comma keeps the card too`() = runTest(dispatcher) {
        val model = viewModel()

        model.say("la harina, tengo 1 kg a 50000")
        model.say("la harina es un insumo con categoría materia prima")

        assertEquals(listOf("Harina" to 1_000L), model.lines())
        assertEquals(CardNotice.KnownProduct(FLOUR, "Harina"), model.stage().notice)
    }

    @Test
    fun `a purchase takes a line said without a verb with its cost`() = runTest(dispatcher) {
        val model = viewModel()

        model.say("compré 2 kilos de harina")
        model.say("y 1 de azúcar a 3 mil")

        assertEquals(PhraseIntent.PURCHASE, model.card().intent)
        assertEquals(listOf("Harina" to 2_000L, "Azúcar" to 1_000L), model.lines())
        assertEquals(Money(3_000L), model.card().lines[1].unitPrice)
    }

    @Test
    fun `another sale of the same bread is summed`() = runTest(dispatcher) {
        val model = viewModel()

        model.say("vendí 2 panes")
        model.say("y otros 2 panes en efectivo")

        assertEquals(listOf("Pan" to 4_000L), model.lines())
        assertEquals(CASH, model.card().payment?.selectedId)
    }

    @Test
    fun `another operation asks and keeping the card discards the phrase`() = runTest(dispatcher) {
        val model = viewModel()

        model.say("compré 2 kilos de harina")
        model.say("vendí 2 panes")

        assertEquals(CardNotice.OtherOperation(PhraseIntent.SALE, null), model.stage().notice)
        assertEquals(PhraseIntent.PURCHASE, model.card().intent)
        assertEquals(listOf("Harina" to 2_000L), model.lines())

        model.onAction(PhraseAction.KeepCard)

        assertNull(model.stage().notice)
        assertEquals(PhraseIntent.PURCHASE, model.card().intent)
        assertEquals(listOf("Harina" to 2_000L), model.lines())
        // La frase descartada ya no espera: empezar otra no hace nada.
        model.onAction(PhraseAction.StartNewCard)
        assertEquals(PhraseIntent.PURCHASE, model.card().intent)
    }

    @Test
    fun `another operation asks and starting another card reads the new phrase`() =
        runTest(dispatcher) {
            val model = viewModel()

            model.say("compré 2 kilos de harina")
            model.say("vendí 2 panes")
            model.onAction(PhraseAction.StartNewCard)

            assertNull(model.stage().notice)
            assertEquals(PhraseIntent.SALE, model.card().intent)
            assertEquals(listOf("Pan" to 2_000L), model.lines())
        }

    @Test
    fun `what is not understood keeps the card and the text`() = runTest(dispatcher) {
        val model = viewModel()

        model.say("vendí 2 panes")
        model.say("hola")

        assertEquals(PhraseFailure.NotUnderstood, model.stage().failure)
        assertEquals(listOf("Pan" to 2_000L), model.lines())
        assertEquals("hola", model.uiState.value.text)
    }

    @Test
    fun `a list without a verb opens a sale that changes operation by hand`() =
        runTest(dispatcher) {
            val model = viewModel()

            model.say("2 kilos de queso")

            assertEquals(PhraseIntent.SALE, model.card().intent)
            assertEquals(
                listOf(
                    CardOperation(PhraseIntent.SALE),
                    CardOperation(PhraseIntent.PURCHASE),
                    CardOperation(
                        PhraseIntent.STOCK_ADJUSTMENT,
                        StockAdjustmentReason.COUNT_CORRECTION,
                    ),
                    CardOperation(PhraseIntent.PRODUCTION),
                ),
                model.card().operations,
            )

            model.onAction(
                PhraseAction.ChooseOperation(
                    CardOperation(
                        PhraseIntent.STOCK_ADJUSTMENT,
                        StockAdjustmentReason.COUNT_CORRECTION,
                    ),
                ),
            )
            advanceUntilIdle()

            assertEquals(PhraseIntent.STOCK_ADJUSTMENT, model.card().intent)
            assertEquals(StockAdjustmentReason.COUNT_CORRECTION, model.card().reason)
            assertEquals(listOf("Queso" to 2_000L), model.lines())
            assertNull(model.card().payment)
        }

    @Test
    fun `a damaged adjustment offers its own reason among the operations`() = runTest(dispatcher) {
        val model = viewModel()

        model.say("se dañaron 2 panes")

        assertEquals(
            CardOperation(PhraseIntent.STOCK_ADJUSTMENT, StockAdjustmentReason.DAMAGED),
            model.card().operations.last(),
        )
    }

    @Test
    fun `an expense card offers no operations to choose`() = runTest(dispatcher) {
        val model = viewModel()

        model.say("pagué la luz 80000")

        assertEquals(emptyList<CardOperation>(), model.card().operations)
    }

    private suspend fun PhraseViewModel.say(text: String) {
        onAction(PhraseAction.TextChange(text))
        onAction(PhraseAction.Understand)
        dispatcher.scheduler.advanceUntilIdle()
    }

    private fun PhraseViewModel.stage(): PhraseStage.Card =
        requireNotNull(uiState.value.stage as? PhraseStage.Card) { "Sin tarjeta" }

    private fun PhraseViewModel.card(): PhraseCard = stage().card

    private fun PhraseViewModel.lines(): List<Pair<String, Long?>> = card().lines.map { line ->
        val product = requireNotNull(line.product as? CardProduct.Chosen) { "Producto sin elegir" }
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
        const val CHEESE = 100L
        const val SUGAR = 101L
        const val FLOUR = 102L
        const val BREAD = 103L
        const val CASH = 400L
        const val UTILITIES = 500L
        const val RAW_MATERIAL = 600L

        fun product(id: Long, name: String, base: Long, sale: Boolean = true) = PhraseProduct(
            id = id,
            name = name,
            baseUnitId = base,
            conversions = emptyList(),
            forSale = sale,
            forPurchase = true,
            hasRecipe = false,
            stockMode = ProductStockMode.TRACKED,
        )

        val CATALOG = PhraseCatalog(
            products = listOf(
                product(CHEESE, "Queso", KILOGRAM),
                product(SUGAR, "Azúcar", KILOGRAM),
                product(FLOUR, "Harina", KILOGRAM, sale = false),
                product(BREAD, "Pan", UNIT),
            ),
            units = listOf(
                PhraseUnit(UNIT, "unit", "Unidad", "und", UnitKind.COUNT, null),
                PhraseUnit(KILOGRAM, "kilogram", "Kilogramo", "kg", UnitKind.MASS, 1_000_000L),
            ),
            paymentMethods = listOf(PhraseName(CASH, "cash", "Efectivo")),
            suppliers = emptyList(),
            customers = emptyList(),
            expenseCategories = listOf(PhraseName(UTILITIES, "utilities", "Servicios")),
            productCategories = listOf(PhraseName(RAW_MATERIAL, null, "Materia prima")),
        )
    }
}
