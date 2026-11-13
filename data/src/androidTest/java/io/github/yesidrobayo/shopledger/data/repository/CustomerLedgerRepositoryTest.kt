package io.github.yesidrobayo.shopledger.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import io.github.yesidrobayo.shopledger.data.local.customer.CUSTOMER_BALANCE_SQL
import io.github.yesidrobayo.shopledger.data.local.customer.CUSTOMER_CHAIN_SQL
import io.github.yesidrobayo.shopledger.data.local.sale
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerContact
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerDetails
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerEntryDraft
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerEntrySource
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerLedgerEntry
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerLedgerEntryType
import io.github.yesidrobayo.shopledger.domain.model.customer.NewCustomerLedgerEntry
import io.github.yesidrobayo.shopledger.domain.model.region.RecordTime
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.usecase.customer.CustomerEntryOutcome
import io.github.yesidrobayo.shopledger.domain.usecase.customer.CustomerLedgerWriter
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * El libro del cliente sobre Room: cada fila deja al cliente con su saldo en la misma transacción,
 * el ejemplo de la regla del libro da los mismos saldos, y una secuencia al azar con semilla fija
 * deja las dos consultas del invariante en cero filas después de cada paso.
 */
@RunWith(AndroidJUnit4::class)
internal class CustomerLedgerRepositoryTest : RepositoryTest() {
    private val customers by lazy { RoomCustomerRepository(database) }
    private val ledger by lazy { RoomCustomerLedgerRepository(database) }
    private val writer by lazy { CustomerLedgerWriter(ledger) }

    @Test
    fun theExampleOfTheRuleGivesTheSameBalances() = runBlocking {
        val id = newCustomer("Ana")
        val sales = List(3) { database.saleDao().insert(sale(priceListId).copy(customerId = id)) }
        val due = requireNotNull(BusinessDate.parse("2026-01-31"))
        val balances = mutableListOf<Long>()

        ledger.observeByCustomer(id).test {
            assertEquals(emptyList<CustomerLedgerEntry>(), awaitItem())
            balances += append(credit(id, 5_000, sales[0]).copy(dueDate = due))
            balances += append(entry(id, CustomerLedgerEntryType.PAYMENT, -2_000))
            balances += append(credit(id, 1_500, sales[1]))
            balances += append(entry(id, CustomerLedgerEntryType.PAYMENT, -6_000))
            balances += append(credit(id, 1_000, sales[2]))
            balances += reverse(id, ledger.findByCustomer(id)[3].id)
            awaitValue(ledger.findByCustomer(id))
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals(listOf(5_000L, 3_000L, 4_500L, -1_500L, -500L, 5_500L), balances)
        val rows = ledger.findByCustomer(id)
        assertEquals(balances, rows.map { it.balanceAfter.minor })
        assertEquals(due, rows.first().dueDate)
        assertEquals(Money(5_500), customers.findById(id)?.balance)
        assertEquals(Money(5_500), ledger.lastBalanceAfter(id))
        assertEquals(listOf(rows.first()), ledger.findBySale(sales[0]))
        assertEquals(rows.first(), ledger.findById(rows.first().id))
        assertTrue(ledger.isCompensated(rows[3].id))
        assertEquals(CustomerEntrySource.Reverses(rows[3].id), rows.last().source)
        assertEquals(0L, count("SELECT COUNT(*) FROM ($CUSTOMER_BALANCE_SQL)"))
        assertEquals(0L, count("SELECT COUNT(*) FROM ($CUSTOMER_CHAIN_SQL)"))
    }

    @Test
    fun aRowIsCompensatedOnlyOnceAndAReversalIsNotReversed() = runBlocking {
        val id = newCustomer("Ana")
        val other = newCustomer("Bruno")
        append(entry(id, CustomerLedgerEntryType.OPENING_BALANCE, 3_000, note = "Libreta"))
        val opening = ledger.findByCustomer(id).single().id
        reverse(id, opening)
        val reversal = ledger.findByCustomer(id).last().id

        val outcomes = commit {
            listOf(
                writer.reverse(id, opening, null, TIME),
                writer.reverse(id, reversal, null, TIME),
                writer.reverse(other, opening, null, TIME),
                writer.reverse(id, MISSING_ID, null, TIME),
            )
        }

        assertEquals(
            listOf(
                CustomerEntryOutcome.AlreadyCompensated,
                CustomerEntryOutcome.NotReversible,
                CustomerEntryOutcome.EntryNotFound,
                CustomerEntryOutcome.EntryNotFound,
            ),
            outcomes,
        )
        assertEquals(Money.ZERO, customers.findById(id)?.balance)
    }

    @Test
    fun theDatabaseRefusesWhatTheRulesWouldNotLetThrough() = runBlocking {
        val id = newCustomer("Ana")
        // Lo que el escritor no deja pasar, escrito directo: la base tampoco lo acepta.
        val cases = listOf(
            entry(id, CustomerLedgerEntryType.CREDIT_SALE, 1_000),
            entry(id, CustomerLedgerEntryType.PAYMENT, 1_000),
            entry(id, CustomerLedgerEntryType.ADJUSTMENT, 1_000),
        )
        for (draft in cases) {
            val row =
                NewCustomerLedgerEntry(draft, draft.amount, TIME.occurredAt, TIME.businessDate)
            assertEquals(
                draft.type.name,
                StorageFailure.INVARIANT_VIOLATION,
                storageFailureOf { commit { ledger.append(row) } },
            )
        }
        assertEquals(emptyList<CustomerLedgerEntry>(), ledger.findByCustomer(id))
        assertEquals(Money.ZERO, customers.findById(id)?.balance)
    }

    @Test
    fun theInvariantHoldsAfterEveryStepOfARandomSequence() = runBlocking {
        val ids = listOf(newCustomer("Ana"), newCustomer("Bruno"), newCustomer("Carla"))
        val random = Random(SEED)
        repeat(OPERATIONS) { step ->
            val id = ids.random(random)
            val amount = random.nextLong(1, 50_000)
            val kind = random.nextInt(5)
            val saleId =
                if (kind == 0) {
                    database.saleDao().insert(sale(priceListId).copy(customerId = id))
                } else {
                    null
                }
            commit {
                when (kind) {
                    0 -> writer.append(credit(id, amount, requireNotNull(saleId)), TIME)

                    1 -> writer.append(entry(id, CustomerLedgerEntryType.PAYMENT, -amount), TIME)

                    2 -> {
                        val signed = if (random.nextBoolean()) amount else -amount
                        val note = "Ajuste $step"
                        val adjustment =
                            entry(id, CustomerLedgerEntryType.ADJUSTMENT, signed, note = note)
                        writer.append(adjustment, TIME)
                    }

                    3 -> {
                        val opening = entry(id, CustomerLedgerEntryType.OPENING_BALANCE, amount)
                        writer.append(opening, TIME)
                    }

                    else ->
                        ledger.findByCustomer(id)
                            .filter { it.type != CustomerLedgerEntryType.ENTRY_VOID }
                            .filterNot { ledger.isCompensated(it.id) }
                            .randomOrNull(random)
                            ?.let { writer.reverse(id, it.id, null, TIME) }
                }
            }
            assertEquals("paso $step", 0L, count("SELECT COUNT(*) FROM ($CUSTOMER_BALANCE_SQL)"))
            assertEquals("paso $step", 0L, count("SELECT COUNT(*) FROM ($CUSTOMER_CHAIN_SQL)"))
        }
    }

    private suspend fun newCustomer(name: String): Long = commit {
        val contact = CustomerContact(name, name.lowercase(), null, null, null)
        customers.insert(CustomerDetails(contact, null), AT)
    }

    /** Agrega la fila en su propia transacción y devuelve el saldo que deja. */
    private suspend fun append(draft: CustomerEntryDraft): Long =
        balanceOf(commit { writer.append(draft, TIME) })

    private suspend fun reverse(customerId: Long, entryId: Long): Long =
        balanceOf(commit { writer.reverse(customerId, entryId, null, TIME) })

    private fun balanceOf(outcome: CustomerEntryOutcome): Long =
        (outcome as? CustomerEntryOutcome.Appended)?.balanceAfter?.minor
            ?: throw AssertionError("No entró: $outcome")

    private fun credit(customerId: Long, amount: Long, saleId: Long) = entry(
        customerId,
        CustomerLedgerEntryType.CREDIT_SALE,
        amount,
        CustomerEntrySource.Sale(saleId),
    )

    private fun entry(
        customerId: Long,
        type: CustomerLedgerEntryType,
        amount: Long,
        source: CustomerEntrySource = CustomerEntrySource.None,
        note: String? = null,
    ) = CustomerEntryDraft(customerId, type, Money(amount), source, null, note)

    private companion object {
        const val MISSING_ID = 999L
        const val SEED = 20_261_113L
        const val OPERATIONS = 150
        val TIME = RecordTime(AT, DATE)
    }
}
