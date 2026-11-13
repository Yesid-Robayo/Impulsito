package io.github.yesidrobayo.shopledger.domain.usecase.returns

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerEntrySource
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerLedgerEntryType
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodKind
import io.github.yesidrobayo.shopledger.domain.model.returns.ReturnLineRequest
import io.github.yesidrobayo.shopledger.domain.model.returns.ReturnResolution
import io.github.yesidrobayo.shopledger.domain.model.returns.ReturnSettlement
import io.github.yesidrobayo.shopledger.domain.model.returns.SaleReturnRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SalePaymentRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleRequest
import io.github.yesidrobayo.shopledger.domain.usecase.sale.InjectedSaleFailure
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleResult
import io.github.yesidrobayo.shopledger.domain.usecase.sale.SaleWorld
import io.github.yesidrobayo.shopledger.domain.usecase.sale.method
import io.github.yesidrobayo.shopledger.domain.usecase.sale.product
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Devolver como saldo a favor: un `RETURN_CREDIT` en el libro del cliente de la venta, o del elegido
 * si la venta no tiene, que baja su saldo y que una venta a crédito consume después. Sin cliente no
 * hay saldo a favor. Los valores esperados están escritos a mano.
 */
class RegisterSaleReturnCreditTest {
    private val world = SaleWorld().apply {
        addMethod(method())
        addMethod(method(id = ON_ACCOUNT, kind = PaymentMethodKind.ON_ACCOUNT))
        addProduct(
            product(
                1,
                salePrice = 2_000,
                onHandMilli = 5_000,
                averageCost = 1_000,
                stockValue = 5_000,
            ),
        )
        addCustomer(CUSTOMER)
    }

    private suspend fun sell(customerId: Long?, onAccount: Boolean = false, units: Long = 2): Long {
        val total = Money(2_000 * units)
        val methodId = if (onAccount) ON_ACCOUNT else SaleWorld.CASH
        val request = SaleRequest(
            listOf(SaleLineRequest(1, QuantityMilli.ofUnits(units))),
            methodId,
            payments = listOf(SalePaymentRequest(methodId, total)),
            customerId = customerId,
        )
        return (world.registerSale()(request) as RegisterSaleResult.Registered).saleId
    }

    private suspend fun credit(saleId: Long, customerId: Long? = null): RegisterSaleReturnResult {
        val lineId = world.tables.lines.values.single { it.saleId == saleId }.id
        return world.registerSaleReturn()(
            SaleReturnRequest(
                saleId,
                listOf(ReturnLineRequest(lineId, QuantityMilli(1_000))),
                ReturnSettlement.StoreCredit(customerId),
            ),
        )
    }

    private fun balance(): Money = world.tables.customers.getValue(CUSTOMER).balance

    @Test
    fun theCustomerOfTheSaleGetsTheReturnAsCredit() = runTest {
        val saleId = sell(CUSTOMER)

        val result = credit(saleId)

        val returnId = (result as RegisterSaleReturnResult.Registered).saleReturnId
        val entry = world.tables.customerEntries.values.single()
        assertEquals(CustomerLedgerEntryType.RETURN_CREDIT, entry.type)
        assertEquals(Money(-2_000), entry.amount)
        assertEquals(CustomerEntrySource.SaleReturn(returnId), entry.source)
        assertEquals(Money(-2_000), balance())
        val header = world.tables.saleReturns.getValue(returnId).content
        assertEquals(ReturnResolution.STORE_CREDIT, header.resolution)
        assertNull(header.moneyAccountId)
        assertTrue(world.tables.moneyMovements.isEmpty())
    }

    @Test
    fun aCreditSaleReturnedThisWayLowersItsDebt() = runTest {
        val saleId = sell(CUSTOMER, onAccount = true)
        assertEquals(Money(4_000), balance())

        credit(saleId)

        assertEquals(Money(2_000), balance())
    }

    @Test
    fun theCreditIsSpentByTheNextSaleOnAccount() = runTest {
        credit(sell(CUSTOMER))
        assertEquals(Money(-2_000), balance())

        sell(CUSTOMER, onAccount = true, units = 1)

        assertEquals(Money.ZERO, balance())
    }

    @Test
    fun aSaleWithoutCustomerNeedsOneChosen() = runTest {
        val saleId = sell(customerId = null)

        assertEquals(RegisterSaleReturnResult.CustomerRequired, credit(saleId))
        assertTrue(world.tables.saleReturns.isEmpty())

        credit(saleId, customerId = CUSTOMER)
        assertEquals(Money(-2_000), balance())
    }

    @Test
    fun anotherCustomerThanTheSalesIsRejected() = runTest {
        world.addCustomer(OTHER)
        val saleId = sell(CUSTOMER)

        assertEquals(RegisterSaleReturnResult.CustomerNotOfSale, credit(saleId, customerId = OTHER))
        // El mismo cliente de la venta, escrito de nuevo, sí vale.
        credit(saleId, customerId = CUSTOMER)
        assertEquals(Money(-2_000), balance())
    }

    @Test
    fun anArchivedAnonymizedOrMissingCustomerGetsNoCredit() = runTest {
        world.addCustomer(ARCHIVED, archived = true)
        world.addCustomer(ANONYMIZED, anonymized = true)
        val saleId = sell(customerId = null)
        val before = world.tables

        for (customerId in listOf(ARCHIVED, ANONYMIZED, 99L)) {
            assertEquals(
                RegisterSaleReturnResult.CustomerUnavailable,
                credit(saleId, customerId = customerId),
            )
        }
        assertEquals(before, world.tables)
    }

    @Test
    fun aFreeReturnWritesNoCredit() = runTest {
        val request = SaleRequest(
            listOf(SaleLineRequest(1, QuantityMilli.ofUnits(1), unitPrice = Money.ZERO)),
            SaleWorld.CASH,
            customerId = CUSTOMER,
        )
        val saleId = (world.registerSale()(request) as RegisterSaleResult.Registered).saleId

        val result = credit(saleId)

        assertTrue(result is RegisterSaleReturnResult.Registered)
        assertTrue(world.tables.customerEntries.isEmpty())
    }

    @Test
    fun aBalanceThatDoesNotFitRejectsAsADamagedLedger() = runTest {
        val saleId = sell(CUSTOMER, onAccount = true)
        // Un saldo que ya está en el mínimo de Long no puede bajar más.
        world.arrange {
            val seeded = it.customerEntries.values.single()
            it.copy(
                customerEntries = it.customerEntries +
                    (seeded.id to seeded.copy(balanceAfter = Money(Long.MIN_VALUE + 1))),
            )
        }
        val before = world.tables

        assertEquals(RegisterSaleReturnResult.LedgerDamaged, credit(saleId))
        assertEquals(before, world.tables)
    }

    @Test
    fun aFailureInAnyWriteLeavesNoCredit() = runTest {
        val saleId = sell(CUSTOMER)
        val before = world.tables
        var failures = 0
        for (failAt in 1..30) {
            world.failAtWrite = failAt
            val attempt = runCatching { credit(saleId) }
            if (attempt.isSuccess) break
            assertTrue("Escritura $failAt", attempt.exceptionOrNull() is InjectedSaleFailure)
            assertEquals(before, world.tables)
            failures++
        }
        // La cabecera y su línea con sus ids, la entrada al stock con el producto, y el saldo a
        // favor con el cliente.
        assertEquals(10, failures)
        assertEquals(Money(-2_000), balance())
    }

    private companion object {
        const val ON_ACCOUNT = 2L
        const val CUSTOMER = 4L
        const val OTHER = 5L
        const val ARCHIVED = 6L
        const val ANONYMIZED = 7L
    }
}
