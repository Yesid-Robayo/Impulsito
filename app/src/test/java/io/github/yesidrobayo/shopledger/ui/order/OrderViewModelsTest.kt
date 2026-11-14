package io.github.yesidrobayo.shopledger.ui.order

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.customer.Customer
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerContact
import io.github.yesidrobayo.shopledger.domain.model.document.DocumentLine
import io.github.yesidrobayo.shopledger.domain.model.document.NewDocumentLine
import io.github.yesidrobayo.shopledger.domain.model.document.Order
import io.github.yesidrobayo.shopledger.domain.model.document.OrderStatus
import io.github.yesidrobayo.shopledger.domain.model.region.NumberSymbols
import io.github.yesidrobayo.shopledger.domain.rule.customer.CustomerPaymentIssue
import io.github.yesidrobayo.shopledger.domain.rule.customer.PaymentAllocation
import io.github.yesidrobayo.shopledger.domain.usecase.customer.PaymentChoices
import io.github.yesidrobayo.shopledger.domain.usecase.customer.RecordCustomerPaymentResult
import io.github.yesidrobayo.shopledger.domain.usecase.document.OrderChangeResult
import io.github.yesidrobayo.shopledger.domain.usecase.document.OrderDelivery
import io.github.yesidrobayo.shopledger.domain.usecase.document.OrderDeliveryResult
import io.github.yesidrobayo.shopledger.domain.usecase.document.OrderDepositResult
import io.github.yesidrobayo.shopledger.domain.usecase.document.OrderLineView
import io.github.yesidrobayo.shopledger.domain.usecase.document.OrderRow
import io.github.yesidrobayo.shopledger.domain.usecase.document.OrderView
import io.github.yesidrobayo.shopledger.ui.sale.SaleNumberFormat
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * La lista y el detalle de los pedidos sin base: los chips, los vacíos, la entrega vencida, el
 * resumen con el anticipo, cambiar la preparación, entregar, cancelar con anticipo y registrarlo.
 * Nombres y montos inventados; los esperados escritos a mano.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OrderViewModelsTest {
    private val dispatcher = StandardTestDispatcher()
    private val orders = MutableStateFlow(
        listOf(order(1, OrderStatus.PENDING, YESTERDAY), order(2, OrderStatus.PENDING, null)),
    )
    private var listFails = false
    private val customer = MutableStateFlow<Customer?>(CUSTOMER)
    private var detailOrder = order(58, OrderStatus.READY, YESTERDAY)
    private val changes = mutableListOf<Pair<Long, Boolean>>()
    private val cancelled = mutableListOf<Long>()
    private val deposits = mutableListOf<Pair<Money, Long?>>()
    private var depositResult: OrderDepositResult = OrderDepositResult.Recorded(
        RecordCustomerPaymentResult.Recorded(1L, null, Money(-5_000L), ALLOCATION),
    )
    private var delivery: OrderDeliveryResult = OrderDeliveryResult.NothingToDeliver

    @Before
    fun useTestMain() = Dispatchers.setMain(dispatcher)

    @After
    fun resetTestMain() = Dispatchers.resetMain()

    // ---------------------------------------------------------------- lista

    @Test
    fun `the list starts on pending and marks an overdue delivery`() = runTest(dispatcher) {
        val model = listModel()
        val state = model.uiState.value
        assertEquals(OrderStatus.PENDING, state.filter)
        val items = (state.content as OrdersContent.Rows).items
        assertEquals(listOf(true, false), items.map { it.overdue })
        assertEquals("Ana Ríos", items.first().customerName)
    }

    @Test
    fun `a status with no orders says so, and no order at all invites the first`() =
        runTest(dispatcher) {
            val model = listModel()
            model.onAction(OrdersAction.SelectFilter(OrderStatus.DELIVERED))
            advanceUntilIdle()
            assertEquals(OrdersContent.FilterEmpty, model.uiState.value.content)

            orders.value = emptyList()
            model.onAction(OrdersAction.SelectFilter(OrderStatus.PENDING))
            advanceUntilIdle()
            assertEquals(OrdersContent.Empty, model.uiState.value.content)
        }

    @Test
    fun `a failed read can be retried`() = runTest(dispatcher) {
        listFails = true
        val model = listModel()
        assertEquals(OrdersContent.Failed, model.uiState.value.content)

        listFails = false
        model.onAction(OrdersAction.Retry)
        advanceUntilIdle()
        assertTrue(model.uiState.value.content is OrdersContent.Rows)
    }

    // ---------------------------------------------------------------- detalle

    @Test
    fun `the summary takes the customer credit as deposit up to the total`() = runTest(dispatcher) {
        customer.value = CUSTOMER.copy(balance = Money(-5_000L))
        val model = detailModel()

        val detail = ready(model)
        assertEquals(Money(20_000L), detail.products)
        assertEquals(Money(1_500L), detail.shipping)
        assertEquals(Money(5_000L), detail.deposit)
        assertEquals(Money(16_500L), detail.due)
        assertTrue(detail.overdue)
        assertEquals(QuantityMilli(2_000L), detail.lines.single().reserved)
        assertEquals(QuantityMilli(1_000L), detail.lines.single().short)

        customer.value = CUSTOMER.copy(balance = Money(-30_000L))
        advanceUntilIdle()
        assertEquals(Money(21_500L), ready(model).deposit)
        assertEquals(Money.ZERO, ready(model).due)
    }

    @Test
    fun `a delivered order shows no deposit and is not overdue`() = runTest(dispatcher) {
        customer.value = CUSTOMER.copy(balance = Money(-5_000L))
        detailOrder = order(58, OrderStatus.DELIVERED, YESTERDAY).copy(deliveredSaleId = 9L)
        val detail = ready(detailModel())
        assertEquals(Money.ZERO, detail.deposit)
        assertEquals(Money(21_500L), detail.due)
        assertFalse(detail.overdue)
        assertEquals(9L, detail.deliveredSaleId)
    }

    @Test
    fun `ready and back to pending change only the preparation`() = runTest(dispatcher) {
        val model = detailModel()
        model.act(this, OrderDetailAction.MarkReady)
        model.act(this, OrderDetailAction.BackToPending)
        assertEquals(listOf(58L to true, 58L to false), changes)
        assertNull(model.uiState.value.notice)
    }

    @Test
    fun `cancelling with credit names the customer, and cancelling says it is done`() =
        runTest(dispatcher) {
            customer.value = CUSTOMER.copy(balance = Money(-5_000L))
            val model = detailModel()
            model.act(this, OrderDetailAction.AskCancel)
            assertEquals(
                OrderDetailDialog.ConfirmCancel("Ana Ríos"),
                model.uiState.value.dialog,
            )
            model.act(this, OrderDetailAction.ConfirmCancel)
            assertEquals(listOf(58L), cancelled)
            assertNull(model.uiState.value.dialog)
            assertEquals(OrderDetailNotice.CANCELLED, model.uiState.value.notice)
        }

    @Test
    fun `cancelling without credit does not mention a deposit`() = runTest(dispatcher) {
        val model = detailModel()
        model.act(this, OrderDetailAction.AskCancel)
        assertEquals(OrderDetailDialog.ConfirmCancel(null), model.uiState.value.dialog)
    }

    @Test
    fun `delivering opens the sale only when the order can be delivered`() = runTest(dispatcher) {
        val model = detailModel()
        model.act(this, OrderDetailAction.Deliver)
        assertNull(model.uiState.value.toDeliver)
        assertEquals(OrderDetailNotice.DELIVER_FAILED, model.uiState.value.notice)

        delivery = OrderDeliveryResult.Ready(
            OrderDelivery(58L, CUSTOMER.id, null, emptyList(), Money.ZERO, 0, Money.ZERO),
        )
        model.act(this, OrderDetailAction.Deliver)
        assertEquals(58L, model.uiState.value.toDeliver)
        model.act(this, OrderDetailAction.DeliverShown)
        assertNull(model.uiState.value.toDeliver)
    }

    @Test
    fun `a deposit is checked, recorded and closes its sheet`() = runTest(dispatcher) {
        val model = detailModel()
        model.act(this, OrderDetailAction.OpenDeposit)
        model.act(this, OrderDetailAction.SaveDeposit)
        assertEquals(DepositError.AMOUNT_ZERO, model.uiState.value.deposit?.error)

        depositResult = OrderDepositResult.Rejected(
            RecordCustomerPaymentResult.InvalidAmount(CustomerPaymentIssue.AMOUNT_TOO_LARGE),
        )
        model.act(this, OrderDetailAction.DepositAmount("5000"))
        model.act(this, OrderDetailAction.SaveDeposit)
        assertEquals(DepositError.AMOUNT_TOO_HIGH, model.uiState.value.deposit?.error)

        depositResult = OrderDepositResult.Recorded(
            RecordCustomerPaymentResult.Recorded(1L, null, Money(-5_000L), ALLOCATION),
        )
        model.act(this, OrderDetailAction.SaveDeposit)
        assertEquals(listOf(Money(5_000L) to null, Money(5_000L) to null), deposits)
        assertNull(model.uiState.value.deposit)
        assertEquals(OrderDetailNotice.DEPOSIT_RECORDED, model.uiState.value.notice)
    }

    @Test
    fun `an order without customer does not open the deposit`() = runTest(dispatcher) {
        detailOrder = detailOrder.copy(customerId = null)
        val model = detailModel()
        model.act(this, OrderDetailAction.OpenDeposit)
        assertNull(model.uiState.value.deposit)
    }

    @Test
    fun `a shortage shows in the unit of the line, rounded up`() {
        assertEquals(QuantityMilli(1_000L), inLineUnit(QuantityMilli(500L), QuantityMilli(500L)))
        assertEquals(QuantityMilli(84L), inLineUnit(QuantityMilli(1_000L), QuantityMilli(12_000L)))
        assertEquals(QuantityMilli.ZERO, inLineUnit(QuantityMilli.ZERO, QuantityMilli(1_000L)))
    }

    // ---------------------------------------------------------------- dobles

    private fun TestScope.listModel(): OrdersViewModel {
        val model = OrdersViewModel(
            object : OrderListOperations {
                override fun list(status: OrderStatus): Flow<List<OrderRow>> = if (listFails) {
                    flow { error("lectura fallida de prueba") }
                } else {
                    orders.map { all ->
                        all.filter { it.status == status }.map { OrderRow(it, "Ana Ríos") }
                    }
                }

                override fun hasAny(): Flow<Boolean> = orders.map { it.isNotEmpty() }

                override fun today(): Flow<BusinessDate?> = flowOf(TODAY)
            },
        )
        backgroundScope.launch { model.uiState.collect {} }
        advanceUntilIdle()
        return model
    }

    private fun TestScope.detailModel(): OrderDetailViewModel {
        val symbols = NumberSymbols(decimalSeparator = ',', minusSign = '-', grouping = null)
        val model = OrderDetailViewModel(
            58L,
            object : OrderDetailOperations {
                override fun order(orderId: Long): Flow<OrderView?> = flowOf(
                    OrderView(
                        detailOrder,
                        listOf(OrderLineView(LINE, QuantityMilli(2_000L), QuantityMilli(1_000L))),
                        "Ana Ríos",
                    ),
                )

                override fun customer(customerId: Long): Flow<Customer?> = customer

                override fun units(): Flow<Map<Long, MeasureUnit>> = flowOf(emptyMap())

                override fun today(): Flow<BusinessDate?> = flowOf(TODAY)

                override fun paymentChoices(): Flow<PaymentChoices> = flowOf(PaymentChoices())

                override suspend fun setReady(orderId: Long, ready: Boolean): OrderChangeResult {
                    changes += orderId to ready
                    return OrderChangeResult.Changed
                }

                override suspend fun cancel(orderId: Long): OrderChangeResult {
                    cancelled += orderId
                    return OrderChangeResult.Changed
                }

                override suspend fun deposit(
                    orderId: Long,
                    amount: Money,
                    accountId: Long?,
                ): OrderDepositResult {
                    deposits += amount to accountId
                    return depositResult
                }

                override suspend fun delivery(orderId: Long): OrderDeliveryResult = delivery
            },
            SaleNumberFormat(symbols, 0, symbols, symbols),
        )
        backgroundScope.launch { model.uiState.collect {} }
        advanceUntilIdle()
        return model
    }

    private fun OrderDetailViewModel.act(scope: TestScope, action: OrderDetailAction) {
        onAction(action)
        scope.advanceUntilIdle()
    }

    private fun ready(model: OrderDetailViewModel): OrderDetail =
        (model.uiState.value.content as OrderDetailContent.Ready).order

    private companion object {
        val TODAY = BusinessDate(LocalDate(2026, 11, 2))
        val YESTERDAY = BusinessDate(LocalDate(2026, 11, 1))
        val CREATED = Instant.parse("2026-11-01T10:00:00Z")
        val ALLOCATION = PaymentAllocation(emptyList(), Money(5_000L))

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

        fun order(id: Long, status: OrderStatus, due: BusinessDate?) = Order(
            id = id,
            customerId = 30L,
            status = status,
            deliveryAddress = "Calle 8",
            deliveryNote = null,
            shippingAmount = Money(1_500L),
            dueDate = due,
            deliveredSaleId = null,
            total = Money(21_500L),
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
                unitPrice = Money(10_000L),
                lineDiscount = Money.ZERO,
                netAmount = Money(20_000L),
                taxRate = BasisPoints.ZERO,
                taxNameSnapshot = null,
                taxAmount = Money.ZERO,
                lineTotal = Money(20_000L),
                productNameSnapshot = "Arroz",
            ),
        )
    }
}
