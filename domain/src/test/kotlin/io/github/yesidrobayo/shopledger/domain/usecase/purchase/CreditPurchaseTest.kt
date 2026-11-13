package io.github.yesidrobayo.shopledger.domain.usecase.purchase

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.DocumentStatus
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.VoidReason
import io.github.yesidrobayo.shopledger.domain.model.payable.NewSupplierLedgerEntry
import io.github.yesidrobayo.shopledger.domain.model.payable.SupplierLedgerEntryType
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseRequest
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.testing.FixedClock
import io.github.yesidrobayo.shopledger.domain.usecase.sale.InjectedSaleFailure
import io.github.yesidrobayo.shopledger.domain.usecase.sale.SaleWorld
import io.github.yesidrobayo.shopledger.domain.usecase.sale.product
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Comprar a plazo: lo pagado queda en la compra, lo que falta entra al libro del proveedor con su
 * vencimiento, y anular la compra compensa ese cargo una sola vez. Los valores son los ejemplos de
 * la compra a plazo y de su anulación, escritos a mano. Montos en unidad menor de una moneda
 * cualquiera.
 */
class CreditPurchaseTest {
    private val world = SaleWorld().apply {
        addProduct(product(1))
        addSupplier(SUPPLIER, termsDays = 30)
        addSupplier(NO_TERMS)
    }

    private suspend fun register(
        supplierId: Long? = SUPPLIER,
        paid: Long? = null,
        due: BusinessDate? = null,
        date: BusinessDate? = null,
    ): RegisterPurchaseResult = world.registerPurchase()(
        PurchaseRequest(
            lines = listOf(atUnitCost(1, 1_000, cost = 50_000)),
            supplierId = supplierId,
            date = date,
            amountPaid = paid?.let(::Money),
            dueDate = due,
        ),
    )

    private suspend fun registered(paid: Long?, supplierId: Long = SUPPLIER): Long =
        (register(supplierId, paid) as RegisterPurchaseResult.Registered).purchaseId

    private fun entries() = world.tables.supplierEntries.values.sortedBy {
        it.id
    }.map { it.content }

    private fun balanceOf(supplierId: Long) = world.tables.suppliers.getValue(supplierId).balance

    @Test
    fun `a purchase of 50000 with 20000 paid owes 30000 to its supplier`() = runTest {
        val purchaseId = registered(paid = 20_000)

        val purchase = world.tables.purchases.getValue(purchaseId).content
        assertEquals(Money(50_000), purchase.total)
        assertEquals(Money(20_000), purchase.amountPaid)
        assertEquals(date(2026, 12, 2), purchase.dueDate)
        val charge = entries().single()
        assertEquals(SupplierLedgerEntryType.CREDIT_PURCHASE, charge.type)
        assertEquals(Money(30_000), charge.amount)
        assertEquals(Money(30_000), charge.balanceAfter)
        assertEquals(purchaseId, charge.purchaseId)
        assertEquals(date(2026, 12, 2), charge.dueDate)
        assertEquals(TODAY, charge.businessDate)
        assertEquals(Money(30_000), balanceOf(SUPPLIER))
        assertEquals(1, world.commits)
    }

    @Test
    fun `a second credit purchase chains on the previous balance`() = runTest {
        registered(paid = 20_000)
        registered(paid = 45_000)

        assertEquals(listOf(Money(30_000), Money(35_000)), entries().map { it.balanceAfter })
        assertEquals(Money(35_000), balanceOf(SUPPLIER))
    }

    @Test
    fun `paying nothing owes the whole total`() = runTest {
        registered(paid = 0)

        assertEquals(Money(50_000), entries().single().amount)
        assertEquals(Money(50_000), balanceOf(SUPPLIER))
    }

    @Test
    fun `a purchase paid in full writes nothing in the supplier ledger, as before`() = runTest {
        val withoutAmount = registered(paid = null)
        val withTotal = registered(paid = 50_000)

        for (id in listOf(withoutAmount, withTotal)) {
            val purchase = world.tables.purchases.getValue(id).content
            assertEquals(Money(50_000), purchase.amountPaid)
            assertNull(purchase.dueDate)
        }
        assertTrue(world.tables.supplierEntries.isEmpty())
        assertEquals(Money.ZERO, balanceOf(SUPPLIER))
    }

    @Test
    fun `a due date written for a purchase paid in full is not kept`() = runTest {
        val id = (
            register(
                paid = 50_000,
                due = date(2026, 12, 20),
            ) as RegisterPurchaseResult.Registered
            )
            .purchaseId

        assertNull(world.tables.purchases.getValue(id).content.dueDate)
    }

    @Test
    fun `a credit purchase without supplier asks for the supplier and writes nothing`() = runTest {
        val before = world.tables

        assertEquals(
            RegisterPurchaseResult.SupplierRequiredForCredit,
            register(null, paid = 20_000),
        )
        assertEquals(before, world.tables)
    }

    @Test
    fun `an amount paid above the total or negative is refused`() = runTest {
        val before = world.tables

        assertEquals(RegisterPurchaseResult.AmountPaidOutOfRange, register(paid = 50_001))
        assertEquals(RegisterPurchaseResult.AmountPaidOutOfRange, register(paid = -1))
        assertEquals(before, world.tables)
    }

    @Test
    fun `the person can change the proposed due date`() = runTest {
        val id = (register(paid = 0, due = date(2026, 11, 20)) as RegisterPurchaseResult.Registered)
            .purchaseId

        assertEquals(date(2026, 11, 20), world.tables.purchases.getValue(id).content.dueDate)
        assertEquals(date(2026, 11, 20), entries().single().dueDate)
    }

    @Test
    fun `a due date on the purchase day is accepted and an earlier one refused`() = runTest {
        assertTrue(register(paid = 0, due = TODAY) is RegisterPurchaseResult.Registered)
        val before = world.tables

        assertEquals(
            RegisterPurchaseResult.DueDateBeforePurchase,
            register(paid = 0, due = date(2026, 11, 1)),
        )
        assertEquals(before, world.tables)
    }

    @Test
    fun `a backdated purchase takes the due date from its own date`() = runTest {
        register(paid = 0, date = date(2026, 10, 1))

        assertEquals(date(2026, 10, 31), entries().single().dueDate)
        assertEquals(date(2026, 10, 1), entries().single().businessDate)
    }

    @Test
    fun `a supplier without terms leaves the debt without due date`() = runTest {
        registered(paid = 10_000, supplierId = NO_TERMS)

        assertNull(entries().single().dueDate)
        assertEquals(Money(40_000), balanceOf(NO_TERMS))
    }

    @Test
    fun `a failure at any write leaves nothing behind`() = runTest {
        val before = world.tables
        // Cabecera con su id, la línea con su id, la fila del stock con su id y el producto, la
        // reposición del producto, y la fila del proveedor con su id y el saldo.
        val writes = 2 + 2 + 3 + 1 + 3
        for (failAt in 1..writes) {
            world.failAtWrite = failAt
            val failed = runCatching { register(paid = 20_000) }
            assertTrue("Escritura $failAt", failed.exceptionOrNull() is InjectedSaleFailure)
            assertEquals(before, world.tables)
        }
        world.failAtWrite = null
        assertTrue(register(paid = 20_000) is RegisterPurchaseResult.Registered)
    }

    @Test
    fun `a balance that does not fit is refused and nothing is written`() = runTest {
        seedEntry(Long.MAX_VALUE)
        val before = world.tables

        assertEquals(RegisterPurchaseResult.SupplierBalanceTooLarge, register(paid = 0))
        assertEquals(before, world.tables)
    }

    @Test
    fun `voiding a credit purchase compensates its charge once`() = runTest {
        val purchaseId = registered(paid = 20_000)
        world.clock = FixedClock.atEpochMillis(SaleWorld.NOW_MILLIS + 3 * DAY_MILLIS)

        assertEquals(
            VoidPurchaseResult.Voided,
            world.voidPurchase()(purchaseId, VoidReason.ENTRY_ERROR, null),
        )
        assertEquals(
            VoidPurchaseResult.AlreadyVoided,
            world.voidPurchase()(purchaseId, VoidReason.ENTRY_ERROR, null),
        )

        val (charge, compensation) = world.tables.supplierEntries.values.sortedBy { it.id }
        assertEquals(SupplierLedgerEntryType.ENTRY_VOID, compensation.content.type)
        assertEquals(Money(-30_000), compensation.content.amount)
        assertEquals(Money.ZERO, compensation.content.balanceAfter)
        assertEquals(charge.id, compensation.content.reversesEntryId)
        assertNull(compensation.content.purchaseId)
        // La compensación lleva la fecha de la anulación, no la de la compra.
        assertEquals(date(2026, 11, 5), compensation.content.businessDate)
        assertEquals(Money.ZERO, balanceOf(SUPPLIER))
        assertEquals(DocumentStatus.VOIDED, world.tables.purchases.getValue(purchaseId).status)
    }

    @Test
    fun `voiding after a later payment leaves credit with the supplier`() = runTest {
        // El ejemplo de la anulación: cargo de 30000, un pago posterior de 10000 deja 20000, y
        // anular la compra resta los 30000 del cargo: quedan −10000 a favor del negocio.
        val purchaseId = registered(paid = 20_000)
        seedEntry(-10_000, type = SupplierLedgerEntryType.PAYMENT)
        assertEquals(Money(20_000), balanceOf(SUPPLIER))

        assertEquals(
            VoidPurchaseResult.Voided,
            world.voidPurchase()(purchaseId, VoidReason.OTHER, null),
        )

        assertEquals(Money(-10_000), entries().last().balanceAfter)
        assertEquals(Money(-10_000), balanceOf(SUPPLIER))
        // El pago sigue vigente: nada lo compensa.
        val payment = world.tables.supplierEntries.values.single {
            it.content.type == SupplierLedgerEntryType.PAYMENT
        }
        assertTrue(
            world.tables.supplierEntries.values.none {
                it.content.reversesEntryId ==
                    payment.id
            },
        )
    }

    @Test
    fun `voiding a purchase paid in full writes nothing in the supplier ledger`() = runTest {
        val purchaseId = registered(paid = null)

        assertEquals(
            VoidPurchaseResult.Voided,
            world.voidPurchase()(purchaseId, VoidReason.OTHER, null),
        )
        assertTrue(world.tables.supplierEntries.isEmpty())
    }

    @Test
    fun `a failure while compensating the charge leaves the purchase active`() = runTest {
        val purchaseId = registered(paid = 20_000)
        val before = world.tables
        // La anulación: la compra, la fila del stock con su id y el producto, y la compensación
        // del proveedor con su id y el saldo.
        val writes = 1 + 3 + 3
        for (failAt in 1..writes) {
            world.failAtWrite = failAt
            val failed = runCatching { world.voidPurchase()(purchaseId, VoidReason.OTHER, null) }
            assertTrue("Escritura $failAt", failed.exceptionOrNull() is InjectedSaleFailure)
            assertEquals(before, world.tables)
        }
        world.failAtWrite = null
        assertEquals(
            VoidPurchaseResult.Voided,
            world.voidPurchase()(purchaseId, VoidReason.OTHER, null),
        )
    }

    @Test
    fun `a compensation that does not fit leaves the purchase active`() = runTest {
        val purchaseId = registered(paid = 20_000)
        // Dos pagos absurdos dejan el saldo tan abajo que restar el cargo no cabe.
        seedEntry(Long.MIN_VALUE, type = SupplierLedgerEntryType.PAYMENT)
        seedEntry(-1, type = SupplierLedgerEntryType.PAYMENT)
        val before = world.tables

        assertEquals(
            VoidPurchaseResult.LedgerDamaged,
            world.voidPurchase()(purchaseId, VoidReason.OTHER, null),
        )
        assertEquals(before, world.tables)
    }

    /** Agrega una fila al libro del proveedor, como lo haría otro caso de uso. */
    private suspend fun seedEntry(
        amount: Long,
        type: SupplierLedgerEntryType = SupplierLedgerEntryType.ADJUSTMENT,
    ) {
        world.transactions.write {
            val ledger = world.supplierLedger
            val previous = ledger.lastBalanceAfter(SUPPLIER) ?: Money.ZERO
            ledger.append(
                NewSupplierLedgerEntry(
                    supplierId = SUPPLIER,
                    type = type,
                    amount = Money(amount),
                    balanceAfter = Money(previous.minor + amount),
                    note = "Nota inventada",
                    occurredAt = NOW,
                    businessDate = TODAY,
                    createdAt = NOW,
                ),
            )
            TxResult.Commit(Unit)
        }
    }

    private fun date(year: Int, month: Int, day: Int) = BusinessDate(LocalDate(year, month, day))

    private companion object {
        const val SUPPLIER = 5L
        const val NO_TERMS = 6L
        const val DAY_MILLIS = 86_400_000L
    }
}
