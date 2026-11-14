package io.github.yesidrobayo.shopledger.domain.usecase.document

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductUsage
import io.github.yesidrobayo.shopledger.domain.model.document.OrderStatus
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodKind
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SalePaymentRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleRequest
import io.github.yesidrobayo.shopledger.domain.usecase.customer.RecordCustomerPaymentUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.money.MoneyLedgerWriter
import io.github.yesidrobayo.shopledger.domain.usecase.sale.InjectedSaleFailure
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleResult
import io.github.yesidrobayo.shopledger.domain.usecase.sale.SaleWorld
import io.github.yesidrobayo.shopledger.domain.usecase.sale.method
import io.github.yesidrobayo.shopledger.domain.usecase.sale.product
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Entregar un pedido con anticipo: el anticipo es un abono del cliente que le deja saldo a favor,
 * y la venta de la entrega lo consume a crédito, con el envío en el ajuste. Valores de la regla,
 * escritos a mano. Nombres y montos inventados.
 */
class OrderDeliveryTest {
    private val world = SaleWorld().apply {
        addMethod(method())
        addMethod(method(id = CREDIT, kind = PaymentMethodKind.ON_ACCOUNT))
        addProduct(product(1, salePrice = 2_500, onHandMilli = 10_000, averageCost = 900))
        addCustomer(CUSTOMER)
    }

    private val deposit
        get() = RecordOrderDepositUseCase(
            world.transactions,
            world.orderRepository,
            RecordCustomerPaymentUseCase(
                transactions = world.transactions,
                settings = world.settings,
                customers = world.customerRepository,
                ledger = world.customerLedger,
                accounts = world.moneyAccountRepository,
                money = MoneyLedgerWriter(world.moneyAccountRepository, world.moneyLedger),
                clock = world.clock,
            ),
        )

    private val prepare
        get() = PrepareOrderDeliveryUseCase(
            world.transactions,
            world.orderRepository,
            world.products,
            world.customerRepository,
        )

    private suspend fun order(customerId: Long? = CUSTOMER): Long {
        val request = OrderRequest(
            listOf(SaleLineRequest(1, QuantityMilli(8_000))),
            customerId = customerId,
            shippingAmount = Money(1_500),
        )
        return (world.saveOrder()(request) as SaveOrderResult.Saved).orderId
    }

    private fun balance(): Money = world.tables.customers.getValue(CUSTOMER).balance

    private suspend fun deliver(orderId: Long, delivery: OrderDelivery) = world.registerSale()(
        SaleRequest(
            lines = delivery.lines,
            paymentMethodId = SaleWorld.CASH,
            payments = listOf(
                SalePaymentRequest(CREDIT, delivery.credit),
                SalePaymentRequest(SaleWorld.CASH, Money(16_500)),
            ),
            orderAdjustment = delivery.shippingAmount,
            customerId = delivery.customerId,
            orderId = orderId,
        ),
    )

    @Test
    fun `the example of the rule delivers 21500 with 5000 on account and the balance back to 0`() =
        runTest {
            val id = order()

            val recorded = deposit(id, Money(5_000), moneyAccountId = null)

            assertTrue(recorded is OrderDepositResult.Recorded)
            assertEquals(Money(-5_000), balance())
            val delivery = (prepare(id) as OrderDeliveryResult.Ready).delivery
            assertEquals(Money(5_000), delivery.credit)
            assertEquals(Money(1_500), delivery.shippingAmount)
            assertEquals(
                listOf(
                    SaleLineRequest(1, QuantityMilli(8_000), Money(2_500), unitId = SaleWorld.UNIT),
                ),
                delivery.lines,
            )

            val result = deliver(id, delivery)

            val saleId = (result as RegisterSaleResult.Registered).saleId
            val sale = world.tables.sales.getValue(saleId).content
            assertEquals(Money(21_500), sale.total)
            assertEquals(Money(1_500), sale.orderAdjustment)
            assertEquals(id, sale.orderId)
            assertEquals(Money.ZERO, balance())
            val delivered = world.tables.orders.getValue(id)
            assertEquals(OrderStatus.DELIVERED, delivered.status)
            assertEquals(saleId, delivered.deliveredSaleId)
            // El stock sale al entregar, no antes.
            assertEquals(QuantityMilli(2_000), world.tables.products.getValue(1).onHand)
        }

    @Test
    fun `an order delivers only once and the stock leaves only with its sale`() = runTest {
        val id = order()
        deposit(id, Money(5_000), moneyAccountId = null)
        val delivery = (prepare(id) as OrderDeliveryResult.Ready).delivery
        assertEquals(QuantityMilli(10_000), world.tables.products.getValue(1).onHand)
        deliver(id, delivery)

        assertEquals(OrderDeliveryResult.NotOpen(OrderStatus.DELIVERED), prepare(id))
        assertEquals(RegisterSaleResult.OrderUnavailable, deliver(id, delivery))
        assertEquals(1, world.tables.sales.size)
        assertEquals(QuantityMilli(2_000), world.tables.products.getValue(1).onHand)
    }

    @Test
    fun `a failure in any write of the delivery leaves the order open and the stock alone`() =
        runTest {
            val id = order()
            deposit(id, Money(5_000), moneyAccountId = null)
            val delivery = (prepare(id) as OrderDeliveryResult.Ready).delivery
            var failAt = 1
            while (true) {
                world.failAtWrite = failAt
                val failed = runCatching { deliver(id, delivery) }.exceptionOrNull()
                if (failed !is InjectedSaleFailure) break
                assertEquals(OrderStatus.PENDING, world.tables.orders.getValue(id).status)
                assertEquals(QuantityMilli(10_000), world.tables.products.getValue(1).onHand)
                assertEquals(Money(-5_000), balance())
                failAt++
            }
            assertTrue(failAt > 1)
            assertEquals(OrderStatus.DELIVERED, world.tables.orders.getValue(id).status)
        }

    @Test
    fun `cancelling keeps the deposit as credit for the customer`() = runTest {
        val id = order()
        deposit(id, Money(5_000), moneyAccountId = null)

        CancelOrderUseCase(world.transactions, world.orderRepository, world.clock)(id)

        assertEquals(OrderStatus.CANCELLED, world.tables.orders.getValue(id).status)
        assertEquals(Money(-5_000), balance())
        assertEquals(OrderDepositResult.OrderNotOpen, deposit(id, Money(1_000), null))
    }

    @Test
    fun `a deposit needs a customer and an open order`() = runTest {
        val anonymous = order(customerId = null)

        assertEquals(OrderDepositResult.NeedsCustomer, deposit(anonymous, Money(1_000), null))
        assertEquals(OrderDepositResult.OrderNotOpen, deposit(99, Money(1_000), null))
        assertTrue(
            deposit(order(), Money.ZERO, null) is OrderDepositResult.Rejected,
        )
        assertEquals(OrderDeliveryResult.NotFound, prepare(99))
        assertEquals(Money.ZERO, (prepare(anonymous) as OrderDeliveryResult.Ready).delivery.credit)
    }

    @Test
    fun `archived products are left out and an order of only archived ones cannot be delivered`() =
        runTest {
            val id = order()
            world.addProduct(product(1, salePrice = 2_500, archived = true))

            assertEquals(OrderDeliveryResult.NothingToDeliver, prepare(id))
            val flows = ObserveOrdersUseCase(
                world.orderRepository,
                world.customerRepository,
                world.products,
            )
            assertEquals(1, flows.list(OrderStatus.PENDING).first().size)
        }

    @Test
    fun `a product that is now an input is left out and counted apart`() = runTest {
        world.addProduct(product(2, salePrice = 500, onHandMilli = 1_000))
        val request = OrderRequest(
            listOf(
                SaleLineRequest(1, QuantityMilli(1_000)),
                SaleLineRequest(2, QuantityMilli(1_000)),
            ),
            customerId = CUSTOMER,
        )
        val id = (world.saveOrder()(request) as SaveOrderResult.Saved).orderId
        world.addProduct(
            product(2, salePrice = 500, onHandMilli = 1_000, usage = ProductUsage.INPUT),
        )

        val delivery = (prepare(id) as OrderDeliveryResult.Ready).delivery

        assertEquals(0, delivery.archivedLines)
        assertEquals(1, delivery.notSoldLines)
        assertEquals(listOf(1L), delivery.lines.map { it.productId })

        world.addProduct(product(1, salePrice = 2_500, usage = ProductUsage.INPUT))
        assertEquals(OrderDeliveryResult.NothingToDeliver, prepare(id))
    }

    private companion object {
        const val CUSTOMER = 30L
        const val CREDIT = 9L
    }
}
