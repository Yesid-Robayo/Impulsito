package io.github.yesidrobayo.shopledger.data.repository

import androidx.room3.useReaderConnection
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.backup.InvariantQueries
import io.github.yesidrobayo.shopledger.data.catalog.InjectedWriteFailure
import io.github.yesidrobayo.shopledger.data.catalog.WriteFaults
import io.github.yesidrobayo.shopledger.data.local.TEST_BUSINESS_CURRENCY
import io.github.yesidrobayo.shopledger.data.local.TEST_BUSINESS_DECIMALS
import io.github.yesidrobayo.shopledger.data.local.money.MoneyAccountDao
import io.github.yesidrobayo.shopledger.data.local.money.MoneyMovementDao
import io.github.yesidrobayo.shopledger.data.local.money.MoneyMovementEntity
import io.github.yesidrobayo.shopledger.data.local.sale
import io.github.yesidrobayo.shopledger.data.local.texts
import io.github.yesidrobayo.shopledger.data.transaction.RestoreLock
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerContact
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerDetails
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerEntryDraft
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerEntrySource
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerLedgerEntryType
import io.github.yesidrobayo.shopledger.domain.model.customer.NewCustomerLedgerEntry
import io.github.yesidrobayo.shopledger.domain.model.money.AccountCurrency
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccountDetails
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccountKind
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyDocument
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyMovementType
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyOrigin
import io.github.yesidrobayo.shopledger.domain.model.money.NewMoneyAccount
import io.github.yesidrobayo.shopledger.domain.model.region.RecordTime
import io.github.yesidrobayo.shopledger.domain.model.settings.CurrencyCode
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.CustomerLedgerRepository
import io.github.yesidrobayo.shopledger.domain.rule.customer.CoveredCharge
import io.github.yesidrobayo.shopledger.domain.usecase.customer.CustomerLedgerWriter
import io.github.yesidrobayo.shopledger.domain.usecase.customer.CustomerPaymentRequest
import io.github.yesidrobayo.shopledger.domain.usecase.customer.RecordCustomerPaymentResult
import io.github.yesidrobayo.shopledger.domain.usecase.customer.RecordCustomerPaymentUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.customer.VoidCustomerPaymentResult
import io.github.yesidrobayo.shopledger.domain.usecase.customer.VoidCustomerPaymentUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.money.MoneyLedgerWriter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Abonos de clientes sobre la base real, con sus guardas: el abono es una fila nueva del libro del
 * cliente y, con cuenta, un movimiento de dinero que apunta a ella, en una sola transacción y un
 * solo cambio para el respaldo. Una falla inyectada después de cada escritura no deja nada, y las
 * consultas de los saldos de clientes y cuentas quedan en cero filas. Nombres y montos inventados.
 */
@RunWith(AndroidJUnit4::class)
internal class CustomerPaymentOnDatabaseTest : RepositoryTest() {
    private val faults = WriteFaults()
    private val customers by lazy { RoomCustomerRepository(database) }
    private val ledger by lazy {
        FaultyCustomerLedger(RoomCustomerLedgerRepository(database), faults)
    }
    private val accounts by lazy { RoomMoneyAccountRepository(database) }
    private val moneyLedger by lazy {
        val movements = database.moneyMovementDao()
        val balances = database.moneyAccountDao()
        RoomMoneyLedgerRepository(
            object : MoneyMovementDao by movements {
                override suspend fun insert(movement: MoneyMovementEntity): Long =
                    faults.after { movements.insert(movement) }
            },
            object : MoneyAccountDao by balances {
                override suspend fun setBalance(id: Long, balance: Long, updatedAt: Long): Int =
                    faults.after { balances.setBalance(id, balance, updatedAt) }
            },
            RepositoryWrites(database),
        )
    }
    private val settings by lazy { RoomAppSettingsRepository(database, RestoreLock { false }) }
    private val money by lazy { MoneyLedgerWriter(accounts, moneyLedger) }
    private val record by lazy {
        RecordCustomerPaymentUseCase(
            runner,
            settings,
            customers,
            ledger,
            accounts,
            money,
            Clock { AT },
        )
    }
    private val void by lazy {
        VoidCustomerPaymentUseCase(runner, settings, ledger, moneyLedger, money, Clock { LATER_AT })
    }

    private var customerId = 0L
    private var bankId = 0L

    @Before
    fun createCustomerAndAccount() = runBlocking {
        customerId = commit {
            customers.insert(
                CustomerDetails(CustomerContact("Ana", "ana", null, null, null), null),
                AT,
            )
        }
        bankId = commit {
            accounts.insert(
                NewMoneyAccount(
                    MoneyAccountDetails(
                        "Banco inventado",
                        "banco inventado",
                        MoneyAccountKind.BANK,
                    ),
                    AccountCurrency(
                        requireNotNull(CurrencyCode.of(TEST_BUSINESS_CURRENCY)),
                        TEST_BUSINESS_DECIMALS.toInt(),
                    ),
                    AT,
                ),
            )
        }
    }

    @Test
    fun aPaymentWithAnAccountIsOneChangeAndLeavesBothBalances() = runBlocking {
        charge(5_000)
        val before = changeSeq()

        val paid = record(request(2_000, bankId)) as RecordCustomerPaymentResult.Recorded

        assertEquals(before + 1, changeSeq())
        assertEquals(Money(3_000), paid.balanceAfter)
        assertEquals(listOf(CoveredCharge(1L, Money(2_000), null)), paid.allocation.covered)
        val rows = ledger.findByCustomer(customerId)
        assertEquals(listOf(5_000L, -2_000L), rows.map { it.amount.minor })
        assertEquals(Money(3_000), customers.findById(customerId)?.balance)
        val movement = moneyLedger.findByCustomerLedgerEntry(paid.entryId).single()
        assertEquals(paid.movementId, movement.id)
        assertEquals(MoneyMovementType.CUSTOMER_PAYMENT, movement.type)
        assertEquals(MoneyOrigin.CustomerPayment(paid.entryId), movement.draft.origin)
        assertEquals(MoneyDocument.Customer(customerId), moneyLedger.findDocument(movement.id))
        assertEquals(Money(2_000), accounts.findById(bankId)?.balance)
        assertInvariantsHold()
    }

    @Test
    fun aPaymentWithoutAccountWritesOnlyTheCustomerLedger() = runBlocking {
        charge(5_000)

        val paid = record(request(6_000, null)) as RecordCustomerPaymentResult.Recorded

        assertEquals(Money(-1_000), paid.balanceAfter)
        assertEquals(Money(1_000), paid.allocation.creditLeft)
        assertEquals(0L, count("SELECT COUNT(*) FROM money_movements"))
        assertEquals(Money.ZERO, accounts.findById(bankId)?.balance)
        assertInvariantsHold()
    }

    @Test
    fun voidingAPaymentCompensatesTheLedgerAndTheMoneyOnce() = runBlocking {
        charge(5_000)
        val paid = record(request(2_000, bankId)) as RecordCustomerPaymentResult.Recorded
        val before = changeSeq()

        val voided =
            void(customerId, paid.entryId, "Abono repetido") as VoidCustomerPaymentResult.Voided

        assertEquals(before + 1, changeSeq())
        assertEquals(Money(5_000), voided.balanceAfter)
        val reversal = requireNotNull(ledger.findById(voided.voidEntryId))
        assertEquals(CustomerLedgerEntryType.ENTRY_VOID, reversal.type)
        assertEquals(CustomerEntrySource.Reverses(paid.entryId), reversal.source)
        val moneyVoid = requireNotNull(moneyLedger.findById(voided.voidMovementIds.single()))
        assertEquals(MoneyOrigin.Void(requireNotNull(paid.movementId)), moneyVoid.draft.origin)
        assertEquals(Money(-2_000), moneyVoid.amount)
        assertEquals(Money.ZERO, accounts.findById(bankId)?.balance)
        assertEquals(VoidCustomerPaymentResult.AlreadyVoided, void(customerId, paid.entryId))
        assertEquals(VoidCustomerPaymentResult.NotAPayment, void(customerId, 1L))
        assertEquals(before + 1, changeSeq())
        assertInvariantsHold()
    }

    @Test
    fun aFailureAfterEachWriteOfAPaymentLeavesNothing() = runBlocking {
        charge(5_000)

        assertEachWriteRollsBack("abono") { record(request(2_000, bankId)) }
        assertEquals(Money(3_000), customers.findById(customerId)?.balance)
        assertEquals(Money(2_000), accounts.findById(bankId)?.balance)
        assertInvariantsHold()
    }

    @Test
    fun aFailureAfterEachWriteOfAVoidLeavesThePaymentInPlace() = runBlocking {
        charge(5_000)
        val paid = record(request(2_000, bankId)) as RecordCustomerPaymentResult.Recorded

        assertEachWriteRollsBack("anulación") { void(customerId, paid.entryId) }
        assertEquals(Money(5_000), customers.findById(customerId)?.balance)
        assertEquals(Money.ZERO, accounts.findById(bankId)?.balance)
        assertInvariantsHold()
    }

    @Test
    fun theDatabaseRefusesAPaymentWithTheWrongSign() = runBlocking {
        val draft = CustomerEntryDraft(
            customerId,
            CustomerLedgerEntryType.PAYMENT,
            Money(2_000),
            CustomerEntrySource.None,
            null,
            null,
        )
        val row = NewCustomerLedgerEntry(draft, Money(2_000), AT, DATE)

        storageFailureOf { commit { ledger.append(row) } }

        assertEquals(0L, count("SELECT COUNT(*) FROM customer_ledger_entries"))
    }

    /**
     * Corre [operation] con una falla después de la escritura 1, 2, 3… hasta que confirma. Cada
     * falla debe dejar la base igual que antes, sin cambio contado, y la operación tiene que haber
     * escrito al menos el libro del cliente, el movimiento y el saldo de la cuenta.
     */
    private suspend fun assertEachWriteRollsBack(name: String, operation: suspend () -> Any) {
        val before = snapshot()
        var failAt = 1
        while (true) {
            faults.reset(failAfter = failAt)
            val failed = try {
                operation()
                false
            } catch (expected: InjectedWriteFailure) {
                true
            }
            if (!failed) break
            assertEquals("$name: la escritura $failAt dejó algo", before, snapshot())
            failAt++
        }
        faults.reset(failAfter = null)
        assertEquals("$name: escrituras", WRITES + 1, failAt)
        assertTrue("$name no confirmó", before != snapshot())
    }

    private suspend fun snapshot(): List<Any> = listOf(
        changeSeq(),
        count("SELECT COUNT(*) FROM customer_ledger_entries"),
        count("SELECT COUNT(*) FROM money_movements"),
        requireNotNull(customers.findById(customerId)).balance,
        requireNotNull(accounts.findById(bankId)).balance,
    )

    private suspend fun assertInvariantsHold() {
        val broken = InvariantQueries.MODEL
            .filter { it.label.startsWith("I-14") || it.label.startsWith("I-16") }
            .associate { query ->
                query.label to database.useReaderConnection { it.texts(query.sql) }
            }
            .filterValues { it.isNotEmpty() }
        assertEquals(emptyMap<String, List<String>>(), broken)
    }

    private suspend fun charge(amount: Long) {
        val saleId = database.saleDao().insert(sale(priceListId).copy(customerId = customerId))
        val draft = CustomerEntryDraft(
            customerId,
            CustomerLedgerEntryType.CREDIT_SALE,
            Money(amount),
            CustomerEntrySource.Sale(saleId),
            null,
            null,
        )
        commit { CustomerLedgerWriter(ledger).append(draft, RecordTime(AT, DATE)) }
    }

    private fun request(amount: Long, accountId: Long?) =
        CustomerPaymentRequest(customerId, Money(amount), accountId, null, null)

    /** El libro del cliente real, con una falla posible justo después de agregar cada fila. */
    private class FaultyCustomerLedger(
        private val real: CustomerLedgerRepository,
        private val faults: WriteFaults,
    ) : CustomerLedgerRepository by real {
        override suspend fun append(entry: NewCustomerLedgerEntry): Long =
            faults.after { real.append(entry) }
    }

    private companion object {
        /** El libro del cliente con su saldo, el movimiento de dinero y el saldo de la cuenta. */
        const val WRITES = 3
    }
}
