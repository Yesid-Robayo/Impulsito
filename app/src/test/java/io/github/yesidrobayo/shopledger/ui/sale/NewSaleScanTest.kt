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
 * Lo que trae el escáner a la venta nueva: el producto se agrega como al tocarlo, y el código de
 * una presentación pone la línea nueva en esa unidad. Un lector externo escribe el código en la
 * búsqueda y termina con Enter, y llega igual. Productos, códigos y montos inventados.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NewSaleScanTest {
    private val dispatcher = StandardTestDispatcher()
    private val box = MeasureUnit(5L, "BOX", null, null, UnitKind.COUNT, false, null)
    private val kilo = MeasureUnit(2L, "KILOGRAM", null, null, UnitKind.MASS, true, null)
    private val oil =
        product(2L, "Aceite", salePrice = 12_500L, onHand = 23_000L, barcode = "2900000123458")
    private val cheese = product(3L, "Queso", salePrice = 22_000L).let {
        it.copy(details = it.details.copy(baseUnitId = kilo.id))
    }
    private val conversions = mapOf(
        oil.id to
            mapOf(box.id to ConversionDetails(box.id, 12_000L, null, "2900000999992", true, false)),
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
    fun `a scanned product is added like a tap`() = runTest(dispatcher) {
        val model = viewModel()

        model.act(this, NewSaleAction.AddScanned(oil.id, unitId = null))
        model.act(this, NewSaleAction.AddScanned(oil.id, unitId = null))

        val line = model.state.cart.lines.single()
        assertEquals(QuantityMilli(2_000L), line.quantity)
        assertEquals(COUNT_UNIT, line.unit)
    }

    @Test
    fun `a box code puts a new line in boxes`() = runTest(dispatcher) {
        val model = viewModel()

        model.act(this, NewSaleAction.AddScanned(oil.id, unitId = box.id))

        val line = model.state.cart.lines.single()
        assertEquals(box, line.unit)
        assertEquals(QuantityMilli(1_000L), line.quantity)
    }

    @Test
    fun `a box code on a line in units adds one unit`() = runTest(dispatcher) {
        val model = viewModel()
        model.act(this, NewSaleAction.AddProduct(oil.id))

        model.act(this, NewSaleAction.AddScanned(oil.id, unitId = box.id))

        val line = model.state.cart.lines.single()
        assertEquals(COUNT_UNIT, line.unit)
        assertEquals(QuantityMilli(2_000L), line.quantity)
    }

    @Test
    fun `a product that is not in the catalog adds nothing`() = runTest(dispatcher) {
        val model = viewModel()

        model.act(this, NewSaleAction.AddScanned(99L, unitId = null))

        assertEquals(emptyList<Any>(), model.state.cart.lines)
    }

    @Test
    fun `the scanner is offered only with a code saved`() = runTest(dispatcher) {
        val model = viewModel()
        assertEquals(true, model.state.canScan)

        val plain = oil.copy(details = oil.details.copy(barcode = null))
        catalog.value = catalogOf(emptyMap()).copy(products = listOf(plain, cheese))
        advanceUntilIdle()

        assertEquals(false, model.state.canScan)
    }

    @Test
    fun `a code typed by an external reader and Enter adds the product`() = runTest(dispatcher) {
        val model = viewModel()

        model.act(this, NewSaleAction.QueryChange("2900000123458"))
        model.act(this, NewSaleAction.SubmitSearch)

        assertEquals(QuantityMilli(1_000L), model.state.cart.lines.single().quantity)
        assertEquals("", model.state.query)
    }

    @Test
    fun `a box code from the reader sells a box`() = runTest(dispatcher) {
        val model = viewModel()

        model.act(this, NewSaleAction.QueryChange("2900000999992"))
        model.act(this, NewSaleAction.SubmitSearch)

        assertEquals(box, model.state.cart.lines.single().unit)
    }

    @Test
    fun `Enter on a name or an unknown code keeps the search`() = runTest(dispatcher) {
        val model = viewModel()

        model.act(this, NewSaleAction.QueryChange("Aceite"))
        model.act(this, NewSaleAction.SubmitSearch)
        assertEquals("Aceite", model.state.query)
        model.act(this, NewSaleAction.QueryChange("P00000042"))
        model.act(this, NewSaleAction.SubmitSearch)

        assertEquals("P00000042", model.state.query)
        assertEquals(emptyList<Any>(), model.state.cart.lines)
    }
}
