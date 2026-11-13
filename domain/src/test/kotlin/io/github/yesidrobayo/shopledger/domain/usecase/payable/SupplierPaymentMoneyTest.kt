package io.github.yesidrobayo.shopledger.domain.usecase.payable

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.catalog.Supplier
import io.github.yesidrobayo.shopledger.domain.model.catalog.SupplierDetails
import io.github.yesidrobayo.shopledger.domain.model.money.AccountCurrency
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccount
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccountDetails
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccountKind
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyMovementType
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyOrigin
import io.github.yesidrobayo.shopledger.domain.model.payable.NewSupplierLedgerEntry
import io.github.yesidrobayo.shopledger.domain.model.payable.SupplierLedgerEntryType
import io.github.yesidrobayo.shopledger.domain.model.settings.BusinessCurrency
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.testing.FakeAppSettingsRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakeMoneyAccountRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakeMoneyLedgerRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakeSupplierLedgerRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakeSupplierRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakeTransactionRunner
import io.github.yesidrobayo.shopledger.domain.testing.FixedClock
import io.github.yesidrobayo.shopledger.domain.testing.InMemoryDatabase
import io.github.yesidrobayo.shopledger.domain.testing.InjectedFailure
import io.github.yesidrobayo.shopledger.domain.testing.appSettings
import io.github.yesidrobayo.shopledger.domain.testing.regionalSettings
import io.github.yesidrobayo.shopledger.domain.testing.seedCashAccount
import io.github.yesidrobayo.shopledger.domain.usecase.money.MoneyWorld
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DayOfWeek
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pagar a un proveedor desde una cuenta de dinero: el pago y su `SUPPLIER_PAYMENT` van juntos, en
 * una sola transacción, y anular el pago compensa los dos. Sin cuenta, el libro de dinero no se
 * toca. Montos en unidad menor de una moneda reservada para pruebas, con dos decimales.
 */
class SupplierPaymentMoneyTest {
    private val database = InMemoryDatabase()
    private val transactions = FakeTransactionRunner(database)
    private val accounts = FakeMoneyAccountRepository(database)
    private val money = FakeMoneyLedgerRepository(database)
    private val ledger = FakeSupplierLedgerRepository(database)
    private val clock = FixedClock(MoneyWorld.NOW)
    private val settings = FakeAppSettingsRepository(
        appSettings(
            regionalSettings(MoneyWorld.ZONE, DayOfWeek.MONDAY).copy(
                currency = BusinessCurrency(
                    code = MoneyWorld.BOOK.code,
                    decimals = MoneyWorld.BOOK.decimals,
                    symbol = null,
                    lockedAt = null,
                ),
            ),
        ),
    )
    private val cash = database.seedCashAccount(MoneyWorld.BOOK, MoneyWorld.SEEDED_AT)

    init {
        database.seed { tables ->
            tables.copy(
                suppliers = tables.suppliers + (SUPPLIER to supplier()),
                moneyAccounts = tables.moneyAccounts +
                    (
                        OTHER_CURRENCY to account(
                            OTHER_CURRENCY,
                            MoneyWorld.OTHER,
                            archived = false,
                        )
                        ) +
                    (ARCHIVED to account(ARCHIVED, MoneyWorld.BOOK, archived = true)),
            )
        }
    }

    private val pay = PaySupplierUseCase(
        transactions,
        settings,
        FakeSupplierRepository(database),
        ledger,
        clock,
        accounts,
        money,
    )

    private val void =
        VoidSupplierPaymentUseCase(transactions, settings, ledger, clock, accounts, money)

    private suspend fun pay(amount: Long, account: Long? = cash) =
        pay(SupplierPaymentRequest(SUPPLIER, Money(amount), moneyAccountId = account))

    private fun tables() = database.tables.value

    @Test
    fun `a payment from an account writes the payment and its money movement together`() = runTest {
        owe(30_000)

        val entryId = (pay(10_000) as PaySupplierResult.Paid).entryId

        val movement = tables().moneyMovements.values.single()
        assertEquals(MoneyMovementType.SUPPLIER_PAYMENT, movement.type)
        assertEquals(Money(-10_000), movement.amount)
        assertEquals(cash, movement.accountId)
        assertEquals(MoneyOrigin.SupplierPayment(entryId), movement.draft.origin)
        assertEquals(Money(-10_000), tables().moneyAccounts.getValue(cash).balance)
        assertEquals(Money(20_000), tables().suppliers.getValue(SUPPLIER).balance)
        assertEquals(MoneyWorld.NOW, movement.time.occurredAt)
        assertEquals(
            tables().supplierEntries.getValue(entryId).content.businessDate,
            movement.time.businessDate,
        )
    }

    @Test
    fun `a payment without account leaves the money ledger alone`() = runTest {
        assertTrue(pay(10_000, account = null) is PaySupplierResult.Paid)

        assertTrue(tables().moneyMovements.isEmpty())
        assertEquals(Money.ZERO, tables().moneyAccounts.getValue(cash).balance)
    }

    @Test
    fun `an account that is missing, archived or in another currency is refused`() = runTest {
        val before = tables()

        assertEquals(PaySupplierResult.MoneyAccountNotFound, pay(1_000, account = 99))
        assertEquals(PaySupplierResult.MoneyAccountArchived, pay(1_000, account = ARCHIVED))
        assertEquals(
            PaySupplierResult.MoneyAccountOtherCurrency,
            pay(1_000, account = OTHER_CURRENCY),
        )
        assertEquals(before, tables())
    }

    @Test
    fun `an account balance that does not fit is refused`() = runTest {
        database.seed { tables ->
            val account = tables.moneyAccounts.getValue(cash)
            tables.copy(
                moneyAccounts =
                    tables.moneyAccounts +
                        (cash to account.copy(balance = Money(Long.MIN_VALUE + 1))),
            )
        }
        val before = tables()

        assertEquals(PaySupplierResult.AccountBalanceTooLarge, pay(2))
        assertEquals(before, tables())
    }

    @Test
    fun `a failure at any write leaves neither the payment nor the movement`() = runTest {
        owe(30_000)
        val before = tables()
        var failAt = 1
        while (true) {
            database.failAtWrite = failAt
            val failed = runCatching { pay(10_000) }
            if (failed.isSuccess) break
            assertTrue("Escritura $failAt", failed.exceptionOrNull() is InjectedFailure)
            assertEquals(before, tables())
            failAt++
        }
        // La fila del proveedor con su id y su saldo, y el movimiento con el saldo de su cuenta.
        assertEquals(6, failAt)
    }

    @Test
    fun `voiding a payment from an account compensates both ledgers once`() = runTest {
        owe(30_000)
        val entryId = (pay(10_000) as PaySupplierResult.Paid).entryId

        assertEquals(VoidSupplierPaymentResult.Voided, void(entryId))
        assertEquals(VoidSupplierPaymentResult.AlreadyVoided, void(entryId))

        val (payment, compensation) = tables().moneyMovements.values.sortedBy { it.id }
        assertEquals(MoneyMovementType.MOVEMENT_VOID, compensation.type)
        assertEquals(Money(10_000), compensation.amount)
        assertEquals(MoneyOrigin.Void(payment.id), compensation.draft.origin)
        assertEquals(Money.ZERO, tables().moneyAccounts.getValue(cash).balance)
        assertEquals(Money(30_000), tables().suppliers.getValue(SUPPLIER).balance)
    }

    @Test
    fun `voiding a payment without account writes no money movement`() = runTest {
        val entryId = (pay(10_000, account = null) as PaySupplierResult.Paid).entryId

        assertEquals(VoidSupplierPaymentResult.Voided, void(entryId))
        assertTrue(tables().moneyMovements.isEmpty())
    }

    @Test
    fun `a failure while voiding leaves both ledgers as they were`() = runTest {
        val entryId = (pay(10_000) as PaySupplierResult.Paid).entryId
        val before = tables()
        for (failAt in 1..5) {
            database.failAtWrite = failAt
            val failed = runCatching { void(entryId) }
            assertTrue("Escritura $failAt", failed.exceptionOrNull() is InjectedFailure)
            assertEquals(before, tables())
        }
        database.failAtWrite = null
        assertEquals(VoidSupplierPaymentResult.Voided, void(entryId))
    }

    @Test
    fun `a money compensation that does not fit leaves the payment standing`() = runTest {
        val entryId = (pay(10_000) as PaySupplierResult.Paid).entryId
        database.seed { tables ->
            val account = tables.moneyAccounts.getValue(cash)
            tables.copy(
                moneyAccounts =
                    tables.moneyAccounts + (cash to account.copy(balance = Money(Long.MAX_VALUE))),
            )
        }
        val before = tables()

        assertEquals(VoidSupplierPaymentResult.AccountBalanceTooLarge, void(entryId))
        assertEquals(before, tables())
    }

    /** Lo que ya se le debía al proveedor, como saldo inicial de su libro. */
    private suspend fun owe(amount: Long) {
        transactions.write {
            ledger.append(
                NewSupplierLedgerEntry(
                    supplierId = SUPPLIER,
                    type = SupplierLedgerEntryType.OPENING_BALANCE,
                    amount = Money(amount),
                    balanceAfter = Money(amount),
                    occurredAt = MoneyWorld.NOW,
                    businessDate = checkNotNull(BusinessDate.parse("2026-03-15")),
                    createdAt = MoneyWorld.NOW,
                ),
            )
            TxResult.Commit(Unit)
        }
    }

    private fun supplier() = Supplier(
        id = SUPPLIER,
        details = SupplierDetails("Supplier", "supplier", null, null, null, null),
        balance = Money.ZERO,
        archivedAt = null,
        createdAt = AT,
        updatedAt = AT,
    )

    private fun account(id: Long, currency: AccountCurrency, archived: Boolean) = MoneyAccount(
        id = id,
        systemKey = null,
        details = MoneyAccountDetails("Account $id", "account $id", MoneyAccountKind.BANK),
        currency = currency,
        balance = Money.ZERO,
        archivedAt = if (archived) AT else null,
        createdAt = AT,
        updatedAt = AT,
    )

    private companion object {
        const val SUPPLIER = 5L
        const val OTHER_CURRENCY = 20L
        const val ARCHIVED = 21L
        val AT: Instant = Instant.fromEpochMilliseconds(0)
    }
}
