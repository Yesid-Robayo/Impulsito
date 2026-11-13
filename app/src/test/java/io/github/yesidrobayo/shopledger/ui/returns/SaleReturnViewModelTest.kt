package io.github.yesidrobayo.shopledger.ui.returns

import androidx.lifecycle.SavedStateHandle
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.customer.Customer
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerContact
import io.github.yesidrobayo.shopledger.domain.model.money.AccountCurrency
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccount
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccountDetails
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccountKind
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodKind
import io.github.yesidrobayo.shopledger.domain.model.region.DigitGrouping
import io.github.yesidrobayo.shopledger.domain.model.region.NumberSymbols
import io.github.yesidrobayo.shopledger.domain.model.returns.LineAmounts
import io.github.yesidrobayo.shopledger.domain.model.returns.ReturnLineRequest
import io.github.yesidrobayo.shopledger.domain.model.returns.ReturnResolution
import io.github.yesidrobayo.shopledger.domain.model.returns.ReturnSettlement
import io.github.yesidrobayo.shopledger.domain.model.returns.SaleReturnRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.LineStockEffect
import io.github.yesidrobayo.shopledger.domain.model.sale.NewSale
import io.github.yesidrobayo.shopledger.domain.model.sale.NewSaleLine
import io.github.yesidrobayo.shopledger.domain.model.sale.NewSalePayment
import io.github.yesidrobayo.shopledger.domain.model.sale.Sale
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleDetail
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLine
import io.github.yesidrobayo.shopledger.domain.model.sale.SalePayment
import io.github.yesidrobayo.shopledger.domain.model.settings.CurrencyCode
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendar
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import io.github.yesidrobayo.shopledger.domain.usecase.returns.RegisterSaleReturnResult
import io.github.yesidrobayo.shopledger.navigation.SaleExchangeLine
import io.github.yesidrobayo.shopledger.ui.product.COUNT_UNIT
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
 * La devolución de una venta sin base: el tope de cada línea, el total con el reparto de la regla,
 * cómo se resuelve y lo que llega al caso de uso. Los valores esperados están escritos a mano, con
 * el ejemplo de la regla: una línea de 3 unidades con lo neto en 10000 devuelve 3333, 3334 y 3333.
 * Datos inventados.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SaleReturnViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val saleDate = BusinessDate(LocalDate(2026, 11, 2))

    /** 2026-11-02 15:00 UTC: lunes 2026-11-02 10:00 en `Etc/GMT+5`. */
    private val at = Instant.fromEpochMilliseconds(1_793_631_600_000L)
    private var now = at
    private val calendar: BusinessCalendarResult = BusinessCalendar.of(
        "Etc/GMT+5",
        DayOfWeek.MONDAY,
    )
    private val symbols = NumberSymbols(',', '-', DigitGrouping('.', 3, 3))
    private val kilogram = MeasureUnit(2L, "KILOGRAM", null, null, COUNT_UNIT.kind, true, null)
    private val accounts = MutableStateFlow(listOf(account(5L), account(6L)))
    private val customers = mutableMapOf<Long, Customer>()
    private val requests = mutableListOf<SaleReturnRequest>()
    private var result: RegisterSaleReturnResult = RegisterSaleReturnResult.Registered(
        9L,
        Money(3_333L),
    )
    private var state = SavedStateHandle()
    private val detail = MutableStateFlow<SaleDetail?>(detailOf())

    @Before
    fun useTestMain() = Dispatchers.setMain(dispatcher)

    @After
    fun resetTestMain() = Dispatchers.resetMain()

    private fun account(id: Long) = MoneyAccount(
        id = id,
        systemKey = null,
        details = MoneyAccountDetails("Account $id", "account $id", MoneyAccountKind.BANK),
        currency = AccountCurrency(requireNotNull(CurrencyCode.of("XTS")), 2),
        balance = Money.ZERO,
        archivedAt = null,
        createdAt = at,
        updatedAt = at,
    )

    private fun line(id: Long, productId: Long, quantity: Long, net: Long, cost: Long) = SaleLine(
        id = id,
        saleId = 7L,
        content = NewSaleLine(
            productId = productId,
            unitId = 1L,
            unitFactor = QuantityMilli(1_000L),
            enteredQuantity = QuantityMilli(quantity),
            quantity = QuantityMilli(quantity),
            unitPrice = Money(net),
            unitCost = Money.ZERO,
            lineDiscount = Money.ZERO,
            orderAdjustmentShare = Money.ZERO,
            netAmount = Money(net),
            taxRate = BasisPoints.ZERO,
            taxNameSnapshot = null,
            taxAmount = Money.ZERO,
            lineTotal = Money(net),
            lineCost = Money(cost),
            unitReplacementCost = Money.ZERO,
            lineReplacementCost = Money.ZERO,
            productNameSnapshot = "Producto $productId",
            stockEffect = LineStockEffect.DIRECT,
            parentLineId = null,
            priceListId = 1L,
        ),
    )

    private fun detailOf(
        customerId: Long? = null,
        kind: PaymentMethodKind = PaymentMethodKind.CASH,
        returned: Map<Long, LineAmounts> = emptyMap(),
    ): SaleDetail {
        val sale = Sale(
            id = 7L,
            content = NewSale(
                netTotal = Money(15_000L),
                taxTotal = Money.ZERO,
                total = Money(15_000L),
                totalCost = Money(9_000L),
                totalReplacementCost = Money(9_000L),
                orderAdjustment = Money.ZERO,
                tipAmount = Money.ZERO,
                cashRoundingAmount = Money.ZERO,
                amountDue = Money(15_000L),
                customerId = customerId,
                priceListId = 1L,
                locationId = 1L,
                cashSessionId = null,
                quoteId = null,
                orderId = null,
                note = null,
                occurredAt = at,
                businessDate = saleDate,
                createdAt = at,
            ),
            voiding = null,
        )
        val payment = SalePayment(
            id = 700L,
            saleId = 7L,
            content = NewSalePayment(
                paymentMethodId = 3L,
                methodNameSnapshot = "Efectivo",
                methodKind = kind,
                amount = Money(15_000L),
                feeAmount = Money.ZERO,
                settlementDate = saleDate,
                moneyAccountId = 6L,
                createdAt = at,
            ),
        )
        return SaleDetail(
            sale = sale,
            lines = listOf(
                line(70L, 1L, 3_000L, 10_000L, 6_000L),
                line(71L, 2L, 1_250L, 5_000L, 3_000L),
            ),
            payments = listOf(payment),
            archivedProductIds = emptySet(),
            returned = returned,
        )
    }

    private fun TestScope.viewModel(): SaleReturnViewModel {
        val model = SaleReturnViewModel(
            saleId = 7L,
            observeDetail = { detail },
            observeUnits = { MutableStateFlow(listOf(COUNT_UNIT, kilogram)) },
            observeCalendar = { MutableStateFlow(calendar) },
            baseUnits = { mapOf(1L to COUNT_UNIT.id, 2L to kilogram.id) },
            payingAccounts = accounts,
            observeCustomer = { flowOf(customers[it]) },
            hasPoints = { false },
            register = {
                requests += it
                result
            },
            savedState = state,
            quantitySymbols = symbols,
            now = { now },
            computation = dispatcher,
        )
        backgroundScope.launch { model.uiState.collect {} }
        advanceUntilIdle()
        return model
    }

    private fun SaleReturnViewModel.act(scope: TestScope, action: SaleReturnAction) {
        onAction(action)
        scope.advanceUntilIdle()
    }

    private val SaleReturnViewModel.shown: SaleReturnUiState get() = uiState.value

    @Test
    fun `returning one of three units refunds its frozen share`() = runTest(dispatcher) {
        val model = viewModel()

        model.act(this, SaleReturnAction.Increment(70L))

        assertEquals(Money(3_333L), model.shown.total)
        assertEquals(1, model.shown.itemCount)
        // Con una ya devuelta, la segunda se lleva 3334: la suma de las tres es exacta.
        detail.value = detailOf(
            returned = mapOf(
                70L to LineAmounts(QuantityMilli(1_000L), Money(3_333L), Money.ZERO, Money(2_000L)),
            ),
        )
        advanceUntilIdle()
        assertEquals(Money(3_334L), model.shown.total)
        assertEquals(QuantityMilli(2_000L), model.shown.lines.first().remaining)
    }

    @Test
    fun `a line never goes above what is left to return`() = runTest(dispatcher) {
        val model = viewModel()

        repeat(5) { model.act(this, SaleReturnAction.Increment(70L)) }

        val line = model.shown.lines.first()
        assertEquals(QuantityMilli(3_000L), line.quantity)
        assertFalse(line.canIncrement)
        assertEquals(Money(10_000L), model.shown.total)
    }

    @Test
    fun `a fraction is typed and checked against what is left`() = runTest(dispatcher) {
        val model = viewModel()

        model.act(this, SaleReturnAction.EditQuantity(71L))
        model.act(this, SaleReturnAction.QuantityTextChange("1,5"))
        model.act(this, SaleReturnAction.QuantityConfirm)
        assertEquals(
            ReturnQuantityError.AboveRemaining(QuantityMilli(1_250L)),
            model.shown.sheet?.error,
        )

        model.act(this, SaleReturnAction.QuantityTextChange("0,5"))
        model.act(this, SaleReturnAction.QuantityConfirm)
        assertNull(model.shown.sheet)
        assertEquals(QuantityMilli(500L), model.shown.lines[1].quantity)
        assertEquals(Money(2_000L), model.shown.total)
    }

    @Test
    fun `nothing chosen is not registered and says so`() = runTest(dispatcher) {
        val model = viewModel()

        model.act(this, SaleReturnAction.Register)

        assertTrue(model.shown.noneSelected)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `a refund leaves from the sale account and keeps what does not restock`() =
        runTest(dispatcher) {
            val model = viewModel()
            model.act(this, SaleReturnAction.Increment(70L))
            model.act(this, SaleReturnAction.RestockChange(70L, restock = false))
            model.act(this, SaleReturnAction.NoteChange("  Llegó rota  "))

            assertEquals(6L, model.shown.accountId)
            model.act(this, SaleReturnAction.Register)

            assertEquals(
                SaleReturnRequest(
                    saleId = 7L,
                    lines = listOf(ReturnLineRequest(70L, QuantityMilli(1_000L), restock = false)),
                    settlement = ReturnSettlement.Refund(6L),
                    note = "Llegó rota",
                ),
                requests.single(),
            )
            assertEquals(Money(3_333L), model.shown.registered)
        }

    @Test
    fun `no account is also a choice`() = runTest(dispatcher) {
        val model = viewModel()
        model.act(this, SaleReturnAction.Increment(70L))

        model.act(this, SaleReturnAction.AccountChange(null))
        model.act(this, SaleReturnAction.Register)

        assertEquals(ReturnSettlement.Refund(null), requests.single().settlement)
    }

    @Test
    fun `a credit balance without a customer asks for one`() = runTest(dispatcher) {
        val model = viewModel()
        model.act(this, SaleReturnAction.Increment(70L))
        model.act(this, SaleReturnAction.ResolutionChange(ReturnResolution.STORE_CREDIT))

        model.act(this, SaleReturnAction.Register)
        assertTrue(model.shown.needsCustomer)
        assertTrue(requests.isEmpty())

        customers[4L] = customer(4L, balance = 1_000L)
        model.act(this, SaleReturnAction.CustomerPicked(4L))
        assertEquals(Money(-2_333L), model.shown.customer?.balanceAfter)
        model.act(this, SaleReturnAction.Register)
        assertEquals(ReturnSettlement.StoreCredit(4L), requests.single().settlement)
    }

    @Test
    fun `a credit sale starts as a credit balance for its customer`() = runTest(dispatcher) {
        customers[4L] = customer(4L, balance = 15_000L)
        detail.value = detailOf(customerId = 4L, kind = PaymentMethodKind.ON_ACCOUNT)
        val model = viewModel()

        assertEquals(ReturnResolution.STORE_CREDIT, model.shown.resolution)
        assertTrue(model.shown.creditSale)
        assertTrue(model.shown.customerLocked)
        model.act(this, SaleReturnAction.Increment(70L))
        model.act(this, SaleReturnAction.Register)
        // El cliente de la venta va implícito.
        assertEquals(ReturnSettlement.StoreCredit(null), requests.single().settlement)
    }

    @Test
    fun `an exchange writes nothing and hands the choice to the new sale`() = runTest(dispatcher) {
        val model = viewModel()
        model.act(this, SaleReturnAction.Increment(70L))
        model.act(this, SaleReturnAction.ResolutionChange(ReturnResolution.EXCHANGE))

        model.act(this, SaleReturnAction.Register)

        assertTrue(requests.isEmpty())
        val exchange = requireNotNull(model.shown.exchange)
        assertEquals(7L, exchange.saleId)
        assertEquals(3_333L, exchange.credit)
        assertEquals(3L, exchange.creditMethodId)
        assertEquals(listOf(SaleExchangeLine(70L, 1_000L, true)), exchange.lines)
        model.act(this, SaleReturnAction.ExchangeShown)
        assertNull(model.shown.exchange)
    }

    @Test
    fun `a failure keeps the choice and can be retried`() = runTest(dispatcher) {
        result = RegisterSaleReturnResult.StorageFailed(StorageFailure.DISK_FULL)
        val model = viewModel()
        model.act(this, SaleReturnAction.Increment(70L))

        model.act(this, SaleReturnAction.Register)
        assertEquals(SaleReturnNotice.RegisterFailed(outOfSpace = true), model.shown.notice)
        assertEquals(QuantityMilli(1_000L), model.shown.lines.first().quantity)

        result = RegisterSaleReturnResult.Registered(9L, Money(3_333L))
        model.act(this, SaleReturnAction.Retry)
        assertEquals(2, requests.size)
        assertEquals(Money(3_333L), model.shown.registered)
    }

    @Test
    fun `the choice survives the process and leaving with it asks first`() = runTest(dispatcher) {
        val first = viewModel()
        first.act(this, SaleReturnAction.Increment(70L))
        first.act(this, SaleReturnAction.RestockChange(70L, restock = false))

        val again = viewModel()
        assertEquals(QuantityMilli(1_000L), again.shown.lines.first().quantity)
        assertFalse(again.shown.lines.first().restock)
        again.act(this, SaleReturnAction.Close)
        assertTrue(again.shown.discardAsked)
        assertFalse(again.closed.value)
        again.act(this, SaleReturnAction.ConfirmLeave)
        assertTrue(again.closed.value)
    }

    @Test
    fun `a sale of another month counts today`() = runTest(dispatcher) {
        val model = viewModel()
        assertNull(model.shown.countsOn)

        // 2026-12-01 15:00 UTC: martes 2026-12-01 en `Etc/GMT+5`.
        now = Instant.fromEpochMilliseconds(1_796_137_200_000L)
        val later = viewModel()
        assertEquals(BusinessDate(LocalDate(2026, 12, 1)), later.shown.countsOn)
    }

    @Test
    fun `everything returned leaves nothing to choose`() = runTest(dispatcher) {
        detail.value = detailOf(
            returned = mapOf(
                70L to LineAmounts(
                    QuantityMilli(3_000L),
                    Money(10_000L),
                    Money.ZERO,
                    Money(6_000L),
                ),
                71L to LineAmounts(QuantityMilli(1_250L), Money(5_000L), Money.ZERO, Money(3_000L)),
            ),
        )
        val model = viewModel()

        assertTrue(model.shown.nothingLeft)
    }

    private fun customer(id: Long, balance: Long) = Customer(
        id = id,
        contact = CustomerContact("Cliente $id", "cliente $id", null, null, null),
        priceListId = null,
        balance = Money(balance),
        pointsBalance = 0L,
        anonymizedAt = null,
        archivedAt = null,
        createdAt = at,
        updatedAt = at,
    )
}
