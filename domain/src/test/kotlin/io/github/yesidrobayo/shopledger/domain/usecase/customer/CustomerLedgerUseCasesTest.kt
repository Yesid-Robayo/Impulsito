package io.github.yesidrobayo.shopledger.domain.usecase.customer

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerEntryDraft
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerEntrySource
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerLedgerEntryType
import io.github.yesidrobayo.shopledger.domain.model.region.RecordTime
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.rule.customer.CustomerEntryIssue
import io.github.yesidrobayo.shopledger.domain.rule.customer.OpenCharge
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** El escritor del libro del cliente, el ajuste a mano y el extracto. */
class CustomerLedgerUseCasesTest {
    private val world = CustomerLedgerWorld()
    private val writer = CustomerLedgerWriter(world.ledger)
    private val adjust = AdjustCustomerBalanceUseCase(
        world.transactions,
        world.settings,
        world.customers,
        world.ledger,
        world.clock,
    )

    @Test
    fun `the rows of the rule leave the balances of its table`() = runTest {
        world.seedCustomer(ID)
        val balances = listOf(
            append(entry(CustomerLedgerEntryType.CREDIT_SALE, 5_000, CustomerEntrySource.Sale(1))),
            append(entry(CustomerLedgerEntryType.PAYMENT, -2_000)),
            append(entry(CustomerLedgerEntryType.CREDIT_SALE, 1_500, CustomerEntrySource.Sale(2))),
            append(entry(CustomerLedgerEntryType.PAYMENT, -6_000)),
            append(entry(CustomerLedgerEntryType.CREDIT_SALE, 1_000, CustomerEntrySource.Sale(3))),
            reverse(4L),
        )

        assertEquals(listOf(5_000L, 3_000L, 4_500L, -1_500L, -500L, 5_500L), balances)
        assertEquals(Money(5_500), world.database.tables.value.customers.getValue(ID).balance)
        assertEquals(listOf(1L), world.ledger.findBySale(1L).map { it.id })
        assertEquals(6, world.transactions.commits)
    }

    @Test
    fun `a note is trimmed and a blank one is not kept`() = runTest {
        world.seedCustomer(ID)
        append(entry(CustomerLedgerEntryType.PAYMENT, -100, note = "  Abono  "))
        append(entry(CustomerLedgerEntryType.PAYMENT, -100, note = "   "))

        assertEquals(listOf("Abono", null), world.ledger.findByCustomer(ID).map { it.note })
    }

    @Test
    fun `a rejected row writes nothing, and a reversal happens once`() = runTest {
        world.seedCustomer(ID)
        world.seedCustomer(OTHER)
        append(entry(CustomerLedgerEntryType.OPENING_BALANCE, 3_000))
        reverse(1L)
        val before = world.database.tables.value

        val outcomes = world.transactions.write {
            TxResult.Rollback(
                listOf(
                    writer.append(entry(CustomerLedgerEntryType.PAYMENT, 100), TIME),
                    writer.reverse(ID, 1L, null, TIME),
                    writer.reverse(ID, 2L, null, TIME),
                    writer.reverse(OTHER, 1L, null, TIME),
                    writer.reverse(ID, 99L, null, TIME),
                ),
            )
        }

        assertEquals(
            listOf(
                CustomerEntryOutcome.Rejected(CustomerEntryIssue.WRONG_SIGN),
                CustomerEntryOutcome.AlreadyCompensated,
                CustomerEntryOutcome.NotReversible,
                CustomerEntryOutcome.EntryNotFound,
                CustomerEntryOutcome.EntryNotFound,
            ),
            outcomes,
        )
        assertEquals(before, world.database.tables.value)
    }

    @Test
    fun `an adjustment with its note moves the balance without touching money`() = runTest {
        world.seedCustomer(ID)

        assertEquals(
            AdjustCustomerBalanceResult.Recorded(1L, Money(-1_200)),
            adjust(ID, Money(-1_200), " Descuento acordado "),
        )
        val row = world.ledger.findByCustomer(ID).single()
        assertEquals(CustomerLedgerEntryType.ADJUSTMENT, row.type)
        assertEquals("Descuento acordado", row.note)
        assertEquals(TODAY, row.businessDate)
        assertEquals(CustomerLedgerWorld.NOW, row.occurredAt)
        // El primer monto bloquea la moneda en la misma transacción.
        assertEquals(
            CustomerLedgerWorld.NOW,
            world.settings.settings.value.regional.currency.lockedAt,
        )
    }

    @Test
    fun `an adjustment is refused without a note, a customer or a currency`() = runTest {
        world.seedCustomer(ID)
        val before = world.database.tables.value

        assertEquals(
            AdjustCustomerBalanceResult.Invalid(CustomerEntryIssue.NOTE_REQUIRED),
            adjust(ID, Money(500), "  "),
        )
        assertEquals(
            AdjustCustomerBalanceResult.Invalid(CustomerEntryIssue.ZERO_AMOUNT),
            adjust(ID, Money.ZERO, "Nada"),
        )
        assertEquals(AdjustCustomerBalanceResult.CustomerNotFound, adjust(99L, Money(500), "Nota"))
        assertEquals(before, world.database.tables.value)

        val unconfirmed = CustomerLedgerWorld(currencyConfirmed = false)
        unconfirmed.seedCustomer(ID)
        assertEquals(
            AdjustCustomerBalanceResult.CurrencyNotConfirmed,
            adjustIn(unconfirmed)(ID, Money(500), "Nota"),
        )
        val unknownZone = CustomerLedgerWorld(zoneId = "Nowhere/Unknown")
        unknownZone.seedCustomer(ID)
        assertEquals(
            AdjustCustomerBalanceResult.UnknownTimeZone("Nowhere/Unknown"),
            adjustIn(unknownZone)(ID, Money(500), "Nota"),
        )
    }

    @Test
    fun `a storage failure is returned typed`() = runTest {
        world.seedCustomer(ID)
        world.database.failAtWrite = 1
        world.database.failure = { StorageFailureException(StorageFailure.DISK_FULL) }

        assertEquals(
            AdjustCustomerBalanceResult.StorageFailed(StorageFailure.DISK_FULL),
            adjust(ID, Money(500), "Nota"),
        )
        assertEquals(emptyList<Any>(), world.ledger.findByCustomer(ID))
    }

    @Test
    fun `the statement goes from newest to oldest with what is still open`() = runTest {
        world.seedCustomer(ID)
        val due = requireNotNull(BusinessDate.parse("2026-11-20"))
        append(
            entry(CustomerLedgerEntryType.CREDIT_SALE, 5_000, CustomerEntrySource.Sale(1), due),
        )
        append(entry(CustomerLedgerEntryType.PAYMENT, -2_000))
        val observe = ObserveCustomerStatementUseCase(world.customers, world.ledger)

        val statement = requireNotNull(observe(ID).first())

        assertEquals(listOf(2L, 1L), statement.entries.map { it.id })
        assertEquals(Money(3_000), statement.customer.balance)
        assertEquals(listOf(OpenCharge(1L, Money(3_000), due)), statement.dues.openCharges)
        assertNull(observe(99L).first())
    }

    private fun adjustIn(other: CustomerLedgerWorld) = AdjustCustomerBalanceUseCase(
        other.transactions,
        other.settings,
        other.customers,
        other.ledger,
        other.clock,
    )

    private suspend fun append(draft: CustomerEntryDraft): Long =
        balanceOf(world.transactions.write { TxResult.Commit(writer.append(draft, TIME)) })

    private suspend fun reverse(entryId: Long): Long = balanceOf(
        world.transactions.write { TxResult.Commit(writer.reverse(ID, entryId, null, TIME)) },
    )

    private fun balanceOf(outcome: CustomerEntryOutcome): Long =
        (outcome as CustomerEntryOutcome.Appended).balanceAfter.minor

    private fun entry(
        type: CustomerLedgerEntryType,
        amount: Long,
        source: CustomerEntrySource = CustomerEntrySource.None,
        due: BusinessDate? = null,
        note: String? = null,
    ) = CustomerEntryDraft(ID, type, Money(amount), source, due, note)

    private companion object {
        const val ID = 1L
        const val OTHER = 2L
        val TODAY: BusinessDate = requireNotNull(BusinessDate.parse("2025-11-01"))
        val TIME = RecordTime(CustomerLedgerWorld.NOW, TODAY)
    }
}
