package io.github.yesidrobayo.shopledger.ui.phrase

import androidx.lifecycle.SavedStateHandle
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.catalog.UnitKind
import io.github.yesidrobayo.shopledger.domain.model.phrase.DraftGap
import io.github.yesidrobayo.shopledger.domain.model.phrase.DraftLine
import io.github.yesidrobayo.shopledger.domain.model.phrase.DraftTarget
import io.github.yesidrobayo.shopledger.domain.model.phrase.NumberStyle
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseCatalog
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseDraft
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseIntent
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseLanguage
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseName
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhrasePrices
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseProduct
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseUnit
import io.github.yesidrobayo.shopledger.domain.model.phrase.SaidConversion
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
 * Lo que no está en el catálogo, los miles dictados con espacios y las unidades de cada línea, como
 * lo dice una persona en su teléfono: con el intérprete y la corrección de verdad sobre un catálogo
 * inventado, en español con coma decimal. El registro solo anota la tarjeta que llegó.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PhraseNewProductCardTest {
    private val dispatcher = StandardTestDispatcher()
    private val registered = mutableListOf<PhraseDraft>()

    @Before
    fun useTestMain() = Dispatchers.setMain(dispatcher)

    @After
    fun resetTestMain() = Dispatchers.resetMain()

    // Productos nuevos.

    @Test
    fun `something new is produced with what it takes and created at register`() =
        runTest(dispatcher) {
            val model = viewModel()

            model.say("hice un pastel con 200g de harina")

            val card = model.card()
            assertEquals("Pastel", (card.lines.single().product as CardProduct.New).name)
            assertFalse((card.lines.single().product as CardProduct.New).needsCreating)
            assertEquals(emptySet<DraftGap>(), card.gaps)
            assertTrue(card.savesRecipe)
            model.onAction(PhraseAction.Register)
            advanceUntilIdle()
            val draft = registered.single()
            assertEquals(DraftTarget.New("Pastel"), draft.lines.single().product)
            assertEquals(
                listOf(DraftLine(DraftTarget.Existing(FLOUR), QuantityMilli(200_000), GRAM)),
                draft.inputs,
            )
        }

    @Test
    fun `what something new takes is said after it`() = runTest(dispatcher) {
        val model = viewModel()

        model.say("hice un pastel")
        assertTrue(DraftGap.INPUTS in model.card().gaps)
        model.say("lleva 200 g de harina")

        val stage = model.stage()
        assertNull(stage.notice)
        assertEquals(
            listOf(FLOUR to 200_000L),
            stage.card.inputs.map { (it.product as CardProduct.Chosen).id to it.quantity?.milli },
        )
        assertEquals(emptySet<DraftGap>(), stage.card.gaps)
    }

    @Test
    fun `a sale of something new asks its price and keeps choosing another`() =
        runTest(dispatcher) {
            val model = viewModel()

            model.say("vendí 2 arepas en efectivo")
            val product = model.card().lines.single().product as CardProduct.New
            assertEquals("Arepa", product.name)
            assertEquals(setOf(DraftGap.PRICE), model.card().gaps)

            model.say("a 3000")
            assertEquals(emptySet<DraftGap>(), model.card().gaps)
            // Se puede cambiar por uno del catálogo antes de registrar.
            model.onAction(PhraseAction.ChooseProduct(0, EGG))
            assertEquals(CardProduct.Chosen(EGG, "Huevo"), model.card().lines.single().product)
        }

    @Test
    fun `a line of something new can be named again and is created at register`() =
        runTest(dispatcher) {
            val model = viewModel()
            model.say("compré 3 velitas a 500")

            model.onAction(PhraseAction.CreateProduct(0, " Vela "))

            val line = model.card().lines.single()
            assertEquals("Vela", (line.product as CardProduct.New).name)
            assertEquals(listOf(UNIT, KILOGRAM, GRAM, POUND), line.units.map { it.id })
        }

    @Test
    fun `a sale of a product without a price asks for it`() = runTest(dispatcher) {
        // Los huevos no tienen precio, como un producto creado al producirlo.
        val prices = PhrasePrices(sale = mapOf(FLOUR to Money(3_000)))
        val model = viewModel(prices)

        model.say("vendí 2 huevos en efectivo")
        assertEquals(setOf(DraftGap.PRICE), model.card().gaps)
        assertTrue(model.card().lines.single().needsPrice)
        model.say("a 500")
        assertEquals(emptySet<DraftGap>(), model.card().gaps)
        assertFalse(model.card().lines.single().needsPrice)

        // Con precio, nada que pedir.
        val priced = viewModel(prices)
        priced.say("vendí 1 kilo de harina en efectivo")
        assertEquals(emptySet<DraftGap>(), priced.card().gaps)
        assertFalse(priced.card().lines.single().needsPrice)
    }

    // Miles dictados con espacios.

    @Test
    fun `thousands dictated with spaces are one amount`() = runTest(dispatcher) {
        for (text in listOf(
            "gasté 200 000 en el arriendo",
            "gasté 200 000 en el arriendo",
            "gasté 200 000 en el arriendo",
            "pagué el arriendo 200 000",
        )) {
            val model = viewModel()
            model.say(text)
            assertEquals(text, Money(200_000), model.card().total)
        }
    }

    // Unidades.

    @Test
    fun `what was said in a unit the product does not take asks the unit to use`() =
        runTest(dispatcher) {
            val model = viewModel()
            model.say("vendí 2 kilos de huevo")

            val line = model.card().lines.single()
            assertNull(line.quantity)
            val unfit = requireNotNull(line.unfit)
            assertEquals(QuantityMilli(2_000), unfit.quantity)
            assertEquals(KILOGRAM, unfit.unit.id)
            assertNull(unfit.measure)
            assertEquals(listOf(UNIT), line.units.map { it.id })

            model.onAction(PhraseAction.ChooseLineUnit(0, input = false, unitId = UNIT))
            val sheet = model.uiState.value.sheet as PhraseSheet.Line
            assertEquals(UNIT, sheet.unit?.id)
            assertEquals("", sheet.quantityText)
            model.onAction(PhraseAction.LineQuantityChange("24"))
            model.onAction(PhraseAction.SaveLine)

            val fixed = model.card().lines.single()
            assertEquals(QuantityMilli(24_000), fixed.quantity)
            assertNull(fixed.unfit)
        }

    @Test
    fun `the unit of a line is changed in its sheet`() = runTest(dispatcher) {
        val model = viewModel()
        model.say("vendí 1 kilo de harina")

        model.onAction(PhraseAction.OpenLine(0))
        val sheet = model.uiState.value.sheet as PhraseSheet.Line
        assertEquals(listOf(KILOGRAM, GRAM), sheet.units.map { it.id })
        model.onAction(PhraseAction.LineUnitChange(GRAM))
        model.onAction(PhraseAction.LineQuantityChange("500"))
        model.onAction(PhraseAction.SaveLine)

        val line = model.card().lines.single()
        assertEquals(GRAM, line.unit?.id)
        assertEquals(QuantityMilli(500_000), line.quantity)
        // La base vuelve a ser "sin unidad dicha".
        model.onAction(PhraseAction.OpenLine(0))
        model.onAction(PhraseAction.LineUnitChange(KILOGRAM))
        model.onAction(PhraseAction.LineUnitChange(999L))
        model.onAction(PhraseAction.SaveLine)
        assertEquals(KILOGRAM, model.card().lines.single().unit?.id)
    }

    @Test
    fun `a pound with no fixed measure asks how much it weighs and converts at once`() =
        runTest(dispatcher) {
            val model = viewModel(PhrasePrices(sale = mapOf(SUGAR to Money(2_000))))
            model.say("vendí 200 g de azúcar en efectivo")

            val unfit = requireNotNull(model.card().lines.single().unfit)
            val measure = requireNotNull(unfit.measure)
            assertEquals(POUND, measure.unit.id)
            assertEquals(GRAM, measure.measureUnit.id)
            assertEquals(
                listOf(QuantityMilli(500_000), QuantityMilli(453_590)),
                measure.options,
            )

            model.onAction(
                PhraseAction.ChooseMeasure(0, input = false, amount = measure.options[0]),
            )

            val line = model.card().lines.single()
            // 200 g con la libra de 500 g son 0,400 libras.
            assertEquals(QuantityMilli(400), line.quantity)
            assertEquals(POUND, line.unit?.id)
            assertNull(line.unfit)
            model.onAction(PhraseAction.Register)
            advanceUntilIdle()
            assertEquals(
                listOf(SaidConversion(SUGAR, KILOGRAM, 2_000L)),
                registered.single().conversions,
            )
        }

    @Test
    fun `another measure is typed in its sheet`() = runTest(dispatcher) {
        val model = viewModel()
        model.say("vendí 200 g de azúcar en efectivo")

        model.onAction(PhraseAction.OpenMeasure(0, input = false))
        assertTrue(model.uiState.value.sheet is PhraseSheet.Measure)
        model.onAction(PhraseAction.MeasureChange("abc"))
        model.onAction(PhraseAction.SaveMeasure)
        assertTrue((model.uiState.value.sheet as PhraseSheet.Measure).error)
        model.onAction(PhraseAction.MeasureChange("453,59"))
        model.onAction(PhraseAction.SaveMeasure)

        assertNull(model.uiState.value.sheet)
        assertEquals(QuantityMilli(441), model.card().lines.single().quantity)
        // Una medida que no convierte nada se marca y la línea sigue igual.
        val other = viewModel()
        other.say("vendí 200 g de azúcar en efectivo")
        other.onAction(
            PhraseAction.ChooseMeasure(0, input = false, amount = QuantityMilli(999_999_999)),
        )
        assertTrue((other.uiState.value.sheet as PhraseSheet.Measure).error)
        assertNull(other.card().lines.single().quantity)
    }

    private suspend fun PhraseViewModel.say(text: String) {
        onAction(PhraseAction.TextChange(text))
        onAction(PhraseAction.Understand)
        dispatcher.scheduler.advanceUntilIdle()
    }

    private fun PhraseViewModel.stage(): PhraseStage.Card =
        requireNotNull(uiState.value.stage as? PhraseStage.Card) { "Sin tarjeta" }

    private fun PhraseViewModel.card(): PhraseCard = stage().card

    private fun viewModel(prices: PhrasePrices = PhrasePrices()): PhraseViewModel = PhraseViewModel(
        read = { text ->
            val reading = PhraseInterpreter.read(text, LANGUAGE, STYLE, DECIMALS, CATALOG)
            PhraseReadResult(
                reading = reading,
                catalog = CATALOG,
                draft = reading.toDraft(),
                prices = prices,
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
        const val POUND = 4L
        const val FLOUR = 102L
        const val EGG = 103L
        const val SUGAR = 104L
        const val CASH = 400L
        const val RENT = 500L

        fun product(id: Long, name: String, base: Long) = PhraseProduct(
            id = id,
            name = name,
            baseUnitId = base,
            conversions = emptyList(),
            forSale = true,
            forPurchase = true,
            hasRecipe = false,
            stockMode = ProductStockMode.TRACKED,
        )

        val CATALOG = PhraseCatalog(
            products = listOf(
                product(FLOUR, "Harina", KILOGRAM),
                product(EGG, "Huevo", UNIT),
                product(SUGAR, "Azúcar", POUND),
            ),
            units = listOf(
                PhraseUnit(UNIT, "unit", "Unidad", "und", UnitKind.COUNT, null),
                PhraseUnit(KILOGRAM, "kilogram", "Kilogramo", "kg", UnitKind.MASS, 1_000_000L),
                PhraseUnit(GRAM, "gram", "Gramo", "g", UnitKind.MASS, 1_000L),
                PhraseUnit(POUND, "pound", "Libra", "lb", UnitKind.MASS, null),
            ),
            paymentMethods = listOf(PhraseName(CASH, "cash", "Efectivo")),
            suppliers = emptyList(),
            customers = emptyList(),
            expenseCategories = listOf(PhraseName(RENT, "rent", "Arriendo")),
        )
    }
}
