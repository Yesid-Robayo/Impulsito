package io.github.yesidrobayo.shopledger.domain.usecase.payable

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.VoidReason
import io.github.yesidrobayo.shopledger.domain.model.payable.SupplierLedgerEntryType
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseRequest
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException
import io.github.yesidrobayo.shopledger.domain.testing.FixedClock
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.RegisterPurchaseResult
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.TODAY
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.VoidPurchaseResult
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.atUnitCost
import io.github.yesidrobayo.shopledger.domain.usecase.sale.InjectedSaleFailure
import io.github.yesidrobayo.shopledger.domain.usecase.sale.SaleWorld
import io.github.yesidrobayo.shopledger.domain.usecase.sale.product
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pagar a un proveedor por partes y anular un pago: cada pago es una fila nueva del libro, la
 * compra no se edita, y anular compensa una sola vez. Los valores son los ejemplos del pago a
 * proveedor, escritos a mano. Montos en unidad menor de una moneda cualquiera.
 */
class SupplierPaymentTest {
    private val world = SaleWorld().apply {
        addProduct(product(1))
        addSupplier(SUPPLIER, termsDays = 30)
    }

    private fun pay() = PaySupplierUseCase(
        world.transactions,
        world.settings,
        world.supplierRepository,
        world.supplierLedger,
        world.clock,
    )

    private fun voidPayment() = VoidSupplierPaymentUseCase(
        world.transactions,
        world.settings,
        world.supplierLedger,
        world.clock,
    )

    private suspend fun pay(amount: Long, date: BusinessDate? = null, note: String? = null) =
        pay()(SupplierPaymentRequest(SUPPLIER, Money(amount), date, note))

    private suspend fun paid(amount: Long): Long = (pay(amount) as PaySupplierResult.Paid).entryId

    /** La compra del ejemplo: 50000 con 20000 pagados deja 30000 por pagar. */
    private suspend fun owe30000(): Long {
        val result = world.registerPurchase()(
            PurchaseRequest(
                lines = listOf(atUnitCost(1, 1_000, cost = 50_000)),
                supplierId = SUPPLIER,
                amountPaid = Money(20_000),
            ),
        )
        return (result as RegisterPurchaseResult.Registered).purchaseId
    }

    private fun entries() = world.tables.supplierEntries.values.sortedBy {
        it.id
    }.map { it.content }

    private fun balance() = world.tables.suppliers.getValue(SUPPLIER).balance

    @Test
    fun `a payment of 10000 on 30000 owed leaves 20000 and does not touch the purchase`() =
        runTest {
            val purchaseId = owe30000()
            val purchase = world.tables.purchases.getValue(purchaseId)

            val entryId = paid(10_000)

            val payment = world.tables.supplierEntries.getValue(entryId).content
            assertEquals(SupplierLedgerEntryType.PAYMENT, payment.type)
            assertEquals(Money(-10_000), payment.amount)
            assertEquals(Money(20_000), payment.balanceAfter)
            assertEquals(TODAY, payment.businessDate)
            assertEquals(Money(20_000), balance())
            assertEquals(purchase, world.tables.purchases.getValue(purchaseId))
            assertEquals(2, world.commits)
        }

    @Test
    fun `payments in parts add up and paying more leaves credit`() = runTest {
        owe30000()

        paid(10_000)
        paid(15_000)
        paid(8_000)

        assertEquals(
            listOf(Money(30_000), Money(20_000), Money(5_000), Money(-3_000)),
            entries().map { it.balanceAfter },
        )
        assertEquals(Money(-3_000), balance())
    }

    @Test
    fun `a supplier with nothing owed can be paid in advance`() = runTest {
        paid(4_000)

        assertEquals(Money(-4_000), balance())
    }

    @Test
    fun `an archived supplier with debt can still be paid`() = runTest {
        owe30000()
        world.arrange { tables ->
            val supplier = tables.suppliers.getValue(SUPPLIER)
            tables.copy(
                suppliers =
                    tables.suppliers + (SUPPLIER to supplier.copy(archivedAt = SaleWorld.EPOCH)),
            )
        }

        assertTrue(pay(30_000) is PaySupplierResult.Paid)
        assertEquals(Money.ZERO, balance())
    }

    @Test
    fun `a backdated payment keeps its date and takes its place at the end of the ledger`() =
        runTest {
            owe30000()

            paid(1_000)
            pay(2_000, date = date(2026, 10, 30))

            assertEquals(date(2026, 10, 30), entries().last().businessDate)
            assertEquals(Money(27_000), entries().last().balanceAfter)
        }

    @Test
    fun `a note is trimmed and a blank note is dropped`() = runTest {
        pay(1_000, note = "  Transferencia inventada  ")
        pay(1_000, note = "   ")

        assertEquals(listOf("Transferencia inventada", null), entries().map { it.note })
    }

    @Test
    fun `wrong payments are refused and write nothing`() = runTest {
        owe30000()
        val before = world.tables

        assertEquals(PaySupplierResult.AmountNotPositive, pay(0))
        assertEquals(PaySupplierResult.AmountNotPositive, pay(-1))
        assertEquals(PaySupplierResult.AmountTooLarge, pay(1_000_000_000_000_001))
        assertEquals(PaySupplierResult.NoteTooLong, pay(1, note = "x".repeat(201)))
        assertEquals(PaySupplierResult.FutureDate, pay(1, date = date(2026, 11, 3)))
        assertEquals(
            PaySupplierResult.SupplierNotFound,
            pay()(SupplierPaymentRequest(99, Money(1))),
        )
        assertEquals(before, world.tables)
    }

    @Test
    fun `the largest payment and the longest note are accepted`() = runTest {
        assertTrue(pay(1_000_000_000_000_000, note = "x".repeat(200)) is PaySupplierResult.Paid)
    }

    @Test
    fun `an unconfirmed currency refuses the payment`() = runTest {
        val world = SaleWorld(currencyConfirmed = false).apply { addSupplier(SUPPLIER) }
        val result = PaySupplierUseCase(
            world.transactions,
            world.settings,
            world.supplierRepository,
            world.supplierLedger,
            world.clock,
        )(SupplierPaymentRequest(SUPPLIER, Money(1_000)))

        assertEquals(PaySupplierResult.CurrencyNotConfirmed, result)
        assertTrue(world.tables.supplierEntries.isEmpty())
    }

    @Test
    fun `the first payment locks the currency`() = runTest {
        val world = SaleWorld(currencyLocked = false).apply { addSupplier(SUPPLIER) }
        PaySupplierUseCase(
            world.transactions,
            world.settings,
            world.supplierRepository,
            world.supplierLedger,
            world.clock,
        )(SupplierPaymentRequest(SUPPLIER, Money(1_000)))

        assertTrue(world.settings.settings.value.regional.currency.lockedAt != null)
    }

    @Test
    fun `an unknown time zone refuses the payment and the void`() = runTest {
        val world = SaleWorld(zoneId = "Invented/Zone").apply { addSupplier(SUPPLIER) }
        val result = PaySupplierUseCase(
            world.transactions,
            world.settings,
            world.supplierRepository,
            world.supplierLedger,
            world.clock,
        )(SupplierPaymentRequest(SUPPLIER, Money(1_000)))
        val voided = VoidSupplierPaymentUseCase(
            world.transactions,
            world.settings,
            world.supplierLedger,
            world.clock,
        )(1)

        assertEquals(PaySupplierResult.UnknownTimeZone("Invented/Zone"), result)
        assertEquals(VoidSupplierPaymentResult.UnknownTimeZone("Invented/Zone"), voided)
    }

    @Test
    fun `a balance that does not fit is refused`() = runTest {
        paid(1_000_000_000_000_000)
        world.arrange { tables ->
            // Un libro dañado, como el de una copia: el saldo ya está en el borde.
            val entries = tables.supplierEntries.mapValues { (_, entry) ->
                entry.copy(
                    content = entry.content.copy(
                        amount = Money(Long.MIN_VALUE + 1),
                        balanceAfter = Money(Long.MIN_VALUE + 1),
                    ),
                )
            }
            tables.copy(supplierEntries = entries)
        }
        val before = world.tables

        assertEquals(PaySupplierResult.BalanceTooLarge, pay(2))
        assertEquals(before, world.tables)
    }

    @Test
    fun `a failure at any write leaves nothing behind`() = runTest {
        owe30000()
        val before = world.tables
        // La fila con su id y el saldo del proveedor.
        for (failAt in 1..3) {
            world.failAtWrite = failAt
            val failed = runCatching { pay(10_000) }
            assertTrue("Escritura $failAt", failed.exceptionOrNull() is InjectedSaleFailure)
            assertEquals(before, world.tables)
        }
        world.failAtWrite = null
        assertTrue(pay(10_000) is PaySupplierResult.Paid)
    }

    @Test
    fun `a storage failure comes back as a result`() = runTest {
        world.failAtWrite = 1
        world.failure = { StorageFailureException(StorageFailure.DISK_FULL) }

        assertEquals(PaySupplierResult.StorageFailed(StorageFailure.DISK_FULL), pay(1_000))
        world.failAtWrite = null
        val entryId = paid(1_000)
        world.failAtWrite = 1
        assertEquals(
            VoidSupplierPaymentResult.StorageFailed(StorageFailure.DISK_FULL),
            voidPayment()(entryId),
        )
    }

    @Test
    fun `voiding a wrong payment compensates it once`() = runTest {
        owe30000()
        val entryId = paid(10_000)
        world.clock = FixedClock.atEpochMillis(SaleWorld.NOW_MILLIS + DAY_MILLIS)

        assertEquals(VoidSupplierPaymentResult.Voided, voidPayment()(entryId))
        assertEquals(VoidSupplierPaymentResult.AlreadyVoided, voidPayment()(entryId))

        val compensation = entries().last()
        assertEquals(SupplierLedgerEntryType.ENTRY_VOID, compensation.type)
        assertEquals(Money(10_000), compensation.amount)
        assertEquals(entryId, compensation.reversesEntryId)
        assertEquals(date(2026, 11, 3), compensation.businessDate)
        assertEquals(Money(30_000), balance())
        assertEquals(3, entries().size)
    }

    @Test
    fun `only a payment is voided here`() = runTest {
        owe30000()
        val charge = world.tables.supplierEntries.keys.single()
        val entryId = paid(10_000)
        voidPayment()(entryId)
        val compensation = world.tables.supplierEntries.keys.max()
        val before = world.tables

        assertEquals(VoidSupplierPaymentResult.NotAPayment, voidPayment()(charge))
        assertEquals(VoidSupplierPaymentResult.NotAPayment, voidPayment()(compensation))
        assertEquals(VoidSupplierPaymentResult.PaymentNotFound, voidPayment()(99))
        assertEquals(before, world.tables)
    }

    @Test
    fun `a failure while voiding leaves the payment standing`() = runTest {
        val entryId = paid(10_000)
        val before = world.tables
        for (failAt in 1..3) {
            world.failAtWrite = failAt
            val failed = runCatching { voidPayment()(entryId) }
            assertTrue("Escritura $failAt", failed.exceptionOrNull() is InjectedSaleFailure)
            assertEquals(before, world.tables)
        }
    }

    @Test
    fun `a void that does not fit is refused`() = runTest {
        val entryId = paid(10_000)
        world.arrange { tables ->
            val entry = tables.supplierEntries.getValue(entryId)
            val damaged = entry.copy(
                content = entry.content.copy(balanceAfter = Money(Long.MAX_VALUE)),
            )
            tables.copy(supplierEntries = tables.supplierEntries + (entryId to damaged))
        }
        val before = world.tables

        assertEquals(VoidSupplierPaymentResult.BalanceTooLarge, voidPayment()(entryId))
        assertEquals(before, world.tables)
    }

    @Test
    fun `voiding the purchase after a payment leaves credit with the supplier`() = runTest {
        val purchaseId = owe30000()
        paid(10_000)

        assertEquals(
            VoidPurchaseResult.Voided,
            world.voidPurchase()(purchaseId, VoidReason.ENTRY_ERROR, null),
        )

        assertEquals(Money(-10_000), balance())
    }

    private fun date(year: Int, month: Int, day: Int) = BusinessDate(LocalDate(year, month, day))

    private companion object {
        const val SUPPLIER = 5L
        const val DAY_MILLIS = 86_400_000L
    }
}
