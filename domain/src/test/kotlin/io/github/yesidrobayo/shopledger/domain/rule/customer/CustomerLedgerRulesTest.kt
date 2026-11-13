package io.github.yesidrobayo.shopledger.domain.rule.customer

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerEntryDraft
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerEntrySource as Src
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerLedgerEntry
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerLedgerEntryType
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerLedgerEntryType.ADJUSTMENT
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerLedgerEntryType.CREDIT_SALE
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerLedgerEntryType.ENTRY_VOID
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerLedgerEntryType.OPENING_BALANCE as OPENING
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerLedgerEntryType.PAYMENT
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerLedgerEntryType.RETURN_CREDIT
import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Signo, origen, nota y saldo de cada fila del libro del cliente. */
class CustomerLedgerRulesTest {
    @Test
    fun `each row adds its amount to the previous balance, also below zero`() {
        assertEquals(
            CustomerEntryCheck.Valid(Money(5_000)),
            next(Money.ZERO, draft(CREDIT_SALE, 5_000, Src.Sale(1))),
        )
        assertEquals(
            CustomerEntryCheck.Valid(Money(-1_500)),
            next(Money(4_500), draft(PAYMENT, -6_000)),
        )
        assertEquals(
            CustomerEntryCheck.Valid(Money(-500)),
            next(Money(-1_500), draft(OPENING, 1_000)),
        )
        assertEquals(
            CustomerEntryCheck.Valid(Money(-2_000)),
            next(
                Money(3_000),
                draft(RETURN_CREDIT, -5_000, Src.SaleReturn(4)),
            ),
        )
        assertEquals(
            CustomerEntryCheck.Valid(Money(5_500)),
            next(Money(-500), draft(ENTRY_VOID, 6_000, Src.Reverses(4))),
        )
        assertEquals(
            CustomerEntryCheck.Valid(Money(-100)),
            next(Money.ZERO, draft(ADJUSTMENT, -100, note = "Redondeo")),
        )
    }

    @Test
    fun `the sign of each type is fixed except where it corrects`() {
        val wrong = CustomerEntryCheck.Invalid(CustomerEntryIssue.WRONG_SIGN)
        assertEquals(wrong, next(Money.ZERO, draft(CREDIT_SALE, -1, Src.Sale(1))))
        assertEquals(wrong, next(Money.ZERO, draft(PAYMENT, 1)))
        assertEquals(
            wrong,
            next(Money.ZERO, draft(RETURN_CREDIT, 1, Src.SaleReturn(1))),
        )
        assertEquals(
            CustomerEntryCheck.Invalid(CustomerEntryIssue.ZERO_AMOUNT),
            next(Money.ZERO, draft(OPENING, 0)),
        )
    }

    @Test
    fun `each type has its own source and nothing else`() {
        val wrong = CustomerEntryCheck.Invalid(CustomerEntryIssue.WRONG_SOURCE)
        assertEquals(wrong, next(Money.ZERO, draft(CREDIT_SALE, 1)))
        assertEquals(wrong, next(Money.ZERO, draft(RETURN_CREDIT, -1)))
        assertEquals(wrong, next(Money.ZERO, draft(ENTRY_VOID, -1)))
        assertEquals(
            wrong,
            next(Money.ZERO, draft(PAYMENT, -1, Src.Sale(1))),
        )
        assertEquals(
            wrong,
            next(Money.ZERO, draft(OPENING, 1, Src.Reverses(1))),
        )
        assertEquals(
            wrong,
            next(
                Money.ZERO,
                draft(ADJUSTMENT, 1, Src.SaleReturn(1), note = "Nota"),
            ),
        )
    }

    @Test
    fun `an adjustment needs a note, and no note passes 200 characters`() {
        assertEquals(
            CustomerEntryCheck.Invalid(CustomerEntryIssue.NOTE_REQUIRED),
            next(Money.ZERO, draft(ADJUSTMENT, 100, note = "   ")),
        )
        assertEquals(
            CustomerEntryCheck.Invalid(CustomerEntryIssue.NOTE_REQUIRED),
            next(Money.ZERO, draft(ADJUSTMENT, 100)),
        )
        assertEquals(
            CustomerEntryCheck.Invalid(CustomerEntryIssue.NOTE_TOO_LONG),
            next(Money.ZERO, draft(PAYMENT, -100, note = "n".repeat(201))),
        )
        assertEquals(
            CustomerEntryCheck.Valid(Money(-100)),
            next(Money.ZERO, draft(PAYMENT, -100, note = "n".repeat(200))),
        )
        assertEquals("Abono", CustomerLedgerRules.note("  Abono "))
        assertNull(CustomerLedgerRules.note("  "))
        assertNull(CustomerLedgerRules.note(null))
    }

    @Test
    fun `a balance that would overflow is refused`() {
        assertEquals(
            CustomerEntryCheck.Invalid(CustomerEntryIssue.BALANCE_OVERFLOW),
            next(Money(Long.MAX_VALUE), draft(OPENING, 1)),
        )
    }

    @Test
    fun `a row is reversed with its opposite amount, and a reversal is not reversed`() {
        val payment = stored(7L, PAYMENT, -2_000)

        assertEquals(
            CustomerEntryDraft(3L, ENTRY_VOID, Money(2_000), Src.Reverses(7L), null, "Error"),
            CustomerLedgerRules.reversalOf(payment, "Error"),
        )
        assertNull(CustomerLedgerRules.reversalOf(stored(8L, ENTRY_VOID, 2_000), null))
    }

    @Test
    fun `only credit sales are always positive and payments always negative`() {
        val signs =
            CustomerLedgerEntryType.entries.associateWith { it.admits(true) to it.admits(false) }

        assertEquals(true to false, signs[CREDIT_SALE])
        assertEquals(false to true, signs[PAYMENT])
        assertEquals(false to true, signs[RETURN_CREDIT])
        assertEquals(true to true, signs[OPENING])
        assertEquals(true to true, signs[ADJUSTMENT])
        assertEquals(true to true, signs[ENTRY_VOID])
    }

    private fun next(previous: Money, draft: CustomerEntryDraft) =
        CustomerLedgerRules.next(previous, draft)

    private fun draft(
        type: CustomerLedgerEntryType,
        amount: Long,
        source: Src = Src.None,
        note: String? = null,
    ): CustomerEntryDraft = CustomerEntryDraft(3L, type, Money(amount), source, null, note)

    private fun stored(id: Long, type: CustomerLedgerEntryType, amount: Long) = CustomerLedgerEntry(
        id = id,
        customerId = 3L,
        type = type,
        amount = Money(amount),
        balanceAfter = Money(amount),
        source = Src.None,
        dueDate = requireNotNull(BusinessDate.parse("2026-11-30")),
        note = null,
        occurredAt = Instant.fromEpochMilliseconds(1_762_000_000_000L),
        businessDate = requireNotNull(BusinessDate.parse("2026-11-01")),
    )
}
