package io.github.yesidrobayo.shopledger.ui.sale

import androidx.lifecycle.SavedStateHandle
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.customer.Customer
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerContact
import io.github.yesidrobayo.shopledger.domain.model.document.DocumentLine
import io.github.yesidrobayo.shopledger.domain.model.document.DocumentTotals
import io.github.yesidrobayo.shopledger.domain.model.document.NewDocumentLine
import io.github.yesidrobayo.shopledger.domain.model.document.Quote
import io.github.yesidrobayo.shopledger.domain.model.document.QuoteStatus
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodDetails
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodKind
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodRecord
import io.github.yesidrobayo.shopledger.domain.model.region.NumberSymbols
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleCatalog
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleRequest
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxMode
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxSettings
import io.github.yesidrobayo.shopledger.domain.usecase.document.ConvertibleLine
import io.github.yesidrobayo.shopledger.domain.usecase.document.DocumentLineRejection
import io.github.yesidrobayo.shopledger.domain.usecase.document.QuoteConversion
import io.github.yesidrobayo.shopledger.domain.usecase.document.QuoteConversionResult
import io.github.yesidrobayo.shopledger.domain.usecase.document.QuoteRequest
import io.github.yesidrobayo.shopledger.domain.usecase.document.QuoteView
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * La venta nueva armando una cotización, sin base: guarda con su hoja, dice lo que no se puede
 * guardar sin perder nada, edita una guardada y convierte una en venta con su número. Productos
 * inventados; los valores esperados están escritos a mano.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NewSaleQuoteTest {
    private val dispatcher = StandardTestDispatcher()
    private val rice = product(1L, "Arroz", salePrice = 4_200L, onHand = 1_000L)
    private val oil = product(2L, "Aceite", salePrice = 12_500L, onHand = 23_000L)
    private val saved = mutableListOf<QuoteRequest>()
    private val sold = mutableListOf<SaleRequest>()
    private var saveResult: SaveQuoteResult = SaveQuoteResult.Saved(41L)
    private val symbols = NumberSymbols(decimalSeparator = ',', minusSign = '-', grouping = null)

    @Before
    fun useTestMain() = Dispatchers.setMain(dispatcher)

    @After
    fun resetTestMain() = Dispatchers.resetMain()

    @Test
    fun `a new quote saves its lines with the chosen customer and closes`() = runTest(dispatcher) {
        val model = viewModel(SaleDocumentMode.NewQuote)
        assertEquals(QuoteEditing(editingId = null), model.state.quote)
        model.act(this, NewSaleAction.AddProduct(oil.id))

        // Registrar no vende: abre la hoja de la cotización.
        model.act(this, NewSaleAction.Register)
        assertTrue(model.state.sheet is SaleSheet.SaveQuote)
        model.act(this, NewSaleAction.QuoteOpenCustomers)
        assertEquals(1, (model.state.sheet as SaleSheet.SaveQuote).form.customerChoices.size)
        model.act(this, NewSaleAction.QuotePickCustomer(CUSTOMER.id))
        model.act(this, NewSaleAction.QuoteValidUntilChange(VALID_UNTIL))
        model.act(this, NewSaleAction.ConfirmSaveQuote)

        val request = saved.single()
        assertEquals(listOf(SaleLineRequest(oil.id, QuantityMilli(1_000L))), request.lines)
        assertEquals(CUSTOMER.id, request.customerId)
        assertEquals(VALID_UNTIL, request.validUntil)
        assertNull(request.quoteId)
        assertTrue(sold.isEmpty())
        assertEquals(41L, model.state.quoteSaved)
        assertTrue(model.state.closed)
    }

    @Test
    fun `saving from a sale empties the sale and stays`() = runTest(dispatcher) {
        val model = viewModel(document = null)
        model.act(this, NewSaleAction.AddProduct(rice.id))

        model.act(this, NewSaleAction.OpenSaveQuote)
        model.act(this, NewSaleAction.ConfirmSaveQuote)

        assertEquals(1, saved.size)
        assertTrue(model.state.cart.isEmpty)
        assertEquals(41L, model.state.quoteSaved)
        assertTrue(!model.state.closed)
    }

    @Test
    fun `what cannot be saved is said in the sheet and nothing is lost`() = runTest(dispatcher) {
        val model = viewModel(SaleDocumentMode.NewQuote)
        model.act(this, NewSaleAction.AddProduct(oil.id))
        model.act(this, NewSaleAction.OpenSaveQuote)

        saveResult = SaveQuoteResult.ValidUntilInPast
        model.act(this, NewSaleAction.ConfirmSaveQuote)
        assertTrue((model.state.sheet as SaleSheet.SaveQuote).form.pastDate)

        saveResult = SaveQuoteResult.LineRejected(0, DocumentLineRejection.EXTRA_NOT_SUPPORTED)
        model.act(this, NewSaleAction.ConfirmSaveQuote)
        assertTrue((model.state.sheet as SaleSheet.SaveQuote).form.extras)

        saveResult = SaveQuoteResult.QuoteNotOpen
        model.act(this, NewSaleAction.ConfirmSaveQuote)
        assertTrue((model.state.sheet as SaleSheet.SaveQuote).form.failed)
        assertEquals(1, model.state.cart.lines.size)
        assertNull(model.state.quoteSaved)
    }

    @Test
    fun `editing opens the saved lines and saves over the same quote`() = runTest(dispatcher) {
        val model = viewModel(SaleDocumentMode.EditQuote(41L))
        assertEquals(QuoteEditing(editingId = 41L), model.state.quote)
        assertEquals(Money(5_000L), model.state.cart.total)

        model.act(this, NewSaleAction.OpenSaveQuote)
        assertEquals(VALID_UNTIL, (model.state.sheet as SaleSheet.SaveQuote).form.validUntil)
        model.act(this, NewSaleAction.ConfirmSaveQuote)

        val request = saved.single()
        assertEquals(41L, request.quoteId)
        assertEquals(
            listOf(SaleLineRequest(rice.id, QuantityMilli(2_000L), Money(2_500L), unitId = 1L)),
            request.lines,
        )
    }

    @Test
    fun `converting registers the sale with the quote and its customer`() = runTest(dispatcher) {
        val model = viewModel(SaleDocumentMode.FromQuote(41L, useCurrentPrices = false))
        assertNull(model.state.quote)
        assertEquals(Money(5_000L), model.state.cart.total)

        model.act(this, NewSaleAction.Register)

        val request = sold.single()
        assertEquals(41L, request.quoteId)
        assertEquals(CUSTOMER.id, request.customerId)
        assertEquals(Money(2_500L), request.lines.single().unitPrice)
        assertTrue(saved.isEmpty())
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
        override suspend fun saveQuote(request: QuoteRequest): SaveQuoteResult {
            saved += request
            return saveResult
        }

        override suspend fun quote(quoteId: Long): QuoteView = QuoteView(QUOTE, listOf(LINE), null)

        override suspend fun conversion(quoteId: Long): QuoteConversionResult =
            QuoteConversionResult.Ready(
                QuoteConversion(
                    quoteId = quoteId,
                    customerId = CUSTOMER.id,
                    priceListId = null,
                    customerDropped = false,
                    lines = listOf(
                        ConvertibleLine(
                            SaleLineRequest(rice.id, QuantityMilli(2_000L), Money(2_500L)),
                            Money(4_200L),
                        ),
                    ),
                    archivedLines = 0,
                    expiredOn = null,
                ),
            )

        override fun customers(): Flow<List<Customer>> = flowOf(listOf(CUSTOMER))
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
        val VALID_UNTIL = BusinessDate(LocalDate(2026, 11, 20))

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

        val QUOTE = Quote(
            id = 41L,
            customerId = 30L,
            status = QuoteStatus.OPEN,
            validUntil = VALID_UNTIL,
            totals = DocumentTotals(Money(5_000L), Money.ZERO, Money(5_000L)),
            convertedSaleId = null,
            note = null,
            occurredAt = CREATED,
            businessDate = TODAY,
            createdAt = CREATED,
            updatedAt = CREATED,
        )

        val LINE = DocumentLine(
            id = 1L,
            documentId = 41L,
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
