package io.github.yesidrobayo.shopledger.domain.rule.payable

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.payable.NewSupplierLedgerEntry
import io.github.yesidrobayo.shopledger.domain.model.payable.SupplierLedgerEntry
import io.github.yesidrobayo.shopledger.domain.model.payable.SupplierLedgerEntryType
import io.github.yesidrobayo.shopledger.domain.model.payable.SupplierLedgerEntryType.ADJUSTMENT
import io.github.yesidrobayo.shopledger.domain.model.payable.SupplierLedgerEntryType.CREDIT_PURCHASE
import io.github.yesidrobayo.shopledger.domain.model.payable.SupplierLedgerEntryType.ENTRY_VOID
import io.github.yesidrobayo.shopledger.domain.model.payable.SupplierLedgerEntryType.OPENING_BALANCE
import io.github.yesidrobayo.shopledger.domain.model.payable.SupplierLedgerEntryType.PAYMENT
import io.github.yesidrobayo.shopledger.domain.model.payable.SupplierLedgerEntryType.RETURN_CREDIT
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lo abierto con un proveedor y sus vencimientos: los pagos cubren los cargos más antiguos primero,
 * y lo vencido se mide con la fecha de negocio. Los valores esperados están escritos a mano.
 */
class SupplierDebtsTest {
    @Test
    fun `a payment covers the oldest charge first`() {
        // El ejemplo de los vencimientos: 5000 al 10, 3000 al 20 y un pago de 6000.
        val debt = debtOf(
            row(1, CREDIT_PURCHASE, 5_000, due = nov(10), purchaseId = 7),
            row(2, CREDIT_PURCHASE, 3_000, due = nov(20), purchaseId = 8),
            row(3, PAYMENT, -6_000),
        )

        assertEquals(listOf(OpenCharge(2, 8, nov(20), Money(2_000))), debt.openCharges)
        assertEquals(Money(2_000), debt.owed)
        assertEquals(Money.ZERO, debt.creditInFavor)
        assertTrue(debt.overdue(nov(15)).isEmpty())
        assertTrue(debt.overdue(nov(20)).isEmpty())
        assertEquals(nov(20), debt.overdueSince(nov(21)))
        assertEquals(Money(2_000), debt.overdueAmount(nov(21)))
        assertEquals(nov(20), debt.nextDue(nov(15)))
        assertEquals(nov(20), debt.nextDue(nov(20)))
        assertNull(debt.nextDue(nov(21)))
    }

    @Test
    fun `overdue starts at the oldest open due date`() {
        val debt = debtOf(
            row(1, CREDIT_PURCHASE, 5_000, due = nov(10)),
            row(2, CREDIT_PURCHASE, 3_000, due = nov(5)),
            row(3, CREDIT_PURCHASE, 1_000, due = nov(30)),
        )

        assertEquals(nov(5), debt.overdueSince(nov(12)))
        assertEquals(Money(8_000), debt.overdueAmount(nov(12)))
        assertEquals(nov(30), debt.nextDue(nov(12)))
        assertEquals(Money(3_000), debt.dueBetween(nov(1), nov(7)))
        assertEquals(Money(1_000), debt.dueBetween(nov(12), nov(30)))
        assertEquals(Money.ZERO, debt.dueBetween(nov(11), nov(29)))
    }

    @Test
    fun `a charge without due date never becomes overdue`() {
        val debt = debtOf(row(1, CREDIT_PURCHASE, 5_000, due = null))

        assertTrue(debt.overdue(nov(30)).isEmpty())
        assertNull(debt.overdueSince(nov(30)))
        assertNull(debt.nextDue(nov(1)))
        assertEquals(Money.ZERO, debt.dueBetween(nov(1), nov(30)))
        assertEquals(Money.ZERO, debt.overdueAmount(nov(30)))
        assertEquals(Money(5_000), debt.owed)
    }

    @Test
    fun `a voided charge and its compensation do not count`() {
        // El ejemplo de la anulación: cargo de 30000, pago de 10000 y anulación del cargo.
        val debt = debtOf(
            row(1, CREDIT_PURCHASE, 30_000, due = nov(10)),
            row(2, PAYMENT, -10_000),
            row(3, ENTRY_VOID, -30_000, reverses = 1),
        )

        assertTrue(debt.openCharges.isEmpty())
        assertEquals(Money(10_000), debt.creditInFavor)
        assertEquals(Money.ZERO, debt.owed)
    }

    @Test
    fun `a voided payment opens the charge again`() {
        val debt = debtOf(
            row(1, CREDIT_PURCHASE, 30_000, due = nov(10)),
            row(2, PAYMENT, -10_000),
            row(3, ENTRY_VOID, 10_000, reverses = 2),
        )

        assertEquals(listOf(OpenCharge(1, null, nov(10), Money(30_000))), debt.openCharges)
    }

    @Test
    fun `every reducing row covers charges and every increasing row is a charge`() {
        val debt = debtOf(
            row(1, OPENING_BALANCE, 4_000),
            row(2, ADJUSTMENT, 1_000),
            row(3, RETURN_CREDIT, -2_000),
            row(4, ADJUSTMENT, -1_500),
            row(5, OPENING_BALANCE, -500),
        )

        assertEquals(listOf(OpenCharge(2, null, null, Money(1_000))), debt.openCharges)
    }

    @Test
    fun `an exact payment closes everything`() {
        val debt = debtOf(row(1, CREDIT_PURCHASE, 5_000), row(2, PAYMENT, -5_000))

        assertTrue(debt.openCharges.isEmpty())
        assertEquals(Money.ZERO, debt.creditInFavor)
    }

    @Test
    fun `the ledger order rules, not the input order`() {
        val debt = debtOf(
            row(2, CREDIT_PURCHASE, 3_000, due = nov(20)),
            row(3, PAYMENT, -4_000),
            row(1, CREDIT_PURCHASE, 5_000, due = nov(10)),
        )

        assertEquals(
            listOf(
                OpenCharge(1, null, nov(10), Money(1_000)),
                OpenCharge(2, null, nov(20), Money(3_000)),
            ),
            debt.openCharges,
        )
    }

    @Test
    fun `sums that do not fit give no answer`() {
        assertNull(
            SupplierDebts.of(
                listOf(
                    row(1, PAYMENT, Long.MIN_VALUE + 1),
                    row(2, PAYMENT, -2),
                ),
            ),
        )
    }

    @Test
    fun `an empty ledger owes nothing`() {
        val debt = debtOf()

        assertTrue(debt.openCharges.isEmpty())
        assertEquals(Money.ZERO, debt.creditInFavor)
    }

    private fun debtOf(vararg rows: SupplierLedgerEntry): SupplierDebt =
        requireNotNull(SupplierDebts.of(rows.toList()))

    private fun row(
        id: Long,
        type: SupplierLedgerEntryType,
        amount: Long,
        due: BusinessDate? = null,
        purchaseId: Long? = null,
        reverses: Long? = null,
    ) = SupplierLedgerEntry(
        id = id,
        content = NewSupplierLedgerEntry(
            supplierId = 1,
            type = type,
            amount = Money(amount),
            balanceAfter = Money(amount),
            purchaseId = purchaseId,
            reversesEntryId = reverses,
            dueDate = due,
            occurredAt = AT,
            businessDate = nov(1),
            createdAt = AT,
        ),
    )

    private fun nov(day: Int) = BusinessDate(LocalDate(2026, 11, day))

    private companion object {
        val AT: Instant = Instant.fromEpochMilliseconds(1_793_631_600_000L)
    }
}
