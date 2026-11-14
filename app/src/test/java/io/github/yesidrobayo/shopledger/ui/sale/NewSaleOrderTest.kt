package io.github.yesidrobayo.shopledger.ui.sale

import androidx.lifecycle.SavedStateHandle
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.customer.Customer
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerContact
import io.github.yesidrobayo.shopledger.domain.model.document.DocumentLine
import io.github.yesidrobayo.shopledger.domain.model.document.NewDocumentLine
import io.github.yesidrobayo.shopledger.domain.model.document.Order
import io.github.yesidrobayo.shopledger.domain.model.document.OrderStatus
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodDetails
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodKind
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodRecord
import io.github.yesidrobayo.shopledger.domain.model.region.NumberSymbols
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleCatalog
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleRequest
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxMode
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxSettings
import io.github.yesidrobayo.shopledger.domain.usecase.document.OrderDelivery
import io.github.yesidrobayo.shopledger.domain.usecase.document.OrderDeliveryResult
import io.github.yesidrobayo.shopledger.domain.usecase.document.OrderLineView
import io.github.yesidrobayo.shopledger.domain.usecase.document.OrderRequest
import io.github.yesidrobayo.shopledger.domain.usecase.document.OrderView
import io.github.yesidrobayo.shopledger.domain.usecase.document.QuoteConversionResult
import io.github.yesidrobayo.shopledger.domain.usecase.document.QuoteRequest
import io.github.yesidrobayo.shopledger.domain.usecase.document.QuoteView
import io.github.yesidrobayo.shopledger.domain.usecase.document.SaveOrderResult
import io.github.yesidrobayo.shopledger.domain.usecase.document.SaveQuoteResult
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleResult
import io.github.yesidrobayo.shopledger.navigation.SaleDocumentMode
import io.github.yesidrobayo.shopledger.ui.product.COUNT_UNIT
import io.github.yesidrobayo.shopledger.ui.product.CREATED
import io.github.yesidrobayo.shopledger.ui.product.product
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * La venta nueva armando un pedido o entregándolo, sin base: guarda con su hoja la entrega y el
 * envío, edita uno guardado, y la venta de la entrega lleva el pedido, o pasa al cobro si hay envío
 * o saldo a favor. Productos inventados; los valores esperados están escritos a mano.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NewSaleOrderTest {
    private val dispatcher = StandardTestDispatcher()
    private val rice = product(1L, "Arroz", salePrice = 4_200L, onHand = 1_000L)
    private val oil = product(2L, "Aceite", salePrice = 12_500L, onHand = 23_000L)
    private val saved = mutableListOf<OrderRequest>()
    private val sold = mutableListOf<SaleRequest>()
    private var saveResult: SaveOrderResult = SaveOrderResult.Saved(58L)
    private var shipping = Money.ZERO
    private var customers = listOf(CUSTOMER)
    private val symbols = NumberSymbols(decimalSeparator = ',', minusSign = '-', grouping = null)

    @Before
    fun useTestMain() = Dispatchers.setMain(dispatcher)

    @After
    fun resetTestMain() = Dispatchers.resetMain()

    @Test
    fun `a new order saves its delivery and shipping and closes`() = runTest(dispatcher) {
        val model = viewModel(SaleDocumentMode.NewOrder)
        assertEquals(OrderEditing(editingId = null), model.state.order)
        assertTrue(model.state.cart.forQuote)
        model.act(this, NewSaleAction.AddProduct(oil.id))

        // Registrar no vende: abre la hoja del pedido.
        model.act(this, NewSaleAction.Register)
        assertTrue(model.state.sheet is SaleSheet.SaveOrder)
        model.act(this, NewSaleAction.OrderPickCustomer(CUSTOMER.id))
        model.act(this, NewSaleAction.OrderDueDateChange(DUE))
        model.act(this, NewSaleAction.OrderTextChange(OrderTextField.ADDRESS, "Calle 8"))
        model.act(this, NewSaleAction.OrderTextChange(OrderTextField.NOTE, "Timbre 2"))
        model.act(this, NewSaleAction.OrderTextChange(OrderTextField.SHIPPING, "1500"))
        assertEquals("Ana Ríos", (model.state.sheet as SaleSheet.SaveOrder).form.customerName)
        model.act(this, NewSaleAction.ConfirmSaveOrder)

        val request = saved.single()
        assertEquals(listOf(SaleLineRequest(oil.id, QuantityMilli(1_000L))), request.lines)
        assertEquals(CUSTOMER.id, request.customerId)
        assertEquals(DUE, request.dueDate)
        assertEquals("Calle 8", request.deliveryAddress)
        assertEquals("Timbre 2", request.deliveryNote)
        assertEquals(Money(1_500L), request.shippingAmount)
        assertNull(request.orderId)
        assertTrue(sold.isEmpty())
        assertEquals(58L, model.state.orderSaved)
        assertTrue(model.state.closed)
    }

    @Test
    fun `a shipping that cannot be read keeps the sheet and saves nothing`() = runTest(dispatcher) {
        val model = viewModel(SaleDocumentMode.NewOrder)
        model.act(this, NewSaleAction.AddProduct(rice.id))
        model.act(this, NewSaleAction.OpenSaveOrder)

        model.act(this, NewSaleAction.OrderTextChange(OrderTextField.SHIPPING, "-5"))
        model.act(this, NewSaleAction.ConfirmSaveOrder)
        assertTrue((model.state.sheet as SaleSheet.SaveOrder).form.shippingError)
        assertTrue(saved.isEmpty())

        model.act(this, NewSaleAction.OrderTextChange(OrderTextField.SHIPPING, ""))
        saveResult = SaveOrderResult.OrderNotOpen
        model.act(this, NewSaleAction.ConfirmSaveOrder)
        val form = (model.state.sheet as SaleSheet.SaveOrder).form
        assertTrue(form.failed)
        assertFalse(form.shippingError)
        assertEquals(Money.ZERO, saved.single().shippingAmount)
        assertEquals(1, model.state.cart.lines.size)
    }

    @Test
    fun `saving from a sale empties the sale and stays`() = runTest(dispatcher) {
        val model = viewModel(document = null)
        model.act(this, NewSaleAction.AddProduct(rice.id))

        model.act(this, NewSaleAction.OpenSaveOrder)
        model.act(this, NewSaleAction.ConfirmSaveOrder)

        assertEquals(1, saved.size)
        assertTrue(model.state.cart.isEmpty)
        assertEquals(58L, model.state.orderSaved)
        assertFalse(model.state.closed)
    }

    @Test
    fun `editing opens the saved order and saves over it`() = runTest(dispatcher) {
        val model = viewModel(SaleDocumentMode.EditOrder(58L))
        assertEquals(OrderEditing(editingId = 58L), model.state.order)
        assertEquals(Money(5_000L), model.state.cart.total)

        model.act(this, NewSaleAction.OpenSaveOrder)
        val form = (model.state.sheet as SaleSheet.SaveOrder).form
        assertEquals(DUE, form.dueDate)
        assertEquals("Calle 8", form.address)
        assertEquals("1500", form.shippingText)
        model.act(this, NewSaleAction.ConfirmSaveOrder)

        val request = saved.single()
        assertEquals(58L, request.orderId)
        assertEquals(Money(1_500L), request.shippingAmount)
        assertEquals(
            listOf(SaleLineRequest(rice.id, QuantityMilli(2_000L), Money(2_500L), unitId = 1L)),
            request.lines,
        )
    }

    @Test
    fun `a delivery without shipping or credit registers the sale with the order`() =
        runTest(dispatcher) {
            val model = viewModel(SaleDocumentMode.FromOrder(58L))
            assertNull(model.state.order)
            assertEquals(DeliveryBand(58L, Money.ZERO, Money.ZERO), model.state.delivery)

            model.act(this, NewSaleAction.Register)

            val request = sold.single()
            assertEquals(58L, request.orderId)
            assertEquals(CUSTOMER.id, request.customerId)
            assertEquals(Money(2_500L), request.lines.single().unitPrice)
            assertTrue(saved.isEmpty())
        }

    @Test
    fun `a delivery with shipping or credit goes through checkout`() = runTest(dispatcher) {
        shipping = Money(1_500L)
        customers = listOf(CUSTOMER.copy(balance = Money(-5_000L)))
        val model = viewModel(SaleDocumentMode.FromOrder(58L))

        val band = model.state.delivery
        assertEquals(DeliveryBand(58L, Money(1_500L), Money(5_000L)), band)
        assertTrue(band?.needsCheckout == true)
        // Registrar desde aquí no guarda: el cobro muestra el envío y el saldo a favor.
        model.act(this, NewSaleAction.Register)
        assertTrue(sold.isEmpty())
        val draft = model.checkoutDraft()
        assertEquals(58L, draft?.orderId)
        assertEquals(Money(1_500L), draft?.shipping)
        assertEquals(CUSTOMER.id, draft?.customerId)
    }

    // ---------------------------------------------------------------- dobles

    private fun TestScope.viewModel(document: SaleDocumentMode?): NewSaleViewModel {
        val model = NewSaleViewModel(
            catalogSource = { MutableStateFlow(catalog) },
            registerSale = {
                sold += it
                RegisterSaleResult.Registered(7L)
            },
            reactivate = { false },
            savedState = SavedStateHandle(),
            numbers = SaleNumberFormat(symbols, 0, symbols, symbols),
            computation = dispatcher,
            document = document,
            documents = operations,
        )
        backgroundScope.launch { model.uiState.collect {} }
        advanceUntilIdle()
        return model
    }

    private val operations = object : SaleDocumentOperations {
        override suspend fun saveQuote(request: QuoteRequest): SaveQuoteResult =
            SaveQuoteResult.EmptyQuote

        override suspend fun quote(quoteId: Long): QuoteView? = null

        override suspend fun conversion(quoteId: Long): QuoteConversionResult =
            QuoteConversionResult.NotFound

        override fun customers(): Flow<List<Customer>> = flowOf(customers)

        override suspend fun saveOrder(request: OrderRequest): SaveOrderResult {
            saved += request
            return saveResult
        }

        override suspend fun order(orderId: Long): OrderView = OrderView(
            ORDER,
            listOf(OrderLineView(LINE, QuantityMilli(2_000L), QuantityMilli.ZERO)),
            "Ana Ríos",
        )

        override suspend fun delivery(orderId: Long): OrderDeliveryResult =
            OrderDeliveryResult.Ready(
                OrderDelivery(
                    orderId = orderId,
                    customerId = CUSTOMER.id,
                    priceListId = null,
                    lines = listOf(SaleLineRequest(rice.id, QuantityMilli(2_000L), Money(2_500L))),
                    shippingAmount = shipping,
                    archivedLines = 0,
                    credit = Money.ZERO,
                ),
            )
    }

    private fun NewSaleViewModel.act(scope: TestScope, action: NewSaleAction) {
        onAction(action)
        scope.advanceUntilIdle()
    }

    private val NewSaleViewModel.state: NewSaleUiState get() = uiState.value

    private fun method(id: Long) = PaymentMethodRecord(
        id = id,
        systemKey = PaymentMethodKind.CASH.name,
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
        sortOrder = id,
        archivedAt = null,
        createdAt = CREATED,
        updatedAt = CREATED,
    )

    private val catalog = SaleCatalog(
        products = listOf(rice, oil),
        frequent = listOf(rice, oil),
        archived = emptyList(),
        units = mapOf(COUNT_UNIT.id to COUNT_UNIT),
        methods = listOf(method(10L)),
        taxes = TaxSettings(TaxMode.NONE, null, purchaseTaxRecoverable = false),
        taxRates = emptyMap(),
        today = TODAY,
        weekStartDay = DayOfWeek.MONDAY,
    )

    private companion object {
        val TODAY = BusinessDate(LocalDate(2026, 11, 2))
        val DUE = BusinessDate(LocalDate(2026, 11, 20))

        val CUSTOMER = Customer(
            id = 30L,
            contact = CustomerContact("Ana Ríos", "ana rios", null, null, null),
            priceListId = null,
            balance = Money.ZERO,
            pointsBalance = 0L,
            anonymizedAt = null,
            archivedAt = null,
            createdAt = CREATED,
            updatedAt = CREATED,
        )

        val ORDER = Order(
            id = 58L,
            customerId = 30L,
            status = OrderStatus.PENDING,
            deliveryAddress = "Calle 8",
            deliveryNote = null,
            shippingAmount = Money(1_500L),
            dueDate = DUE,
            deliveredSaleId = null,
            total = Money(6_500L),
            occurredAt = CREATED,
            businessDate = TODAY,
            createdAt = CREATED,
            updatedAt = CREATED,
        )

        val LINE = DocumentLine(
            id = 1L,
            documentId = 58L,
            content = NewDocumentLine(
                productId = 1L,
                unitId = 1L,
                unitFactor = QuantityMilli(1_000L),
                enteredQuantity = QuantityMilli(2_000L),
                quantity = QuantityMilli(2_000L),
                unitPrice = Money(2_500L),
                lineDiscount = Money.ZERO,
                netAmount = Money(5_000L),
                taxRate = BasisPoints.ZERO,
                taxNameSnapshot = null,
                taxAmount = Money.ZERO,
                lineTotal = Money(5_000L),
                productNameSnapshot = "Arroz",
            ),
        )
    }
}
