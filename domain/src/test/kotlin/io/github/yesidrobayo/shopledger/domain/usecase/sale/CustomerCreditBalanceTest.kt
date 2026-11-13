package io.github.yesidrobayo.shopledger.domain.usecase.sale

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.VoidReason
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerEntrySource
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerLedgerEntry
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerLedgerEntryType
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodKind
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SalePaymentRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleRequest
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * El saldo a favor y la anulación de una venta a crédito, con los ejemplos de la regla escritos a
 * mano. El saldo a favor es un saldo negativo en el libro del cliente, sin códigos ni tarjetas: una
 * venta a crédito lo consume, y anular la venta devuelve su cargo una sola vez.
 */
class CustomerCreditBalanceTest {
    private val world = SaleWorld().apply {
        addMethod(method())
        addMethod(method(id = ON_ACCOUNT, kind = PaymentMethodKind.ON_ACCOUNT))
        addProduct(product(PRODUCT, salePrice = 1_000, stockMode = ProductStockMode.UNTRACKED))
        addCustomer(CUSTOMER)
    }

    private fun sale(price: Long, vararg payments: Pair<Long, Long>) = SaleRequest(
        lines = listOf(SaleLineRequest(PRODUCT, QuantityMilli.ofUnits(1L), Money(price))),
        paymentMethodId = SaleWorld.CASH,
        payments = payments.map { (method, amount) -> SalePaymentRequest(method, Money(amount)) },
        customerId = CUSTOMER,
        creditDueDate = null,
    )

    private suspend fun register(request: SaleRequest): Long =
        (world.registerSale()(request) as RegisterSaleResult.Registered).saleId

    /** Una fila ya guardada en el libro del cliente, como la dejaría un abono o un cargo. */
    private fun arrangeEntry(
        id: Long,
        type: CustomerLedgerEntryType,
        amount: Long,
        balanceAfter: Long,
    ) = world.arrange { tables ->
        val entry = CustomerLedgerEntry(
            id = id,
            customerId = CUSTOMER,
            type = type,
            amount = Money(amount),
            balanceAfter = Money(balanceAfter),
            source = CustomerEntrySource.None,
            dueDate = null,
            note = null,
            occurredAt = AT,
            businessDate = DATE,
        )
        tables.copy(customerEntries = tables.customerEntries + (id to entry))
    }

    @Test
    fun aPrepaidCreditIsConsumedBySellingOnAccount() = runTest {
        // Saldo −1500, un anticipo sin deuda; una venta de 1000 a crédito lo deja en −500.
        arrangeEntry(90L, CustomerLedgerEntryType.PAYMENT, -1_500, -1_500)

        val saleId = register(sale(1_000, ON_ACCOUNT to 1_000))

        val charge = world.tables.customerEntries.values
            .single { it.type == CustomerLedgerEntryType.CREDIT_SALE }
        assertEquals(Money(1_000), charge.amount)
        assertEquals(Money(-500), charge.balanceAfter)
        assertEquals(CustomerEntrySource.Sale(saleId), charge.source)
        assertEquals(Money(-500), world.tables.customers.getValue(CUSTOMER).balance)
    }

    @Test
    fun voidingACreditSaleAfterAPaymentLeavesThePaymentAsCredit() = runTest {
        // Venta de 5000 a crédito, abono de 2000 y anulación de la venta: el saldo queda en −2000.
        val saleId = register(sale(5_000, ON_ACCOUNT to 5_000))
        arrangeEntry(90L, CustomerLedgerEntryType.PAYMENT, -2_000, 3_000)

        assertEquals(VoidSaleResult.Voided, world.voidSale()(saleId, VoidReason.ENTRY_ERROR, null))

        assertEquals(Money(-2_000), world.tables.customers.getValue(CUSTOMER).balance)
        val void = world.tables.customerEntries.values
            .single { it.type == CustomerLedgerEntryType.ENTRY_VOID }
        assertEquals(Money(-5_000), void.amount)
        assertEquals(Money(-2_000), void.balanceAfter)
    }

    @Test
    fun voidingTheSameSaleTwiceRevertsItsChargeOnlyOnce() = runTest {
        val saleId = register(sale(1_000, ON_ACCOUNT to 1_000))

        assertEquals(VoidSaleResult.Voided, world.voidSale()(saleId, VoidReason.ENTRY_ERROR, null))
        assertEquals(
            VoidSaleResult.AlreadyVoided,
            world.voidSale()(saleId, VoidReason.ENTRY_ERROR, null),
        )

        val voids = world.tables.customerEntries.values
            .filter { it.type == CustomerLedgerEntryType.ENTRY_VOID }
        assertEquals(1, voids.size)
        assertEquals(Money.ZERO, world.tables.customers.getValue(CUSTOMER).balance)
    }

    private companion object {
        const val PRODUCT = 1L
        const val ON_ACCOUNT = 5L
        const val CUSTOMER = 10L
        val AT = Instant.parse("2026-11-01T12:00:00Z")
        val DATE: BusinessDate = requireNotNull(BusinessDate.parse("2026-11-01"))
    }
}
