package io.github.yesidrobayo.shopledger.ui.sale

import androidx.lifecycle.SavedStateHandle
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.ConversionDetails
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.catalog.UnitKind
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodDetails
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodKind
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodRecord
import io.github.yesidrobayo.shopledger.domain.model.region.NumberSymbols
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleCatalog
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleRequest
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxMode
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxSettings
import io.github.yesidrobayo.shopledger.domain.rule.sale.CartLineIssue
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
import org.junit.Before
import org.junit.Test

/**
 * Vender en otra unidad desde la venta nueva, sin base: la hoja de la línea ofrece las unidades de
 * venta del producto, el total cobra por caja, el aviso de stock va en la unidad base y la venta
 * confirma con la unidad elegida. Productos y montos inventados.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NewSaleUnitsTest {
    private val dispatcher = StandardTestDispatcher()
    private val box = MeasureUnit(5L, "BOX", null, null, UnitKind.COUNT, false, null)
    private val kilo = MeasureUnit(2L, "KILOGRAM", null, null, UnitKind.MASS, true, null)
    private val oil = product(2L, "Aceite", salePrice = 12_500L, onHand = 23_000L)
    private val cheese = product(3L, "Queso", salePrice = 22_000L).let {
        it.copy(details = it.details.copy(baseUnitId = kilo.id))
    }
    private val conversions = mapOf(
        oil.id to mapOf(box.id to ConversionDetails(box.id, 12_000L, null, null, true, false)),
        cheese.id to
            mapOf(box.id to ConversionDetails(box.id, 5_000L, Money(100_000L), null, true, false)),
    )
    private val catalog = MutableStateFlow(catalogOf(conversions))
    private val requests = mutableListOf<SaleRequest>()
    private val symbols = NumberSymbols(decimalSeparator = ',', minusSign = '-', grouping = null)

    @Before
    fun useTestMain() = Dispatchers.setMain(dispatcher)

    @After
    fun resetTestMain() = Dispatchers.resetMain()

    private fun catalogOf(byProduct: Map<Long, Map<Long, ConversionDetails>>) = SaleCatalog(
        products = listOf(oil, cheese),
        frequent = listOf(oil, cheese),
        archived = emptyList(),
        units = listOf(COUNT_UNIT, kilo, box).associateBy { it.id },
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
        conversions = byProduct,
    )

    private fun TestScope.viewModel(): NewSaleViewModel {
        val model = NewSaleViewModel(
            catalogSource = { catalog },
            registerSale = {
                requests += it
                RegisterSaleResult.Registered(99L)
            },
            reactivate = { false },
            savedState = SavedStateHandle(),
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
    fun `the line offers the base unit and the box, and sells by the box`() = runTest(dispatcher) {
        val model = viewModel()
        model.act(this, NewSaleAction.AddProduct(oil.id))
        model.act(this, NewSaleAction.Increment(oil.id))
        model.act(this, NewSaleAction.OpenLine(oil.id))
        assertEquals(listOf(COUNT_UNIT.id, box.id), model.editor.saleUnits.map { it.id })

        model.act(this, NewSaleAction.LineUnitChange(box.id))

        assertEquals(box.id, model.editor.unitId)
        assertEquals(box, model.editor.unit)
        // Dos cajas de 12 a 12.500 cada unidad: 300.000.
        assertEquals(Money(300_000L), model.state.cart.total)
        assertEquals(box, model.state.cart.lines.single().unit)
        assertEquals("150000", model.editor.priceText)
        val tile = (model.state.content as SaleContent.Products).tiles.first {
            it.productId ==
                oil.id
        }
        assertEquals(box, tile.line?.unit)
        // 24 unidades contra 23 en stock: falta una, en la unidad base.
        assertEquals(QuantityMilli(-1_000L), tile.line?.oversell)

        model.act(this, NewSaleAction.CloseSheet)
        model.act(this, NewSaleAction.Register)

        val line = requests.single().lines.single()
        assertEquals(box.id, line.unitId)
        assertEquals(QuantityMilli(2_000L), line.quantity)
    }

    @Test
    fun `going back to the base unit sells by the unit again`() = runTest(dispatcher) {
        val model = viewModel()
        model.act(this, NewSaleAction.AddProduct(oil.id))
        model.act(this, NewSaleAction.OpenLine(oil.id))
        model.act(this, NewSaleAction.LinePriceChange("100000"))
        model.act(this, NewSaleAction.LineUnitChange(box.id))
        // El precio escrito era por la unidad anterior: la caja vuelve a su precio, 12 × 12.500.
        assertEquals(Money(150_000L), model.state.cart.lines.single().unitPrice)

        model.act(this, NewSaleAction.LineUnitChange(COUNT_UNIT.id))

        assertEquals(COUNT_UNIT.id, model.editor.unitId)
        assertEquals(Money(12_500L), model.state.cart.total)
        model.act(this, NewSaleAction.CloseSheet)
        model.act(this, NewSaleAction.Register)
        // La unidad base va sin unidad, como siempre.
        assertNull(requests.single().lines.single().unitId)
    }

    @Test
    fun `a fraction of a kilo becomes one whole box`() = runTest(dispatcher) {
        val model = viewModel()
        model.act(this, NewSaleAction.AddProduct(cheese.id))
        model.act(this, NewSaleAction.QuantityTextChange("1,5"))
        model.act(this, NewSaleAction.QuantityConfirm)
        model.act(this, NewSaleAction.OpenLine(cheese.id))

        model.act(this, NewSaleAction.LineUnitChange(box.id))

        assertEquals(QuantityMilli(1_000L), model.state.cart.lines.single().quantity)
        // La caja de queso tiene precio propio.
        assertEquals(Money(100_000L), model.state.cart.total)
    }

    @Test
    fun `a box that stopped being sold marks the line`() = runTest(dispatcher) {
        val model = viewModel()
        model.act(this, NewSaleAction.AddProduct(oil.id))
        model.act(this, NewSaleAction.OpenLine(oil.id))
        model.act(this, NewSaleAction.LineUnitChange(box.id))

        catalog.value = catalogOf(emptyMap())
        advanceUntilIdle()

        assertEquals(CartLineIssue.UNIT_UNAVAILABLE, model.state.cart.lines.single().issue)
        assertNull(model.state.cart.total)
    }
}
