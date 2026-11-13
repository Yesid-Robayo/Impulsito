package io.github.yesidrobayo.shopledger.domain.usecase.customer

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.customer.Customer
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerContact
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerDetails
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerForm
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerFormIssue
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException
import io.github.yesidrobayo.shopledger.domain.rule.TextNormalizer
import io.github.yesidrobayo.shopledger.domain.testing.FakeCustomerRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakeTransactionRunner
import io.github.yesidrobayo.shopledger.domain.testing.FixedClock
import io.github.yesidrobayo.shopledger.domain.testing.InMemoryDatabase
import io.github.yesidrobayo.shopledger.domain.testing.Tables
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Crear, editar, archivar, reactivar y eliminar clientes con lo mínimo, y leer su lista. */
class CustomerUseCasesTest {
    private val database = InMemoryDatabase()
    private val tx = FakeTransactionRunner(database)
    private val customers = FakeCustomerRepository(database)
    private val clock = FixedClock(NOW)
    private val create = CreateCustomerUseCase(tx, customers, clock)
    private val edit = EditCustomerUseCase(tx, customers, clock)
    private val archive = ArchiveCustomerUseCase(tx, customers, clock)
    private val reactivate = ReactivateCustomerUseCase(tx, customers, clock)
    private val delete = DeleteCustomerUseCase(tx, customers)
    private val tables: Tables get() = database.tables.value

    @Test
    fun `only a name creates a customer, with zero balances, in one transaction`() = runTest {
        val result = create(CustomerForm(" Ana Ríos "))

        val id = (result as SaveCustomerResult.Saved).customerId
        val stored = tables.customers.getValue(id)
        assertEquals(CustomerContact("Ana Ríos", "ana rios", null, null, null), stored.contact)
        assertEquals(Money.ZERO, stored.balance)
        assertEquals(0L, stored.pointsBalance)
        assertEquals(NOW, stored.createdAt)
        assertEquals(1L, tables.changeSeq)
        assertEquals(1, tx.writeCalls)
    }

    @Test
    fun `the same name warns without writing, and goes through once the person saw it`() = runTest {
        seed(customer(1L, "Ana Ríos"))
        val before = tables

        assertEquals(SaveCustomerResult.SameName, create(CustomerForm("ANA RIOS")))
        assertEquals(before, tables)

        val saved = create(CustomerForm("ana ríos", phone = "555 0101"), allowSameName = true)
        assertEquals(SaveCustomerResult.Saved(2L), saved)
        assertEquals(2, tables.customers.size)
    }

    @Test
    fun `an archived or anonymized customer does not warn about the name`() = runTest {
        seed(customer(1L, "Ana Ríos", archivedAt = EARLIER))
        seed(customer(2L, null, anonymizedAt = EARLIER))

        assertEquals(SaveCustomerResult.Saved(3L), create(CustomerForm("Ana Ríos")))
    }

    @Test
    fun `an invalid customer is rejected with every reason and nothing is written`() = runTest {
        seed(customer(1L, "Ana Ríos"))
        val before = tables

        assertEquals(
            SaveCustomerResult.Invalid(
                setOf(CustomerFormIssue.NAME_EMPTY, CustomerFormIssue.EMAIL_FORMAT),
            ),
            create(CustomerForm("", email = "ana")),
        )
        assertEquals(
            SaveCustomerResult.Invalid(setOf(CustomerFormIssue.PHONE_FORMAT)),
            edit(1L, CustomerForm("Ana Ríos", phone = "uno")),
        )
        assertEquals(before, tables)
    }

    @Test
    fun `editing keeps the id and the balances, and saving unchanged writes nothing`() = runTest {
        seed(customer(1L, "Ana Ríos", balance = 4_000L, points = 12L))

        assertEquals(
            SaveCustomerResult.Saved(1L),
            edit(1L, CustomerForm("Ana Ríos", phone = "555 0101", priceListId = 2L)),
        )
        val stored = tables.customers.getValue(1L)
        assertEquals("555 0101", stored.contact?.phone)
        assertEquals(2L, stored.priceListId)
        assertEquals(Money(4_000L), stored.balance)
        assertEquals(12L, stored.pointsBalance)
        assertEquals(NOW, stored.updatedAt)
        val before = tables

        assertEquals(
            SaveCustomerResult.Saved(1L),
            edit(1L, CustomerForm(" Ana Ríos ", phone = " 555 0101 ", priceListId = 2L)),
        )
        assertEquals(before, tables)
    }

    @Test
    fun `renaming to another active name warns, but keeping its own never does`() = runTest {
        seed(customer(1L, "Ana Ríos"))
        seed(customer(2L, "Bruno Paz"))
        seed(customer(3L, "Bruno Paz"))
        val before = tables

        assertEquals(SaveCustomerResult.SameName, edit(1L, CustomerForm("bruno paz")))
        assertEquals(before, tables)
        // Bruno ya comparte su nombre con otro: cambiar su teléfono no avisa.
        assertEquals(
            SaveCustomerResult.Saved(2L),
            edit(2L, CustomerForm("Bruno Paz", phone = "555")),
        )
        // Cambiar solo las tildes o mayúsculas de su propio nombre tampoco.
        assertEquals(SaveCustomerResult.Saved(1L), edit(1L, CustomerForm("ANA RIOS")))
        assertEquals(
            SaveCustomerResult.Saved(1L),
            edit(1L, CustomerForm("Bruno Paz"), allowSameName = true),
        )
        assertEquals("Bruno Paz", tables.customers.getValue(1L).contact?.name)
    }

    @Test
    fun `a missing or anonymized customer is not edited`() = runTest {
        seed(customer(1L, null, anonymizedAt = EARLIER))
        val before = tables

        assertEquals(SaveCustomerResult.NotFound, edit(99L, CustomerForm("Otro")))
        assertEquals(SaveCustomerResult.Anonymized, edit(1L, CustomerForm("Ana")))
        assertEquals(before, tables)
    }

    @Test
    fun `an archived customer can still be corrected`() = runTest {
        seed(customer(1L, "Ana Ríos", archivedAt = EARLIER))

        assertEquals(SaveCustomerResult.Saved(1L), edit(1L, CustomerForm("Ana Ríos", phone = "")))
        assertEquals(EARLIER, tables.customers.getValue(1L).archivedAt)
    }

    @Test
    fun `archiving and reactivating keep the history and say when nothing changes`() = runTest {
        seed(customer(1L, "Ana Ríos", balance = 4_000L))
        database.seed { it.copy(customersWithHistory = setOf(1L)) }

        assertEquals(CustomerLifecycleResult.Done, archive(1L))
        assertEquals(NOW, tables.customers.getValue(1L).archivedAt)
        assertEquals(Money(4_000L), tables.customers.getValue(1L).balance)
        assertEquals(CustomerLifecycleResult.Unchanged, archive(1L))

        // Un nombre repetido no impide reactivar: el nombre no es único.
        seed(customer(2L, "Ana Ríos"))
        assertEquals(CustomerLifecycleResult.Done, reactivate(1L))
        assertNull(tables.customers.getValue(1L).archivedAt)
        assertEquals(CustomerLifecycleResult.Unchanged, reactivate(1L))

        assertEquals(CustomerLifecycleResult.NotFound, archive(99L))
        assertEquals(CustomerLifecycleResult.NotFound, reactivate(99L))
        assertEquals(2L, tables.changeSeq)
    }

    @Test
    fun `only a customer without history is deleted, never on its own`() = runTest {
        seed(customer(1L, "Ana Ríos"))
        seed(customer(2L, "Bruno Paz"))
        database.seed { it.copy(customersWithHistory = setOf(2L)) }

        assertEquals(CustomerLifecycleResult.HasHistory, delete(2L))
        assertTrue(2L in tables.customers)
        assertEquals(CustomerLifecycleResult.Done, delete(1L))
        assertFalse(1L in tables.customers)
        assertEquals(CustomerLifecycleResult.NotFound, delete(1L))
    }

    @Test
    fun `a storage failure is returned typed, and nothing stays written`() = runTest {
        seed(customer(1L, "Ana Ríos"))
        database.failAtWrite = 1
        database.failure = { StorageFailureException(StorageFailure.DISK_FULL) }
        val before = tables

        val failed = SaveCustomerResult.StorageFailed(StorageFailure.DISK_FULL)
        assertEquals(failed, create(CustomerForm("Bruno Paz")))
        assertEquals(failed, edit(1L, CustomerForm("Ana Paz")))
        val lifecycle = CustomerLifecycleResult.StorageFailed(StorageFailure.DISK_FULL)
        assertEquals(lifecycle, archive(1L))
        assertEquals(lifecycle, delete(1L))
        assertEquals(before, tables)

        database.failAtWrite = null
        tx.restoreInProgress = true
        assertEquals(
            CustomerLifecycleResult.StorageFailed(StorageFailure.RESTORE_IN_PROGRESS),
            reactivate(1L),
        )
    }

    @Test
    fun `the list splits active and archived and adds up only what is owed`() = runTest {
        seed(customer(1L, "Bruno Paz", balance = 4_000L))
        seed(customer(2L, "Ana Ríos", balance = -1_500L))
        seed(customer(3L, null, balance = 2_000L, anonymizedAt = EARLIER))
        seed(customer(4L, "Carla Mena", balance = 500L, archivedAt = EARLIER))

        val overview = ObserveCustomersUseCase(customers)().first()

        assertEquals(listOf(2L, 1L, 3L), overview.active.map { it.id })
        assertEquals(listOf(4L), overview.archived.map { it.id })
        // El anonimizado y el archivado siguen debiendo; el saldo a favor no resta.
        assertEquals(Money(6_500L), overview.receivable)
        assertEquals(1L, ObserveCustomerUseCase(customers)(1L).first()?.id)
        assertNull(ObserveCustomerUseCase(customers)(99L).first())
    }

    @Test
    fun `an absurd total does not break the list`() = runTest {
        seed(customer(1L, "Ana", balance = Long.MAX_VALUE))
        seed(customer(2L, "Bruno", balance = 1L))

        assertNull(ObserveCustomersUseCase(customers)().first().receivable)
    }

    @Test
    fun `the selectable ones leave out the archived and the anonymized`() = runTest {
        seed(customer(1L, "Ana"))
        seed(customer(2L, "Bruno", archivedAt = EARLIER))
        seed(customer(3L, null, anonymizedAt = EARLIER))

        assertEquals(listOf(1L), customers.observeSelectable().first().map { it.id })
    }

    @Test
    fun `a customer knows if it is archived or anonymized, and what can be edited`() {
        val plain = customer(1L, "Ana", priceList = 2L)
        val gone = customer(2L, null, anonymizedAt = EARLIER, archivedAt = EARLIER)

        assertFalse(plain.isArchived)
        assertFalse(plain.isAnonymized)
        assertEquals(CustomerDetails(requireNotNull(plain.contact), 2L), plain.details)
        assertTrue(gone.isArchived)
        assertTrue(gone.isAnonymized)
        assertNull(gone.details)
    }

    private fun seed(customer: Customer) {
        database.seed { it.copy(customers = it.customers + (customer.id to customer)) }
        database.seed { it.copy(lastIds = it.lastIds + ("customers" to customer.id)) }
    }

    private fun customer(
        id: Long,
        name: String?,
        balance: Long = 0L,
        points: Long = 0L,
        priceList: Long? = null,
        anonymizedAt: Instant? = null,
        archivedAt: Instant? = null,
    ): Customer = Customer(
        id = id,
        contact = name?.let {
            CustomerContact(it, TextNormalizer.normalize(it), null, null, null)
        },
        priceListId = priceList,
        balance = Money(balance),
        pointsBalance = points,
        anonymizedAt = anonymizedAt,
        archivedAt = archivedAt,
        createdAt = EARLIER,
        updatedAt = EARLIER,
    )

    private companion object {
        val NOW: Instant = Instant.fromEpochMilliseconds(1_762_000_000_000L)
        val EARLIER: Instant = NOW - 5.hours
    }
}
