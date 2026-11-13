package io.github.yesidrobayo.shopledger.domain.rule.customer

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerEntrySource
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerLedgerEntry
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerLedgerEntryType
import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** El vencimiento: los abonos cubren primero los cargos más antiguos, y es solo una lectura. */
class CustomerDueRulesTest {
    @Test
    fun `the example of the rule leaves 2000 of the second charge, due on the 20th`() {
        val dues = CustomerDueRules.duesOf(
            listOf(
                charge(1, 5_000, "2026-11-10"),
                charge(2, 3_000, "2026-11-20"),
                entry(3, CustomerLedgerEntryType.PAYMENT, -6_000),
            ),
        )

        assertEquals(listOf(OpenCharge(2L, Money(2_000), date("2026-11-20"))), dues.openCharges)
        // Nada vencido hasta pasar el 20: ese día todavía no vence.
        assertNull(dues.overdueSince(date("2026-11-15")))
        assertNull(dues.overdueSince(date("2026-11-20")))
        assertEquals(date("2026-11-20"), dues.overdueSince(date("2026-11-21")))
        assertEquals(date("2026-11-20"), dues.nextDue(date("2026-11-20")))
        assertNull(dues.nextDue(date("2026-11-21")))
    }

    @Test
    fun `a voided charge and a voided payment leave the reckoning`() {
        val dues = CustomerDueRules.duesOf(
            listOf(
                charge(1, 5_000, "2026-11-01"),
                entry(2, CustomerLedgerEntryType.PAYMENT, -2_000),
                charge(3, 1_500, "2026-11-05"),
                entry(4, VOID, -5_000, CustomerEntrySource.Reverses(1)),
                entry(5, VOID, 2_000, CustomerEntrySource.Reverses(2)),
            ),
        )

        assertEquals(listOf(OpenCharge(3L, Money(1_500), date("2026-11-05"))), dues.openCharges)
    }

    @Test
    fun `money in favour covers the charges that come after`() {
        val dues = CustomerDueRules.duesOf(
            listOf(
                entry(1, CustomerLedgerEntryType.PAYMENT, -4_000),
                charge(2, 1_000, "2026-11-01"),
                charge(3, 5_000, null),
                entry(4, CustomerLedgerEntryType.ADJUSTMENT, -500),
            ),
        )

        // El anticipo cubre 1.000 y 3.000; el ajuste a la baja, 500 más del segundo.
        assertEquals(listOf(OpenCharge(3L, Money(1_500), null)), dues.openCharges)
        assertNull(dues.overdueSince(date("2026-12-31")))
        assertNull(dues.nextDue(date("2026-01-01")))
    }

    @Test
    fun `with nothing owed there is nothing open, and the order is the ledger's`() {
        val settled = CustomerDueRules.duesOf(
            listOf(
                charge(2, 1_000, "2026-11-01"),
                entry(1, CustomerLedgerEntryType.OPENING_BALANCE, -3_000),
                entry(3, CustomerLedgerEntryType.OPENING_BALANCE, 1_000),
            ),
        )

        assertEquals(emptyList<OpenCharge>(), settled.openCharges)
        assertEquals(emptyList<OpenCharge>(), CustomerDueRules.duesOf(emptyList()).openCharges)
    }

    @Test
    fun `a payment covers part of a charge and leaves the rest open`() {
        val dues = CustomerDueRules.duesOf(
            listOf(
                charge(1, 5_000, "2026-11-01"),
                charge(2, 3_000, "2026-11-03"),
                entry(3, CustomerLedgerEntryType.PAYMENT, -1_000),
            ),
        )

        assertEquals(
            listOf(
                OpenCharge(1L, Money(4_000), date("2026-11-01")),
                OpenCharge(2L, Money(3_000), date("2026-11-03")),
            ),
            dues.openCharges,
        )
        assertEquals(date("2026-11-01"), dues.overdueSince(date("2026-11-10")))
    }

    private fun charge(id: Long, amount: Long, due: String?): CustomerLedgerEntry =
        entry(id, CustomerLedgerEntryType.CREDIT_SALE, amount, CustomerEntrySource.Sale(id))
            .copy(dueDate = due?.let(::date))

    private fun entry(
        id: Long,
        type: CustomerLedgerEntryType,
        amount: Long,
        source: CustomerEntrySource = CustomerEntrySource.None,
    ): CustomerLedgerEntry = CustomerLedgerEntry(
        id = id,
        customerId = 1L,
        type = type,
        amount = Money(amount),
        balanceAfter = Money.ZERO,
        source = source,
        dueDate = null,
        note = null,
        occurredAt = AT,
        businessDate = date("2026-11-01"),
    )

    private fun date(text: String): BusinessDate = requireNotNull(BusinessDate.parse(text))

    private companion object {
        val AT: Instant = Instant.fromEpochMilliseconds(1_762_000_000_000L)
        val VOID = CustomerLedgerEntryType.ENTRY_VOID
    }
}
