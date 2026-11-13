package io.github.yesidrobayo.shopledger.domain.rule.payable

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.payable.NewSupplierLedgerEntry
import io.github.yesidrobayo.shopledger.domain.model.payable.SupplierLedgerEntry
import io.github.yesidrobayo.shopledger.domain.model.payable.SupplierLedgerEntryType
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Las reglas del libro del proveedor, con los ejemplos de la regla de la compra a plazo y los
 * bordes. Montos en unidad menor de una moneda cualquiera; los valores esperados están a mano.
 */
class SupplierLedgerRulesTest {
    @Test
    fun `a purchase of 50000 with 20000 paid leaves 30000 owed`() {
        assertEquals(
            PurchaseCredit.OnCredit(Money(30_000)),
            SupplierLedgerRules.creditOf(Money(50_000), Money(20_000)),
        )
    }

    @Test
    fun `nothing paid leaves the whole total owed`() {
        assertEquals(
            PurchaseCredit.OnCredit(Money(50_000)),
            SupplierLedgerRules.creditOf(Money(50_000), Money.ZERO),
        )
    }

    @Test
    fun `paying the total leaves nothing owed`() {
        assertEquals(
            PurchaseCredit.PaidInFull,
            SupplierLedgerRules.creditOf(Money(50_000), Money(50_000)),
        )
        assertEquals(
            PurchaseCredit.PaidInFull,
            SupplierLedgerRules.creditOf(Money.ZERO, Money.ZERO),
        )
    }

    @Test
    fun `paying more than the total or a negative amount is out of range`() {
        assertEquals(
            PurchaseCredit.OutOfRange,
            SupplierLedgerRules.creditOf(Money(50_000), Money(50_001)),
        )
        assertEquals(
            PurchaseCredit.OutOfRange,
            SupplierLedgerRules.creditOf(Money(50_000), Money(-1)),
        )
    }

    @Test
    fun `thirty days of terms propose a due date thirty days later`() {
        assertEquals(
            date(2026, 12, 2),
            SupplierLedgerRules.proposedDueDate(date(2026, 11, 2), termsDays = 30),
        )
        assertEquals(
            date(2026, 11, 2),
            SupplierLedgerRules.proposedDueDate(date(2026, 11, 2), termsDays = 0),
        )
    }

    @Test
    fun `a supplier without terms gets no proposed due date`() {
        assertNull(SupplierLedgerRules.proposedDueDate(date(2026, 11, 2), termsDays = null))
    }

    @Test
    fun `each type takes its sign and none takes zero`() {
        val positive = Money(1)
        val negative = Money(-1)
        val signs = mapOf(
            SupplierLedgerEntryType.CREDIT_PURCHASE to (true to false),
            SupplierLedgerEntryType.PAYMENT to (false to true),
            SupplierLedgerEntryType.RETURN_CREDIT to (false to true),
            SupplierLedgerEntryType.OPENING_BALANCE to (true to true),
            SupplierLedgerEntryType.ADJUSTMENT to (true to true),
            SupplierLedgerEntryType.ENTRY_VOID to (true to true),
        )
        assertEquals(SupplierLedgerEntryType.entries.toSet(), signs.keys)
        for ((type, allowed) in signs) {
            assertEquals(
                "$type positivo",
                allowed.first,
                SupplierLedgerRules.signFits(type, positive),
            )
            assertEquals(
                "$type negativo",
                allowed.second,
                SupplierLedgerRules.signFits(type, negative),
            )
            assertFalse("$type cero", SupplierLedgerRules.signFits(type, Money.ZERO))
        }
    }

    @Test
    fun `the balance chains from the previous row`() {
        // La secuencia de la regla: cargo de 30000, pago de 10000 y anulación del cargo.
        assertEquals(Money(30_000), SupplierLedgerRules.balanceAfter(null, Money(30_000)))
        assertEquals(Money(20_000), SupplierLedgerRules.balanceAfter(Money(30_000), Money(-10_000)))
        assertEquals(
            Money(-10_000),
            SupplierLedgerRules.balanceAfter(Money(20_000), Money(-30_000)),
        )
    }

    @Test
    fun `a balance that does not fit is refused`() {
        assertNull(SupplierLedgerRules.balanceAfter(Money(Long.MAX_VALUE), Money(1)))
        assertNull(SupplierLedgerRules.balanceAfter(Money(Long.MIN_VALUE), Money(-1)))
    }

    @Test
    fun `a charge is compensated once with the opposite amount`() {
        val charge = entry(SupplierLedgerEntryType.CREDIT_PURCHASE, 30_000)

        assertEquals(
            SupplierCompensation.Ready(Money(-30_000)),
            SupplierLedgerRules.compensationOf(charge, alreadyCompensated = false),
        )
        assertEquals(
            SupplierCompensation.AlreadyCompensated,
            SupplierLedgerRules.compensationOf(charge, alreadyCompensated = true),
        )
    }

    @Test
    fun `a payment is compensated back into the debt`() {
        assertEquals(
            SupplierCompensation.Ready(Money(10_000)),
            SupplierLedgerRules.compensationOf(
                entry(SupplierLedgerEntryType.PAYMENT, -10_000),
                alreadyCompensated = false,
            ),
        )
    }

    @Test
    fun `a compensation is never compensated`() {
        assertEquals(
            SupplierCompensation.NotCompensable,
            SupplierLedgerRules.compensationOf(
                entry(SupplierLedgerEntryType.ENTRY_VOID, -30_000),
                alreadyCompensated = false,
            ),
        )
    }

    private fun entry(type: SupplierLedgerEntryType, amount: Long) = SupplierLedgerEntry(
        id = 7,
        content = NewSupplierLedgerEntry(
            supplierId = 1,
            type = type,
            amount = Money(amount),
            balanceAfter = Money(amount),
            occurredAt = AT,
            businessDate = date(2026, 11, 2),
            createdAt = AT,
        ),
    )

    private fun date(year: Int, month: Int, day: Int) = BusinessDate(LocalDate(year, month, day))

    private companion object {
        val AT: Instant = Instant.fromEpochMilliseconds(1_793_631_600_000L)
    }
}
