package io.github.yesidrobayo.shopledger.ui.sale

import androidx.lifecycle.SavedStateHandle
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodDetails
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodKind
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodRecord
import io.github.yesidrobayo.shopledger.domain.model.region.NumberSymbols
import io.github.yesidrobayo.shopledger.domain.model.returns.ReturnLineRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleCatalog
import io.github.yesidrobayo.shopledger.domain.model.sale.SalePaymentRequest
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxMode
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxSettings
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.usecase.returns.ExchangeRequest
import io.github.yesidrobayo.shopledger.domain.usecase.returns.ExchangeResult
import io.github.yesidrobayo.shopledger.navigation.SaleExchange
import io.github.yesidrobayo.shopledger.navigation.SaleExchangeLine
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * La venta nueva de un cambio, sin base: la banda dice el crédito y la diferencia, el crédito paga
 * con el método de la venta de origen y lo que falta con el elegido, y salir siempre pregunta.
 * Productos inventados; los valores esperados están escritos a mano.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NewSaleExchangeTest {
    private val dispatcher = StandardTestDispatcher()
    private val rice = product(1L, "Arroz", salePrice = 4_200L, onHand = 1_000L)
    private val oil = product(2L, "Aceite", salePrice = 12_500L, onHand = 23_000L)
    private val exchanges = mutableListOf<ExchangeRequest>()
    private var result: ExchangeResult = ExchangeResult.Exchanged(99L, 41L)
    private val symbols = NumberSymbols(decimalSeparator = ',', minusSign = '-', grouping = null)

    @Before
    fun useTestMain() = Dispatchers.setMain(dispatcher)

    @After
    fun resetTestMain() = Dispatchers.resetMain()

    private fun method(id: Long, kind: PaymentMethodKind, accountId: Long?, surcharge: Long = 0L) =
        PaymentMethodRecord(
            id = id,
            systemKey = kind.name,
            details = PaymentMethodDetails(
                name = null,
                normalizedName = null,
                kind = kind,
                feeRate = BasisPoints.ZERO,
                feeFixed = Money.ZERO,
                settlementDays = 0,
                priceAdjustment = BasisPoints(surcharge),
                moneyAccountId = accountId,
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
        // La tarjeta tiene recargo: en un cambio no se suma al total de la banda.
        methods = listOf(
            method(10L, PaymentMethodKind.CASH, accountId = 5L),
            method(11L, PaymentMethodKind.CARD, accountId = null, surcharge = 500L),
        ),
        taxes = TaxSettings(TaxMode.NONE, null, purchaseTaxRecoverable = false),
        taxRates = emptyMap(),
        today = BusinessDate(LocalDate(2026, 11, 2)),
        weekStartDay = DayOfWeek.MONDAY,
    )

    private fun exchange(credit: Long) = SaleExchange(
        saleId = 7L,
        lines = listOf(SaleExchangeLine(70L, 1_000L, restock = true)),
        credit = credit,
        creditMethodId = 10L,
    )

    private fun TestScope.viewModel(credit: Long = 4_200L): NewSaleViewModel {
        val model = NewSaleViewModel(
            catalogSource = { MutableStateFlow(catalog) },
            registerSale = { error("Un cambio no registra una venta sola") },
            reactivate = { false },
            savedState = SavedStateHandle(),
            numbers = SaleNumberFormat(symbols, 0, symbols, symbols),
            computation = dispatcher,
            exchange = exchange(credit),
            exchangeSale = {
                exchanges += it
                result
            },
            exchangeNote = { "Talla equivocada" },
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

    @Test
    fun `a dearer sale collects the difference with the chosen method`() = runTest(dispatcher) {
        val model = viewModel()
        assertEquals(ExchangeBand(7L, Money(4_200L), null), model.state.exchange)

        model.act(this, NewSaleAction.AddProduct(oil.id))
        model.act(this, NewSaleAction.SelectMethod(11L))
        assertEquals(Money(12_500L), model.state.cart.total)
        assertEquals(ExchangeDifference.ToCollect(Money(8_300L)), model.state.exchange?.difference)

        model.act(this, NewSaleAction.Register)

        val request = exchanges.single()
        assertEquals(7L, request.saleId)
        assertEquals(listOf(ReturnLineRequest(70L, QuantityMilli(1_000L), true)), request.lines)
        assertEquals(5L, request.refundAccountId)
        assertEquals("Talla equivocada", request.note)
        assertEquals(
            listOf(SalePaymentRequest(10L, Money(4_200L)), SalePaymentRequest(11L, Money(8_300L))),
            request.replacement.payments,
        )
        assertEquals(RegisteredSale(99L, Money(12_500L), exchanged = true), model.state.registered)
    }

    @Test
    fun `a cheaper sale is paid by the credit and the rest goes back`() = runTest(dispatcher) {
        val model = viewModel(credit = 20_000L)

        model.act(this, NewSaleAction.AddProduct(oil.id))
        assertEquals(ExchangeDifference.ToRefund(Money(7_500L)), model.state.exchange?.difference)
        model.act(this, NewSaleAction.Register)

        assertEquals(
            listOf(SalePaymentRequest(10L, Money(12_500L))),
            exchanges.single().replacement.payments,
        )
    }

    @Test
    fun `the same value has no difference`() = runTest(dispatcher) {
        val model = viewModel(credit = 4_200L)

        model.act(this, NewSaleAction.AddProduct(rice.id))

        assertEquals(ExchangeDifference.None, model.state.exchange?.difference)
    }

    @Test
    fun `leaving an exchange always asks, even without products`() = runTest(dispatcher) {
        val model = viewModel()

        model.act(this, NewSaleAction.Close)
        assertEquals(SaleDialog.LeaveExchange, model.state.dialog)

        model.act(this, NewSaleAction.ConfirmLeave)
        assertTrue(model.state.closed)
        assertTrue(exchanges.isEmpty())
    }

    @Test
    fun `a failed exchange keeps the sale to retry`() = runTest(dispatcher) {
        result = ExchangeResult.StorageFailed(StorageFailure.IO_ERROR)
        val model = viewModel()
        model.act(this, NewSaleAction.AddProduct(oil.id))

        model.act(this, NewSaleAction.Register)

        assertEquals(RegisterStatus.FAILED, model.state.status)
        assertEquals(1, model.state.cart.lines.size)
    }
}
