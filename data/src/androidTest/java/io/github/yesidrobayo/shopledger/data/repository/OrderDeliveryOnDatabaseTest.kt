package io.github.yesidrobayo.shopledger.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.transaction.RestoreLock
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerContact
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerDetails
import io.github.yesidrobayo.shopledger.domain.model.document.OrderStatus
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SalePaymentRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleRequest
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.PaymentMethodNames
import io.github.yesidrobayo.shopledger.domain.usecase.customer.RecordCustomerPaymentUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.document.OrderDeliveryResult
import io.github.yesidrobayo.shopledger.domain.usecase.document.OrderDepositResult
import io.github.yesidrobayo.shopledger.domain.usecase.document.OrderRequest
import io.github.yesidrobayo.shopledger.domain.usecase.document.PrepareOrderDeliveryUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.document.RecordOrderDepositUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.document.SaveOrderResult
import io.github.yesidrobayo.shopledger.domain.usecase.document.SaveOrderUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.money.MoneyLedgerWriter
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleResult
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleUseCase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * La entrega de un pedido con anticipo sobre Room, con sus guardas: el anticipo es un abono del
 * cliente, y la venta de la entrega lo consume a crédito con el envío en su ajuste, saca el stock y
 * deja el pedido entregado, todo en su transacción. El ejemplo de la regla, con datos inventados.
 */
@RunWith(AndroidJUnit4::class)
internal class OrderDeliveryOnDatabaseTest : RepositoryTest() {
    private val products by lazy { RoomProductRepository(database) }
    private val orders by lazy { RoomOrderRepository(database) }
    private val customers by lazy { RoomCustomerRepository(database) }
    private val customerLedger by lazy { RoomCustomerLedgerRepository(database) }
    private val settings by lazy { RoomAppSettingsRepository(database, RestoreLock { false }) }
    private val accounts by lazy { RoomMoneyAccountRepository(database) }
    private val moneyLedger by lazy { RoomMoneyLedgerRepository(database) }
    private val clock = Clock { AT }

    private val save by lazy {
        SaveOrderUseCase(
            transactions = runner,
            settings = settings,
            orders = orders,
            customers = customers,
            clock = clock,
            products = products,
            units = RoomUnitRepository(database),
            taxRates = RoomTaxRateRepository(database),
            systemRows = RoomSystemRowRepository(database),
        )
    }
    private val deposit by lazy {
        RecordOrderDepositUseCase(
            runner,
            orders,
            RecordCustomerPaymentUseCase(
                transactions = runner,
                settings = settings,
                customers = customers,
                ledger = customerLedger,
                accounts = accounts,
                money = MoneyLedgerWriter(accounts, moneyLedger),
                clock = clock,
            ),
        )
    }
    private val prepare by lazy { PrepareOrderDeliveryUseCase(runner, orders, products, customers) }
    private val register by lazy {
        RegisterSaleUseCase(
            transactions = runner,
            settings = settings,
            products = products,
            sales = RoomSaleRepository(database),
            payments = RoomSalePaymentRepository(database),
            units = RoomUnitRepository(database),
            systemRows = RoomSystemRowRepository(database),
            ledger = RoomStockLedgerRepository(database),
            methodNames = PaymentMethodNames { key -> "Initial ${key.lowercase()}" },
            taxRates = RoomTaxRateRepository(database),
            clock = clock,
            customers = customers,
            customerLedger = customerLedger,
            orders = orders,
        )
    }

    private suspend fun methodId(key: String): Long =
        count("SELECT id FROM payment_methods WHERE system_key = '$key'")

    @Test
    fun theExampleOfTheRuleDeliversWithTheDepositOnAccount() = runBlocking {
        val customer = commit {
            customers.insert(
                CustomerDetails(
                    CustomerContact("Cliente inventado", "cliente inventado", null, null, null),
                    null,
                ),
                AT,
            )
        }
        val soap = commit {
            val id = products.insert(newProduct("Jabón inventado"))
            RoomStockLedgerRepository(database).append(openingRow(id, units = 10))
            id
        }
        val saved = save(
            OrderRequest(
                listOf(SaleLineRequest(soap, QuantityMilli(8_000))),
                customerId = customer,
                shippingAmount = Money(1_500),
            ),
        )
        val orderId = (saved as SaveOrderResult.Saved).orderId

        assertTrue(deposit(orderId, Money(5_000), null) is OrderDepositResult.Recorded)
        assertEquals(Money(-5_000), requireNotNull(customers.findById(customer)).balance)

        val delivery = (prepare(orderId) as OrderDeliveryResult.Ready).delivery
        val result = register(
            SaleRequest(
                lines = delivery.lines,
                paymentMethodId = methodId("CASH"),
                payments = listOf(
                    SalePaymentRequest(methodId("ON_ACCOUNT"), delivery.credit),
                    SalePaymentRequest(methodId("CASH"), Money(16_500)),
                ),
                orderAdjustment = delivery.shippingAmount,
                customerId = customer,
                orderId = orderId,
            ),
        )

        val saleId = (result as RegisterSaleResult.Registered).saleId
        assertEquals(21_500L, count("SELECT total FROM sales WHERE id = $saleId"))
        assertEquals(orderId, count("SELECT order_id FROM sales WHERE id = $saleId"))
        assertEquals(saleId, count("SELECT delivered_sale_id FROM orders WHERE id = $orderId"))
        assertEquals(OrderStatus.DELIVERED, requireNotNull(orders.findById(orderId)).status)
        assertEquals(Money.ZERO, requireNotNull(customers.findById(customer)).balance)
        assertEquals(2_000L, count("SELECT on_hand_milli FROM products WHERE id = $soap"))
        // Entregado, ya no reserva nada.
        val reserving = "SELECT COUNT(*) FROM order_lines l JOIN orders o ON o.id = l.order_id " +
            "WHERE o.status IN ('PENDING', 'READY')"
        assertEquals(0L, count(reserving))
    }
}
