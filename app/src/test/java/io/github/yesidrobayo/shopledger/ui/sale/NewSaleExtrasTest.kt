package io.github.yesidrobayo.shopledger.ui.sale

import androidx.lifecycle.SavedStateHandle
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.UnitKind
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodDetails
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodKind
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodRecord
import io.github.yesidrobayo.shopledger.domain.model.region.NumberSymbols
import io.github.yesidrobayo.shopledger.domain.model.sale.LineDiscount
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleCatalog
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleRequest
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxMode
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxSettings
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleResult
import io.github.yesidrobayo.shopledger.ui.product.COUNT_UNIT
import io.github.yesidrobayo.shopledger.ui.product.CREATED
import io.github.yesidrobayo.shopledger.ui.product.product
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Los extras en la venta nueva, sin base: agregar el producto sigue siendo un toque y no abre nada,
 * la hoja de la línea ofrece los extras vigentes como chips, el carrito los muestra bajo su línea y
 * la venta confirma cada uno enlazado a su línea. La venta en edición los conserva al recrear la
 * pantalla, con la unidad de cada línea. Productos y montos inventados.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NewSaleExtrasTest {
    private val dispatcher = StandardTestDispatcher()
    private val box = MeasureUnit(5L, "BOX", null, null, UnitKind.COUNT, false, null)
    private val arepa = product(1L, "Arepa", salePrice = 4_000L, onHand = 10_000L)
    private val cheese = product(2L, "Queso", salePrice = 1_000L, onHand = 1_000L)
    private val sauce = product(3L, "Salsa", salePrice = 200L)
    private val honey = product(4L, "Miel", salePrice = 500L)
    private val catalog = MutableStateFlow(
        catalogOf(
            products = listOf(arepa, cheese, sauce),
            // La miel es extra de la arepa, pero está archivada: no se ofrece.
            modifiers = mapOf(arepa.id to listOf(cheese.id, honey.id, sauce.id)),
        ),
    )
    private val requests = mutableListOf<SaleRequest>()
    private val symbols = NumberSymbols(decimalSeparator = ',', minusSign = '-', grouping = null)

    @Before
    fun useTestMain() = Dispatchers.setMain(dispatcher)

    @After
    fun resetTestMain() = Dispatchers.resetMain()

    private fun catalogOf(products: List<Product>, modifiers: Map<Long, List<Long>>) = SaleCatalog(
        products = products,
        frequent = products,
        archived = listOf(honey),
        units = listOf(COUNT_UNIT, box).associateBy { it.id },
        methods = listOf(
            PaymentMethodRecord(
                id = 10L,
                systemKey = "CASH",
                details = PaymentMethodDetails(
                    name = null,
                    normalizedName = null,
                    kind = PaymentMethodKind.CASH,
                    feeRate = BasisPoints.ZERO,
                    feeFixed = Money.ZERO,
                    settlementDays = 0,
                    priceAdjustment = BasisPoints.ZERO,
                    moneyAccountId = null,
                    instructionsText = null,
                    instructionsMediaId = null,
                ),
                sortOrder = 1,
                archivedAt = null,
                createdAt = CREATED,
                updatedAt = CREATED,
            ),
        ),
        taxes = TaxSettings(TaxMode.NONE, null, purchaseTaxRecoverable = false),
        taxRates = emptyMap(),
        today = BusinessDate(LocalDate(2026, 11, 2)),
        weekStartDay = DayOfWeek.MONDAY,
        modifiers = modifiers,
    )

    private fun TestScope.viewModel(
        state: SavedStateHandle = SavedStateHandle(),
    ): NewSaleViewModel {
        val model = NewSaleViewModel(
            catalogSource = { catalog },
            registerSale = {
                requests += it
                RegisterSaleResult.Registered(99L)
            },
            reactivate = { false },
            savedState = state,
            numbers = SaleNumberFormat(symbols, 0, symbols, symbols),
            computation = dispatcher,
        )
        backgroundScope.launch { model.uiState.collect {} }
        advanceUntilIdle()
        return model
    }

    private fun NewSaleViewModel.act(scope: TestScope, action: NewSaleAction) {
        onAction(action)
        scope.advanceUntilIdle()
    }

    private val NewSaleViewModel.state: NewSaleUiState get() = uiState.value

    private val NewSaleViewModel.editor: LineEditor
        get() = (state.sheet as SaleSheet.Line).editor

    @Test
    fun `adding a product with extras is one tap and opens nothing`() = runTest(dispatcher) {
        val model = viewModel()

        model.act(this, NewSaleAction.AddProduct(arepa.id))

        assertNull(model.state.sheet)
        assertEquals(1, model.state.cart.lines.size)
    }

    @Test
    fun `the line offers its active extras and a chip adds one under the line`() =
        runTest(dispatcher) {
            val model = viewModel()
            model.act(this, NewSaleAction.AddProduct(arepa.id))
            model.act(this, NewSaleAction.AddProduct(arepa.id))
            model.act(this, NewSaleAction.OpenLine(arepa.id))

            assertEquals(
                listOf(
                    ExtraChoice(cheese.id, "Queso", false),
                    ExtraChoice(sauce.id, "Salsa", false),
                ),
                model.editor.extras,
            )

            model.act(this, NewSaleAction.ToggleExtra(cheese.id))

            assertEquals(true, model.editor.extras.first().selected)
            val lines = model.state.cart.lines
            assertEquals(listOf(arepa.id, cheese.id), lines.map { it.productId })
            assertEquals(listOf(null, arepa.id), lines.map { it.parentProductId })
            // Una porción por arepa: el extra cobra 2 × 1000 aparte, y no cuenta como producto.
            assertEquals(Money(2_000L), lines[1].total)
            assertEquals(Money(10_000L), model.state.cart.total)
            assertEquals(2L, model.state.cart.itemCount)
            // Las cuentas de la línea abierta son las de la arepa, no las del extra.
            assertEquals(QuantityMilli(2_000L), lines[0].quantity)
        }

    @Test
    fun `the sale registers each extra linked to its line`() = runTest(dispatcher) {
        val model = viewModel()
        model.act(this, NewSaleAction.AddProduct(arepa.id))
        model.act(this, NewSaleAction.OpenLine(arepa.id))
        model.act(this, NewSaleAction.ToggleExtra(sauce.id))
        model.act(this, NewSaleAction.CloseSheet)
        // El mismo queso también se vende suelto, en su propia línea.
        model.act(this, NewSaleAction.AddProduct(cheese.id))

        model.act(this, NewSaleAction.Register)

        assertEquals(
            listOf(
                SaleLineRequest(arepa.id, QuantityMilli(1_000L)),
                SaleLineRequest(sauce.id, QuantityMilli(1_000L), parentIndex = 0),
                SaleLineRequest(cheese.id, QuantityMilli(1_000L)),
            ),
            requests.single().lines,
        )
    }

    @Test
    fun `removing the line takes its extras, and toggling again removes one`() =
        runTest(dispatcher) {
            val model = viewModel()
            model.act(this, NewSaleAction.AddProduct(arepa.id))
            model.act(this, NewSaleAction.OpenLine(arepa.id))
            model.act(this, NewSaleAction.ToggleExtra(cheese.id))
            model.act(this, NewSaleAction.ToggleExtra(sauce.id))
            model.act(this, NewSaleAction.ToggleExtra(cheese.id))

            assertEquals(listOf(arepa.id, sauce.id), model.state.cart.lines.map { it.productId })

            model.act(this, NewSaleAction.RemoveLine(arepa.id))

            assertTrue(model.state.cart.lines.isEmpty())
        }

    @Test
    fun `the sale in progress keeps its extras and the unit of each line after recreation`() =
        runTest(dispatcher) {
            val state = SavedStateHandle()
            val first = viewModel(state)
            first.act(this, NewSaleAction.AddProduct(arepa.id))
            first.act(this, NewSaleAction.OpenLine(arepa.id))
            first.act(this, NewSaleAction.ToggleExtra(cheese.id))
            first.act(this, NewSaleAction.LineDiscountChange("500"))

            val lines = SaleDraftStore.read(state).lines

            assertEquals(
                listOf(
                    SaleLineRequest(
                        arepa.id,
                        QuantityMilli(1_000L),
                        discount = LineDiscount.Amount(Money(500L)),
                    ),
                    SaleLineRequest(cheese.id, QuantityMilli(1_000L), parentIndex = 0),
                ),
                lines,
            )
            val boxed = SaleDraft(
                lines = listOf(SaleLineRequest(arepa.id, QuantityMilli(2_000L), unitId = box.id)),
            )
            SaleDraftStore.write(state, boxed)
            // Antes la unidad se perdía camino al cobro: dos cajas llegaban como dos unidades.
            assertEquals(boxed.lines, SaleDraftStore.read(state).lines)
        }
}
