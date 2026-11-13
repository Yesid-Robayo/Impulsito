package io.github.yesidrobayo.shopledger.domain.usecase.customer

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerEntryDraft
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerEntrySource
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerLedgerEntryType
import io.github.yesidrobayo.shopledger.domain.model.money.AccountCurrency
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccountDetails
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccountKind
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyMovementType
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyOrigin
import io.github.yesidrobayo.shopledger.domain.model.region.RecordTime
import io.github.yesidrobayo.shopledger.domain.model.settings.CurrencyCode
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.rule.customer.CoveredCharge
import io.github.yesidrobayo.shopledger.domain.rule.customer.CustomerDueRules
import io.github.yesidrobayo.shopledger.domain.rule.customer.CustomerEntryIssue
import io.github.yesidrobayo.shopledger.domain.rule.customer.CustomerPaymentIssue
import io.github.yesidrobayo.shopledger.domain.rule.customer.OpenCharge
import io.github.yesidrobayo.shopledger.domain.rule.customer.PaymentAllocation
import io.github.yesidrobayo.shopledger.domain.testing.seedCashAccount
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Registrar y anular abonos sobre la base en memoria: el abono baja el saldo con una fila nueva,
 * nunca edita otra, reparte sobre lo más antiguo, y entra a una cuenta solo si se eligió una.
 * Montos inventados, en una moneda de prueba sin decimales.
 */
class CustomerPaymentUseCasesTest {
    private val world = CustomerLedgerWorld()
    private val record = recordIn(world)
    private val void = voidIn(world)
    private val writer = CustomerLedgerWriter(world.ledger)

    @Test
    fun `a payment of 2000 on 5000 leaves 3000 and does not touch the charge`() = runTest {
        world.seedCustomer(ID)
        charge(5_000)
        val charge = world.ledger.findByCustomer(ID).single()

        val result = record(request(2_000))

        assertEquals(
            RecordCustomerPaymentResult.Recorded(
                entryId = 2L,
                movementId = null,
                balanceAfter = Money(3_000),
                allocation = PaymentAllocation(
                    listOf(CoveredCharge(1L, Money(2_000), null)),
                    Money.ZERO,
                ),
            ),
            result,
        )
        val rows = world.ledger.findByCustomer(ID)
        assertEquals(charge, rows.first())
        assertEquals(CustomerLedgerEntryType.PAYMENT, rows.last().type)
        assertEquals(Money(-2_000), rows.last().amount)
        assertEquals(CustomerEntrySource.None, rows.last().source)
        assertEquals(Money(3_000), world.database.tables.value.customers.getValue(ID).balance)
        // Sin cuenta elegida, el dinero no se mueve.
        assertTrue(world.database.tables.value.moneyMovements.isEmpty())
        assertEquals(2, world.transactions.commits)
    }

    @Test
    fun `with an account the money enters it in the same transaction`() = runTest {
        world.seedCustomer(ID)
        val cash = world.database.seedCashAccount(BOOK, CustomerLedgerWorld.NOW)
        charge(5_000)

        val result = record(
            request(2_000, accountId = cash),
        ) as RecordCustomerPaymentResult.Recorded

        val movement = requireNotNull(world.moneyLedger.findById(requireNotNull(result.movementId)))
        assertEquals(MoneyMovementType.CUSTOMER_PAYMENT, movement.type)
        assertEquals(Money(2_000), movement.amount)
        assertEquals(MoneyOrigin.CustomerPayment(result.entryId), movement.draft.origin)
        assertEquals(TIME, movement.time)
        assertEquals(Money(2_000), world.accounts.findById(cash)?.balance)
        assertEquals(listOf(movement), world.moneyLedger.findByCustomerLedgerEntry(result.entryId))
        assertEquals(2, world.transactions.commits)
    }

    @Test
    fun `a payment above the debt is kept and leaves credit, as the rule's fourth row`() = runTest {
        world.seedCustomer(ID)
        charge(5_000)
        record(request(2_000))
        charge(1_500, saleId = 2L)

        val result = record(request(6_000)) as RecordCustomerPaymentResult.Recorded

        assertEquals(Money(-1_500), result.balanceAfter)
        assertEquals(Money(1_500), result.allocation.creditLeft)
        assertEquals(
            listOf(
                CoveredCharge(1L, Money(3_000), null),
                CoveredCharge(3L, Money(1_500), null),
            ),
            result.allocation.covered,
        )
        assertEquals(
            listOf(5_000L, 3_000L, 4_500L, -1_500L),
            world.ledger.findByCustomer(ID).map { it.balanceAfter.minor },
        )
    }

    @Test
    fun `an advance without debt is all credit`() = runTest {
        world.seedCustomer(ID)

        val result = record(request(800)) as RecordCustomerPaymentResult.Recorded

        assertEquals(Money(-800), result.balanceAfter)
        assertEquals(PaymentAllocation(emptyList(), Money(800)), result.allocation)
    }

    @Test
    fun `6000 covers the oldest charge first, and nothing is overdue until the second date`() =
        runTest {
            world.seedCustomer(ID)
            charge(5_000, due = NOV_10)
            charge(3_000, saleId = 2L, due = NOV_20)

            val result = record(request(6_000)) as RecordCustomerPaymentResult.Recorded

            assertEquals(
                listOf(
                    CoveredCharge(1L, Money(5_000), NOV_10),
                    CoveredCharge(2L, Money(1_000), NOV_20),
                ),
                result.allocation.covered,
            )
            val dues = CustomerDueRules.duesOf(world.ledger.findByCustomer(ID))
            assertEquals(listOf(OpenCharge(2L, Money(2_000), NOV_20)), dues.openCharges)
            assertNull(dues.overdueSince(NOV_20))
            assertEquals(NOV_20, dues.overdueSince(date("2026-11-21")))
        }

    @Test
    fun `a past date is kept at noon of that day, and the first payment locks the currency`() =
        runTest {
            world.seedCustomer(ID)
            val yesterday = date("2025-10-31")

            record(request(500, date = yesterday))

            val row = world.ledger.findByCustomer(ID).single()
            assertEquals(yesterday, row.businessDate)
            assertEquals(Instant.parse("2025-10-31T12:00:00Z"), row.occurredAt)
            assertEquals(
                CustomerLedgerWorld.NOW,
                world.settings.settings.value.regional.currency.lockedAt,
            )
            record(request(100, date = TODAY))
            assertEquals(CustomerLedgerWorld.NOW, world.ledger.findByCustomer(ID).last().occurredAt)
        }

    @Test
    fun `every refused payment writes nothing`() = runTest {
        world.seedCustomer(ID)
        val cash = world.database.seedCashAccount(BOOK, CustomerLedgerWorld.NOW)
        val archived = seedAccount(BOOK, archived = true)
        val foreign = seedAccount(AccountCurrency(requireNotNull(CurrencyCode.of("XBA")), 0))
        val decimals = seedAccount(AccountCurrency(BOOK.code, 2))
        charge(5_000)
        val before = world.database.tables.value

        val refused = listOf(
            RecordCustomerPaymentResult.InvalidAmount(CustomerPaymentIssue.AMOUNT_NOT_POSITIVE) to
                request(0),
            RecordCustomerPaymentResult.InvalidAmount(CustomerPaymentIssue.AMOUNT_NOT_POSITIVE) to
                request(-100),
            RecordCustomerPaymentResult.InvalidAmount(CustomerPaymentIssue.AMOUNT_TOO_LARGE) to
                request(Limit.DOCUMENT_TOTAL.max + 1),
            RecordCustomerPaymentResult.CustomerNotFound to request(100, customerId = 99L),
            RecordCustomerPaymentResult.FutureDate to request(100, date = date("2025-11-02")),
            RecordCustomerPaymentResult.AccountNotFound to request(100, accountId = 99L),
            RecordCustomerPaymentResult.AccountArchived to request(100, accountId = archived),
            RecordCustomerPaymentResult.AccountInOtherCurrency to request(100, accountId = foreign),
            RecordCustomerPaymentResult.AccountInOtherCurrency to
                request(100, accountId = decimals),
            RecordCustomerPaymentResult.Invalid(CustomerEntryIssue.NOTE_TOO_LONG) to
                request(100, accountId = cash, note = "a".repeat(201)),
        )
        for ((expected, request) in refused) {
            assertEquals(expected, record(request))
        }
        assertEquals(before, world.database.tables.value)
    }

    @Test
    fun `a balance or an account that would overflow is refused`() = runTest {
        world.seedCustomer(ID)
        world.seedCustomer(OTHER)
        val cash = world.database.seedCashAccount(BOOK, CustomerLedgerWorld.NOW)
        world.transactions.write {
            TxResult.Commit(
                writer.append(
                    CustomerEntryDraft(
                        ID,
                        CustomerLedgerEntryType.OPENING_BALANCE,
                        Money(Long.MIN_VALUE + 1),
                        CustomerEntrySource.None,
                        null,
                        null,
                    ),
                    TIME,
                ),
            )
        }
        val full = world.database.tables.value.moneyAccounts.getValue(cash)
        world.database.seed {
            it.copy(
                moneyAccounts =
                    it.moneyAccounts + (cash to full.copy(balance = Money(Long.MAX_VALUE))),
            )
        }
        val before = world.database.tables.value

        assertEquals(
            RecordCustomerPaymentResult.Invalid(CustomerEntryIssue.BALANCE_OVERFLOW),
            record(request(100)),
        )
        assertEquals(
            RecordCustomerPaymentResult.AccountOverflow,
            record(request(100, customerId = OTHER, accountId = cash)),
        )
        assertEquals(before, world.database.tables.value)
    }

    @Test
    fun `without a confirmed currency or a known zone nothing is recorded`() = runTest {
        val unconfirmed = CustomerLedgerWorld(currencyConfirmed = false)
        unconfirmed.seedCustomer(ID)
        assertEquals(
            RecordCustomerPaymentResult.CurrencyNotConfirmed,
            recordIn(unconfirmed)(request(100)),
        )
        val unknownZone = CustomerLedgerWorld(zoneId = "Nowhere/Unknown")
        unknownZone.seedCustomer(ID)
        assertEquals(
            RecordCustomerPaymentResult.UnknownTimeZone("Nowhere/Unknown"),
            recordIn(unknownZone)(request(100)),
        )
        assertTrue(unconfirmed.database.tables.value.customerLedger.isEmpty())
        assertTrue(unknownZone.database.tables.value.customerLedger.isEmpty())
    }

    @Test
    fun `a failure at any write of the payment leaves nothing behind`() = runTest {
        world.seedCustomer(ID)
        val cash = world.database.seedCashAccount(BOOK, CustomerLedgerWorld.NOW)
        charge(5_000)
        val before = world.database.tables.value
        // Cuenta las escrituras de un abono que sí entra, sobre una copia del mismo estado.
        val probe = CustomerLedgerWorld()
        probe.seedCustomer(ID)
        val probeCash = probe.database.seedCashAccount(BOOK, CustomerLedgerWorld.NOW)
        probe.database.seed { before }
        recordIn(probe)(request(2_000, accountId = probeCash))
        val writes = probe.database.lastTransactionWrites
        assertTrue(writes >= 4)

        for (step in 1..writes) {
            world.database.failAtWrite = step
            world.database.failure = { StorageFailureException(StorageFailure.DISK_FULL) }
            assertEquals(
                "escritura $step",
                RecordCustomerPaymentResult.StorageFailed(StorageFailure.DISK_FULL),
                record(request(2_000, accountId = cash)),
            )
            assertEquals("escritura $step", before, world.database.tables.value)
        }
    }

    @Test
    fun `voiding a payment compensates it and its money once`() = runTest {
        world.seedCustomer(ID)
        val cash = world.database.seedCashAccount(BOOK, CustomerLedgerWorld.NOW)
        charge(5_000)
        val paid = record(request(2_000, accountId = cash)) as RecordCustomerPaymentResult.Recorded
        val movement = requireNotNull(paid.movementId)

        val result = void(ID, paid.entryId, " Abono repetido ")

        assertEquals(
            VoidCustomerPaymentResult.Voided(voidEntryId = 3L, listOf(2L), Money(5_000)),
            result,
        )
        val reversal = requireNotNull(world.ledger.findById(3L))
        assertEquals(CustomerLedgerEntryType.ENTRY_VOID, reversal.type)
        assertEquals(CustomerEntrySource.Reverses(paid.entryId), reversal.source)
        assertEquals("Abono repetido", reversal.note)
        val moneyVoid = requireNotNull(world.moneyLedger.findById(2L))
        assertEquals(MoneyMovementType.MOVEMENT_VOID, moneyVoid.type)
        assertEquals(MoneyOrigin.Void(movement), moneyVoid.draft.origin)
        assertNull(moneyVoid.draft.note)
        assertEquals(Money.ZERO, world.accounts.findById(cash)?.balance)
        // El abono anulado deja de cubrir: el cargo vuelve a estar pendiente entero.
        assertEquals(
            listOf(OpenCharge(1L, Money(5_000), null)),
            CustomerDueRules.duesOf(world.ledger.findByCustomer(ID)).openCharges,
        )
        assertEquals(VoidCustomerPaymentResult.AlreadyVoided, void(ID, paid.entryId))
    }

    @Test
    fun `voiding a payment without an account writes no money`() = runTest {
        world.seedCustomer(ID)
        val paid = record(request(900)) as RecordCustomerPaymentResult.Recorded

        assertEquals(
            VoidCustomerPaymentResult.Voided(2L, emptyList(), Money.ZERO),
            void(ID, paid.entryId),
        )
        assertTrue(world.database.tables.value.moneyMovements.isEmpty())
    }

    @Test
    fun `only a payment of this customer is voided, and a refusal writes nothing`() = runTest {
        world.seedCustomer(ID)
        world.seedCustomer(OTHER)
        charge(5_000)
        val paid = record(request(2_000)) as RecordCustomerPaymentResult.Recorded
        val before = world.database.tables.value

        assertEquals(VoidCustomerPaymentResult.NotAPayment, void(ID, 1L))
        assertEquals(VoidCustomerPaymentResult.EntryNotFound, void(OTHER, paid.entryId))
        assertEquals(VoidCustomerPaymentResult.EntryNotFound, void(ID, 99L))
        assertEquals(
            VoidCustomerPaymentResult.Invalid(CustomerEntryIssue.NOTE_TOO_LONG),
            void(ID, paid.entryId, "a".repeat(201)),
        )
        assertEquals(before, world.database.tables.value)

        val unknownZone = CustomerLedgerWorld(zoneId = "Nowhere/Unknown")
        assertEquals(
            VoidCustomerPaymentResult.UnknownTimeZone("Nowhere/Unknown"),
            unknownZone.seedPaymentAndVoid(),
        )
    }

    @Test
    fun `a voided payment whose account would overflow is refused`() = runTest {
        world.seedCustomer(ID)
        val cash = world.database.seedCashAccount(BOOK, CustomerLedgerWorld.NOW)
        val paid = record(request(2_000, accountId = cash)) as RecordCustomerPaymentResult.Recorded
        val account = world.database.tables.value.moneyAccounts.getValue(cash)
        world.database.seed {
            it.copy(
                moneyAccounts = it.moneyAccounts +
                    (cash to account.copy(balance = Money(Long.MIN_VALUE + 1))),
            )
        }
        val before = world.database.tables.value

        assertEquals(VoidCustomerPaymentResult.AccountOverflow, void(ID, paid.entryId))
        assertEquals(before, world.database.tables.value)
    }

    @Test
    fun `a failure at any write of the void leaves the payment in place`() = runTest {
        world.seedCustomer(ID)
        val cash = world.database.seedCashAccount(BOOK, CustomerLedgerWorld.NOW)
        val paid = record(request(2_000, accountId = cash)) as RecordCustomerPaymentResult.Recorded
        val before = world.database.tables.value
        world.database.failure = { StorageFailureException(StorageFailure.DISK_FULL) }

        var step = 1
        while (true) {
            world.database.failAtWrite = step
            val result = void(ID, paid.entryId)
            if (result is VoidCustomerPaymentResult.Voided) break
            assertEquals(
                "escritura $step",
                VoidCustomerPaymentResult.StorageFailed(StorageFailure.DISK_FULL),
                result,
            )
            assertEquals("escritura $step", before, world.database.tables.value)
            step++
        }
        // Falló en cada una de sus escrituras, del libro del cliente y del de dinero, sin dejar nada.
        assertTrue(step > 4)
    }

    /** Un abono sembrado y su anulación en un mundo con la zona desconocida. */
    private suspend fun CustomerLedgerWorld.seedPaymentAndVoid(): VoidCustomerPaymentResult {
        seedCustomer(ID)
        transactions.write {
            TxResult.Commit(
                CustomerLedgerWriter(ledger).append(
                    CustomerEntryDraft(
                        ID,
                        CustomerLedgerEntryType.PAYMENT,
                        Money(-100),
                        CustomerEntrySource.None,
                        null,
                        null,
                    ),
                    TIME,
                ),
            )
        }
        return voidIn(this)(ID, 1L)
    }

    private suspend fun charge(amount: Long, saleId: Long = 1L, due: BusinessDate? = null) {
        world.transactions.write {
            TxResult.Commit(
                writer.append(
                    CustomerEntryDraft(
                        ID,
                        CustomerLedgerEntryType.CREDIT_SALE,
                        Money(amount),
                        CustomerEntrySource.Sale(saleId),
                        due,
                        null,
                    ),
                    TIME,
                ),
            )
        }
    }

    /** Una cuenta con nombre, en [currency], con id propio. */
    private fun seedAccount(currency: AccountCurrency, archived: Boolean = false): Long {
        val id = world.database.seedCashAccount(currency, CustomerLedgerWorld.NOW)
        world.database.seed { tables ->
            val account = tables.moneyAccounts.getValue(id)
            tables.copy(
                moneyAccounts = tables.moneyAccounts + (
                    id to account.copy(
                        systemKey = null,
                        details = MoneyAccountDetails(
                            "Cuenta $id",
                            "cuenta $id",
                            MoneyAccountKind.BANK,
                        ),
                        archivedAt = if (archived) CustomerLedgerWorld.NOW else null,
                    )
                    ),
            )
        }
        return id
    }

    private fun request(
        amount: Long,
        customerId: Long = ID,
        accountId: Long? = null,
        date: BusinessDate? = null,
        note: String? = null,
    ) = CustomerPaymentRequest(customerId, Money(amount), accountId, date, note)

    private fun recordIn(other: CustomerLedgerWorld) = RecordCustomerPaymentUseCase(
        other.transactions,
        other.settings,
        other.customers,
        other.ledger,
        other.accounts,
        other.moneyWriter,
        other.clock,
    )

    private fun voidIn(other: CustomerLedgerWorld) = VoidCustomerPaymentUseCase(
        other.transactions,
        other.settings,
        other.ledger,
        other.moneyLedger,
        other.moneyWriter,
        other.clock,
    )

    private fun date(text: String): BusinessDate = requireNotNull(BusinessDate.parse(text))

    private companion object {
        const val ID = 1L
        const val OTHER = 2L
        val TODAY: BusinessDate = requireNotNull(BusinessDate.parse("2025-11-01"))
        val TIME = RecordTime(CustomerLedgerWorld.NOW, TODAY)
        val BOOK = AccountCurrency(requireNotNull(CurrencyCode.of("XTS")), 0)
        val NOV_10: BusinessDate = requireNotNull(BusinessDate.parse("2026-11-10"))
        val NOV_20: BusinessDate = requireNotNull(BusinessDate.parse("2026-11-20"))
    }
}
