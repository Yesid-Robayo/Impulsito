package io.github.yesidrobayo.shopledger.ui.production

import androidx.lifecycle.SavedStateHandle
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.catalog.RecipeComponent
import io.github.yesidrobayo.shopledger.domain.model.catalog.UnitKind
import io.github.yesidrobayo.shopledger.domain.model.region.NumberSymbols
import io.github.yesidrobayo.shopledger.domain.model.stock.LotChoice
import io.github.yesidrobayo.shopledger.domain.model.stock.LotChoices
import io.github.yesidrobayo.shopledger.domain.model.stock.LotStock
import io.github.yesidrobayo.shopledger.domain.model.stock.StockLot
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.rule.production.ProductionInput
import io.github.yesidrobayo.shopledger.domain.usecase.production.ProductionCatalog
import io.github.yesidrobayo.shopledger.domain.usecase.production.ProductionLot
import io.github.yesidrobayo.shopledger.domain.usecase.production.ProductionLots
import io.github.yesidrobayo.shopledger.domain.usecase.production.ProductionRequest
import io.github.yesidrobayo.shopledger.domain.usecase.production.RegisterProductionResult
import io.github.yesidrobayo.shopledger.ui.product.COUNT_UNIT
import io.github.yesidrobayo.shopledger.ui.product.product
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.DayOfWeek
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.time.Instant

/**
 * El formulario de producción sin base, con el ejemplo de las velas: 0,200 kg de cera y una mecha
 * por vela, con 5 kg de cera de `V = 40000` y 100 mechas de `V = 15000`. Nombres inventados.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProductionFormViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val kilogram = MeasureUnit(2L, "KILOGRAM", null, null, UnitKind.MASS, true, null)
    private val candle = product(1L, "Vela inventada")
    private val wax =
        product(2L, "Cera inventada", averageCost = 8_000L, stockValue = 40_000L, onHand = 5_000L)
            .let { it.copy(details = it.details.copy(baseUnitId = kilogram.id)) }
    private val wick =
        product(3L, "Mecha inventada", averageCost = 150L, stockValue = 15_000L, onHand = 100_000L)
    private val service =
        product(4L, "Etiqueta inventada", stockMode = ProductStockMode.UNTRACKED)
    private val catalog = ProductionCatalog(
        products = listOf(candle, wax, wick, service).associateBy { it.id },
        recipes = mapOf(
            candle.id to listOf(
                RecipeComponent(1L, candle.id, wax.id, QuantityMilli(200)),
                RecipeComponent(2L, candle.id, wick.id, QuantityMilli(1_000)),
            ),
        ),
        units = mapOf(COUNT_UNIT.id to COUNT_UNIT, kilogram.id to kilogram),
    )
    private val requests = mutableListOf<ProductionRequest>()
    private var result: RegisterProductionResult =
        RegisterProductionResult.Registered(1L, Money(17_500L))
    private val savedState = SavedStateHandle()
    private val symbols = NumberSymbols(decimalSeparator = ',', minusSign = '-', grouping = null)

    @Before
    fun useTestMain() = Dispatchers.setMain(dispatcher)

    @After
    fun resetTestMain() = Dispatchers.resetMain()

    private fun TestScope.viewModel(
        initial: Long? = null,
        state: SavedStateHandle = savedState,
        source: () -> Flow<ProductionCatalog> = { flowOf(catalog) },
        lots: suspend (Long?, Set<Long>) -> ProductionLots = { _, _ -> ProductionLots() },
    ): ProductionFormViewModel {
        val model = ProductionFormViewModel(
            catalogSource = source,
            register = {
                requests += it
                result
            },
            savedState = state,
            quantitySymbols = symbols,
            initialProductId = initial,
            computation = dispatcher,
            lotsSource = lots,
        )
        backgroundScope.launch { model.uiState.collect {} }
        advanceUntilIdle()
        return model
    }

    private fun ProductionFormViewModel.act(scope: TestScope, action: ProductionFormAction) {
        onAction(action)
        scope.advanceUntilIdle()
    }

    private val ProductionFormViewModel.state: ProductionFormUiState get() = uiState.value

    @Test
    fun `ten candles show the inputs of the recipe and the cost of the rule`() =
        runTest(dispatcher) {
            val model = viewModel(initial = candle.id)

            model.act(this, ProductionFormAction.QuantityChange("10"))

            val state = model.state
            assertEquals("Vela inventada", state.product?.name)
            assertEquals(listOf("2", "10"), state.inputs.map { it.quantityText })
            assertEquals(listOf(null, null), state.inputs.map { it.leftAfter })
            val figures = requireNotNull(state.figures)
            assertEquals(Money(17_500L), figures.totalCost)
            assertEquals(Money(1_750L), figures.unitCost)
            assertEquals(Money(1_750L), figures.averageAfter)
            assertFalse(figures.hasUncosted)
        }

    @Test
    fun `an input that does not reach says how much would be left, and still registers`() =
        runTest(dispatcher) {
            val model = viewModel(initial = candle.id)
            model.act(this, ProductionFormAction.QuantityChange("30"))

            // 30 velas piden 6 kg de cera: quedarían 1 kg menos de lo que hay.
            assertEquals(QuantityMilli(-1_000), model.state.inputs.first().leftAfter)
            model.act(this, ProductionFormAction.Register)

            assertEquals(
                ProductionRequest(
                    candle.id,
                    QuantityMilli(30_000),
                    listOf(
                        ProductionInput(wax.id, QuantityMilli(6_000)),
                        ProductionInput(wick.id, QuantityMilli(30_000)),
                    ),
                    note = "",
                ),
                requests.single(),
            )
            assertTrue(model.state.registered)
        }

    @Test
    fun `an edited input counts for this batch, and another quantity goes back to the recipe`() =
        runTest(dispatcher) {
            val model = viewModel(initial = candle.id)
            model.act(this, ProductionFormAction.QuantityChange("10"))

            model.act(this, ProductionFormAction.InputChange(wax.id, "2,5"))
            assertEquals("2,5", model.state.inputs.first().quantityText)
            // 2,5 kg salen por 20000: el total pasa a 21500.
            assertEquals(Money(21_500L), model.state.figures?.totalCost)

            model.act(this, ProductionFormAction.QuantityChange("5"))
            assertEquals(listOf("1", "5"), model.state.inputs.map { it.quantityText })
        }

    @Test
    fun `a fraction of a counting unit is marked on the quantity`() = runTest(dispatcher) {
        val model = viewModel(initial = candle.id)

        model.act(this, ProductionFormAction.QuantityChange("1,5"))

        assertEquals(ProductionFieldError.FractionNotAllowed, model.state.quantityError)
        assertTrue(model.state.inputs.isEmpty())
    }

    @Test
    fun `registering without a product opens the picker, and without a quantity marks it`() =
        runTest(dispatcher) {
            val model = viewModel()

            model.act(this, ProductionFormAction.Register)
            assertEquals(
                listOf(ProductionOption(candle.id, "Vela inventada")),
                model.state.picker?.options,
            )

            model.act(this, ProductionFormAction.ChooseProduct(candle.id))
            model.act(this, ProductionFormAction.Register)

            assertNull(model.state.picker)
            assertEquals(ProductionFieldError.Missing, model.state.quantityError)
            assertTrue(requests.isEmpty())
        }

    @Test
    fun `a failure keeps what was written and offers to retry`() = runTest(dispatcher) {
        result = RegisterProductionResult.StorageFailed(StorageFailure.DISK_FULL)
        val model = viewModel(initial = candle.id)
        model.act(this, ProductionFormAction.QuantityChange("10"))
        model.act(this, ProductionFormAction.NoteChange("tanda"))

        model.act(this, ProductionFormAction.Register)

        assertEquals(ProductionNotice.RegisterFailed(outOfSpace = true), model.state.notice)
        assertEquals("10", model.state.quantityText)
        assertEquals("tanda", model.state.noteText)
        result = RegisterProductionResult.Registered(1L, Money(17_500L))
        model.act(this, ProductionFormAction.Retry)
        assertTrue(model.state.registered)
    }

    @Test
    fun `a stock over its limit names the product`() = runTest(dispatcher) {
        result = RegisterProductionResult.AboveLimit(Limit.ON_HAND_MILLI)
        val model = viewModel(initial = candle.id)
        model.act(this, ProductionFormAction.QuantityChange("10"))

        model.act(this, ProductionFormAction.Register)

        assertEquals(ProductionNotice.StockCap("Vela inventada"), model.state.notice)
    }

    @Test
    fun `without recipes the form offers to create one`() = runTest(dispatcher) {
        val empty = catalog.copy(recipes = emptyMap())

        val model = viewModel(source = { flowOf(empty) })

        assertTrue(model.state.noRecipes)
    }

    @Test
    fun `what was written survives the process, and leaving with changes asks first`() =
        runTest(dispatcher) {
            val first = viewModel(initial = candle.id)
            first.act(this, ProductionFormAction.QuantityChange("10"))
            first.act(this, ProductionFormAction.InputChange(wax.id, "3"))

            val again = viewModel(state = savedState)

            assertEquals("10", again.state.quantityText)
            assertEquals("3", again.state.inputs.first().quantityText)
            again.act(this, ProductionFormAction.Close)
            assertTrue(again.state.discardAsked)
            again.act(this, ProductionFormAction.ConfirmLeave)
            assertTrue(again.closed.value)
        }

    @Test
    fun `without lots nothing new shows and no lot is read`() = runTest(dispatcher) {
        var reads = 0
        val model = viewModel(
            initial = candle.id,
            lots = { _, _ -> ProductionLots().also { reads++ } },
        )

        model.act(this, ProductionFormAction.QuantityChange("10"))

        assertNull(model.state.outputLot)
        assertEquals(listOf(null, null), model.state.inputs.map { it.lots })
        assertEquals(0, reads)
    }

    @Test
    fun `an input with lots offers its lots and the product with lots asks for its lot`() =
        runTest(dispatcher) {
            val date = requireNotNull(BusinessDate.parse("2026-11-20"))
            val cheese = product(
                10L,
                "Queso inventado",
                averageCost = 1_000L,
                stockValue = 5_000L,
                onHand = 5_000L,
            ).let { it.copy(details = it.details.copy(tracksLots = true)) }
            val bread = product(11L, "Pan inventado")
                .let { it.copy(details = it.details.copy(tracksLots = true)) }
            val bakery = ProductionCatalog(
                products = listOf(cheese, bread).associateBy { it.id },
                recipes = mapOf(
                    bread.id to
                        listOf(RecipeComponent(3L, bread.id, cheese.id, QuantityMilli(500))),
                ),
                units = mapOf(COUNT_UNIT.id to COUNT_UNIT),
            )
            fun lot(id: Long, code: String, milli: Long) =
                LotChoice(LotStock(id, code, date, false, QuantityMilli(milli)), null)
            val stored = StockLot(
                id = 201L,
                productId = bread.id,
                code = "P-1",
                isUnassigned = false,
                expiryDate = date,
                purchaseLineId = null,
                expiryNotifiedAt = null,
                createdAt = Instant.fromEpochMilliseconds(0L),
                closedAt = null,
            )
            val read = ProductionLots(
                inputs = mapOf(
                    cheese.id to
                        LotChoices(listOf(lot(101L, "A", 1_000L), lot(102L, "B", 4_000L)), null),
                ),
                outputLots = listOf(stored),
                today = date,
                weekStartDay = DayOfWeek.MONDAY,
            )
            val asked = mutableListOf<Pair<Long?, Set<Long>>>()
            val model = viewModel(
                initial = bread.id,
                source = { flowOf(bakery) },
                lots = { output, inputs -> read.also { asked += output to inputs } },
            )

            model.act(this, ProductionFormAction.QuantityChange("4"))
            model.act(this, ProductionFormAction.ChooseInputLot(cheese.id, 102L))
            model.act(this, ProductionFormAction.LotCodeChange("P-1"))

            assertEquals(listOf<Pair<Long?, Set<Long>>>(bread.id to setOf(cheese.id)), asked)
            val input = model.state.inputs.single()
            assertEquals(102L, input.chosenLotId)
            assertEquals(2, input.lots?.lots?.size)
            assertTrue(requireNotNull(model.state.outputLot).existing)
            assertEquals(date, model.state.outputLot?.existingExpiry)

            model.act(this, ProductionFormAction.LotCodeChange("P-2"))
            model.act(this, ProductionFormAction.LotExpiryChange(date))
            assertFalse(requireNotNull(model.state.outputLot).existing)
            model.act(this, ProductionFormAction.Register)

            val request = requests.single()
            assertEquals(ProductionLot("P-2", date), request.lot)
            val expected = ProductionInput(cheese.id, QuantityMilli(2_000), 102L)
            assertEquals(listOf(expected), request.inputs)
        }

    @Test
    fun `a lot code over its limit is marked and nothing is registered`() = runTest(dispatcher) {
        val bread = product(11L, "Pan inventado")
            .let { it.copy(details = it.details.copy(tracksLots = true)) }
        val bakery = catalog.copy(
            products = catalog.products + (bread.id to bread),
            recipes = catalog.recipes +
                (bread.id to listOf(RecipeComponent(9L, bread.id, wick.id, QuantityMilli(1_000)))),
        )
        val model = viewModel(initial = bread.id, source = { flowOf(bakery) })
        model.act(this, ProductionFormAction.QuantityChange("2"))

        model.act(this, ProductionFormAction.LotCodeChange("x".repeat(61)))
        model.act(this, ProductionFormAction.Register)

        assertTrue(requireNotNull(model.state.outputLot).tooLong)
        assertTrue(requests.isEmpty())
        model.act(this, ProductionFormAction.ChooseInputLot(wick.id, null))
        assertNull(model.state.inputs.single().chosenLotId)
    }

    @Test
    fun `a read failure offers to retry`() = runTest(dispatcher) {
        var fail = true
        val model = viewModel(
            source = {
                flow {
                    if (fail) error("lectura")
                    emit(catalog)
                }
            },
        )
        assertTrue(model.state.loadFailed)

        fail = false
        model.act(this, ProductionFormAction.Retry)

        assertFalse(model.state.loadFailed)
    }
}
