package io.github.yesidrobayo.shopledger.domain.usecase.customer

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.customer.Customer
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerContact
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerForm
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException
import io.github.yesidrobayo.shopledger.domain.testing.FakeCustomerRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakeTransactionRunner
import io.github.yesidrobayo.shopledger.domain.testing.FixedClock
import io.github.yesidrobayo.shopledger.domain.testing.InMemoryDatabase
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Anonimizar un cliente: borra lo personal, conserva saldos y libros, y no se deshace. */
class AnonymizeCustomerUseCaseTest {
    private val database = InMemoryDatabase()
    private val tx = FakeTransactionRunner(database)
    private val customers = FakeCustomerRepository(database)
    private val anonymize = AnonymizeCustomerUseCase(tx, customers, FixedClock(NOW))

    @Test
    fun `the personal data goes and the balances stay, in one transaction`() = runTest {
        seed(customer(balance = 4_000L, points = 12L))

        assertEquals(AnonymizeCustomerResult.Done, anonymize(ID))

        val stored = database.tables.value.customers.getValue(ID)
        assertNull(stored.contact)
        assertNull(stored.details)
        assertEquals(NOW, stored.anonymizedAt)
        assertEquals(Money(4_000L), stored.balance)
        assertEquals(12L, stored.pointsBalance)
        assertEquals(2L, stored.priceListId)
        assertEquals(1, tx.writeCalls)
        assertEquals(1L, database.tables.value.changeSeq)
    }

    @Test
    fun `it cannot be undone, and editing afterwards is refused`() = runTest {
        seed(customer())
        anonymize(ID)
        val before = database.tables.value

        assertEquals(AnonymizeCustomerResult.AlreadyAnonymized, anonymize(ID))
        assertEquals(
            SaveCustomerResult.Anonymized,
            EditCustomerUseCase(tx, customers, FixedClock(NOW))(ID, CustomerForm("Ana")),
        )
        assertEquals(before, database.tables.value)
    }

    @Test
    fun `a missing customer or a storage failure leaves everything as it was`() = runTest {
        seed(customer())
        val before = database.tables.value

        assertEquals(AnonymizeCustomerResult.NotFound, anonymize(99L))
        database.failAtWrite = 1
        database.failure = { StorageFailureException(StorageFailure.DISK_FULL) }
        assertEquals(
            AnonymizeCustomerResult.StorageFailed(StorageFailure.DISK_FULL),
            anonymize(ID),
        )
        assertEquals(before, database.tables.value)
    }

    @Test
    fun `a customer with a balance says so before anonymizing`() {
        assertTrue(customer(balance = 4_000L).hasBalance)
        assertTrue(customer(balance = -1_500L).hasBalance)
        // Los puntos no son dinero: sin saldo, no hay deuda que avisar.
        assertFalse(customer(points = 12L).hasBalance)
    }

    private fun seed(customer: Customer) {
        database.seed {
            it.copy(
                customers = it.customers + (customer.id to customer),
                lastIds = it.lastIds + ("customers" to customer.id),
            )
        }
    }

    private fun customer(balance: Long = 0L, points: Long = 0L): Customer = Customer(
        id = ID,
        contact = CustomerContact("Ana Ríos", "ana rios", "555 0101", "ana@example.com", "Notas"),
        priceListId = 2L,
        balance = Money(balance),
        pointsBalance = points,
        anonymizedAt = null,
        archivedAt = null,
        createdAt = EARLIER,
        updatedAt = EARLIER,
    )

    private companion object {
        const val ID = 1L
        val NOW: Instant = Instant.fromEpochMilliseconds(1_762_000_000_000L)
        val EARLIER: Instant = NOW - 5.hours
    }
}
