package io.github.yesidrobayo.shopledger.domain.usecase.sale

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.VoidReason
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerEntrySource
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerLedgerEntryType
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodKind
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SalePaymentRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleRequest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Vender a crédito: el pago a crédito exige un cliente vigente, y lo pagado así entra como un solo
 * cargo en su libro, en la misma transacción que la venta. Anular la venta compensa el cargo. Los
 * ejemplos van escritos a mano.
 */
class RegisterSaleCreditTest {
    private val world = SaleWorld().apply {
        addMethod(method())
        addMethod(method(id = ON_ACCOUNT, kind = PaymentMethodKind.ON_ACCOUNT))
        addProduct(product(PRODUCT, salePrice = 10_000, stockMode = ProductStockMode.UNTRACKED))
        addCustomer(CUSTOMER)
    }

    private fun sale(
        vararg payments: Pair<Long, Long>,
        customerId: Long? = CUSTOMER,
        due: BusinessDate? = null,
    ) = SaleRequest(
        lines = listOf(SaleLineRequest(PRODUCT, QuantityMilli.ofUnits(1L), Money(10_000))),
        paymentMethodId = SaleWorld.CASH,
        payments = payments.map { (method, amount) -> SalePaymentRequest(method, Money(amount)) },
        customerId = customerId,
        creditDueDate = due,
    )

    private suspend fun register(request: SaleRequest): Long =
        (world.registerSale()(request) as RegisterSaleResult.Registered).saleId

    private suspend fun rejectionOf(request: SaleRequest): RegisterSaleResult {
        val result = world.registerSale()(request)
        assertEquals("Un rechazo no confirma nada", 0, world.commits)
        assertTrue(world.tables.sales.isEmpty())
        assertTrue(world.tables.payments.isEmpty())
        assertTrue(world.tables.customerEntries.isEmpty())
        return result
    }

    @Test
    fun theExampleOfTheBookChargesWhatWasPaidOnAccountToTheCustomer() = runTest {
        // Venta de 10000: 3000 en efectivo y 7000 a crédito, con vencimiento.
        val saleId = register(
            sale(SaleWorld.CASH to 3_000, ON_ACCOUNT to 7_000, due = DUE),
        )

        val sale = world.tables.sales.getValue(saleId)
        assertEquals(CUSTOMER, sale.content.customerId)
        val entry = world.tables.customerEntries.values.single()
        assertEquals(CustomerLedgerEntryType.CREDIT_SALE, entry.type)
        assertEquals(Money(7_000), entry.amount)
        assertEquals(Money(7_000), entry.balanceAfter)
        assertEquals(CustomerEntrySource.Sale(saleId), entry.source)
        assertEquals(DUE, entry.dueDate)
        assertEquals(sale.content.businessDate, entry.businessDate)
        assertEquals(Money(7_000), world.tables.customers.getValue(CUSTOMER).balance)
    }

    @Test
    fun theChargesOfASaleAddUpToItsOnAccountPayments() = runTest {
        // Los cargos de la venta suman lo pagado a crédito, aunque haya dos pagos así.
        world.addMethod(method(id = SECOND_ON_ACCOUNT, kind = PaymentMethodKind.ON_ACCOUNT))
        val saleId = register(sale(ON_ACCOUNT to 4_000, SECOND_ON_ACCOUNT to 6_000))

        val onAccount = world.tables.payments.values
            .filter { it.saleId == saleId && it.content.methodKind == PaymentMethodKind.ON_ACCOUNT }
            .sumOf { it.content.amount.minor }
        val charged = world.tables.customerEntries.values
            .filter { it.source == CustomerEntrySource.Sale(saleId) }
            .sumOf { it.amount.minor }
        assertEquals(10_000L, onAccount)
        assertEquals(onAccount, charged)
        assertNull(world.tables.customerEntries.values.single().dueDate)
    }

    @Test
    fun theNextChargeChainsItsBalanceOnTheBook() = runTest {
        register(sale(ON_ACCOUNT to 10_000))
        register(sale(SaleWorld.CASH to 5_000, ON_ACCOUNT to 5_000))

        val entries = world.tables.customerEntries.values.sortedBy { it.id }
        assertEquals(listOf(10_000L, 15_000L), entries.map { it.balanceAfter.minor })
        assertEquals(Money(15_000), world.tables.customers.getValue(CUSTOMER).balance)
    }

    @Test
    fun aSaleWithACustomerAndNoCreditWritesNoCharge() = runTest {
        val saleId = register(sale(SaleWorld.CASH to 10_000))

        assertEquals(CUSTOMER, world.tables.sales.getValue(saleId).content.customerId)
        assertTrue(world.tables.customerEntries.isEmpty())
        assertEquals(Money.ZERO, world.tables.customers.getValue(CUSTOMER).balance)
    }

    @Test
    fun withoutACustomerACreditPaymentIsRejected() = runTest {
        assertEquals(
            RegisterSaleResult.PaymentNeedsCustomer,
            rejectionOf(sale(ON_ACCOUNT to 10_000, customerId = null)),
        )
    }

    @Test
    fun aMissingArchivedOrAnonymizedCustomerIsRejected() = runTest {
        world.addCustomer(ARCHIVED, archived = true)
        world.addCustomer(ANONYMIZED, anonymized = true)

        for (customerId in listOf(MISSING, ARCHIVED, ANONYMIZED)) {
            assertEquals(
                RegisterSaleResult.CustomerUnavailable,
                rejectionOf(sale(SaleWorld.CASH to 10_000, customerId = customerId)),
            )
        }
    }

    @Test
    fun aDueDateBeforeTheSaleIsRejected() = runTest {
        assertEquals(
            RegisterSaleResult.CreditDueBeforeSale,
            rejectionOf(sale(ON_ACCOUNT to 10_000, due = PAST)),
        )
    }

    @Test
    fun aFailureAtAnyWriteLeavesNoSaleWithoutItsCharge() = runTest {
        val request = sale(SaleWorld.CASH to 3_000, ON_ACCOUNT to 7_000)
        // Cada escritura de la transacción falla una vez: la cabecera, la línea, los pagos, el
        // cargo y el saldo del cliente. Ninguna deja una venta sin su cargo.
        var failAt = 1
        while (true) {
            world.failAtWrite = failAt
            val outcome = runCatching { world.registerSale()(request) }
            if (outcome.isSuccess) break
            assertTrue("Escritura $failAt", outcome.exceptionOrNull() is InjectedSaleFailure)
            assertTrue(world.tables.sales.isEmpty())
            assertTrue(world.tables.payments.isEmpty())
            assertTrue(world.tables.customerEntries.isEmpty())
            assertEquals(Money.ZERO, world.tables.customers.getValue(CUSTOMER).balance)
            assertEquals(0L, world.tables.changeSeq)
            failAt++
        }
        // Las dos últimas escrituras son el cargo y el saldo del cliente.
        assertTrue("Se probaron $failAt escrituras", failAt > MIN_WRITES)
        assertEquals(1, world.tables.customerEntries.size)
    }

    @Test
    fun voidingTheSaleCompensatesItsChargeOnceAndKeepsWhatWasPaidAsCredit() = runTest {
        val saleId = register(sale(ON_ACCOUNT to 10_000))
        val charge = world.tables.customerEntries.values.single()
        // Un abono de 4000 que ya existía antes de anular.
        world.arrange { tables ->
            val payment = charge.copy(
                id = 99L,
                type = CustomerLedgerEntryType.PAYMENT,
                amount = Money(-4_000),
                balanceAfter = Money(6_000),
                source = CustomerEntrySource.None,
                dueDate = null,
            )
            tables.copy(customerEntries = tables.customerEntries + (99L to payment))
        }

        val result = world.voidSale()(saleId, VoidReason.CUSTOMER_RETURN, null)

        assertEquals(VoidSaleResult.Voided, result)
        val void = world.tables.customerEntries.values
            .single { it.type == CustomerLedgerEntryType.ENTRY_VOID }
        assertEquals(Money(-10_000), void.amount)
        assertEquals(Money(-4_000), void.balanceAfter)
        assertEquals(CustomerEntrySource.Reverses(charge.id), void.source)
        assertEquals(Money(-4_000), world.tables.customers.getValue(CUSTOMER).balance)
    }

    private companion object {
        const val PRODUCT = 1L
        const val ON_ACCOUNT = 5L
        const val SECOND_ON_ACCOUNT = 6L
        const val CUSTOMER = 10L
        const val ARCHIVED = 11L
        const val ANONYMIZED = 12L
        const val MISSING = 13L
        const val MIN_WRITES = 6
        val DUE: BusinessDate = requireNotNull(BusinessDate.parse("2099-12-31"))
        val PAST: BusinessDate = requireNotNull(BusinessDate.parse("2000-01-01"))
    }
}
