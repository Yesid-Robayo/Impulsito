package io.github.yesidrobayo.shopledger.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.backup.SnapshotCreator
import io.github.yesidrobayo.shopledger.data.backup.SnapshotResult
import io.github.yesidrobayo.shopledger.data.local.customer.CUSTOMER_BALANCE_SQL
import io.github.yesidrobayo.shopledger.data.local.customer.CUSTOMER_CHAIN_SQL
import io.github.yesidrobayo.shopledger.data.local.customer.POINTS_BALANCE_SQL
import io.github.yesidrobayo.shopledger.data.local.customer.POINTS_CHAIN_SQL
import io.github.yesidrobayo.shopledger.data.local.customer.appendCustomerEntry
import io.github.yesidrobayo.shopledger.data.local.customer.appendLoyaltyEntry
import io.github.yesidrobayo.shopledger.data.local.customer.customerEntry
import io.github.yesidrobayo.shopledger.data.local.customer.loyaltyEntry
import io.github.yesidrobayo.shopledger.data.local.document.order
import io.github.yesidrobayo.shopledger.data.local.sale
import io.github.yesidrobayo.shopledger.data.transaction.FIXED_CLOCK
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerContact
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerDetails
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.usecase.customer.AnonymizeCustomerResult
import io.github.yesidrobayo.shopledger.domain.usecase.customer.AnonymizeCustomerUseCase
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Anonimizar sobre Room: borra los datos personales del cliente y la entrega de sus pedidos, deja
 * sus libros y sus ventas como estaban, y una copia nueva de la base ya no los trae.
 */
@RunWith(AndroidJUnit4::class)
internal class CustomerAnonymizeTest : RepositoryTest() {
    private val customers by lazy { RoomCustomerRepository(database) }
    private val anonymize by lazy { AnonymizeCustomerUseCase(runner, customers, FIXED_CLOCK) }

    @Test
    fun thePersonalDataGoesAndTheLedgersStayAsTheyWere() = runBlocking {
        val id = commit { customers.insert(details(), AT) }
        val other = commit { customers.insert(details(name = "Otro Cliente"), AT) }
        database.appendCustomerEntry(
            customerEntry(id, "OPENING_BALANCE", 5_000, 5_000).copy(note = LEDGER_NOTE),
        )
        database.appendLoyaltyEntry(loyaltyEntry(id, "ADJUSTMENT", 150, 150))
        val saleId = database.saleDao().insert(sale(priceListId).copy(customerId = id))
        val pending = database.orderDao().insert(
            order().copy(customerId = id, deliveryAddress = ADDRESS, deliveryNote = DELIVERY_NOTE),
        )
        val cancelled = database.orderDao().insert(
            order().copy(customerId = id, deliveryAddress = ADDRESS, deliveryNote = null),
        )
        database.orderDao().markCancelled(cancelled, LATER_AT.toEpochMilliseconds())
        val othersOrder = database.orderDao().insert(order().copy(customerId = other))
        val ledgerBefore = database.customerLedgerEntryDao().findByCustomer(id)
        val pointsBefore = count("SELECT COUNT(*) FROM loyalty_ledger_entries")
        val changesBefore = changeSeq()

        assertEquals(AnonymizeCustomerResult.Done, anonymize(id))

        val stored = requireNotNull(customers.findById(id))
        assertNull(stored.contact)
        assertTrue(stored.isAnonymized)
        assertEquals(Money(5_000), stored.balance)
        assertEquals(150L, stored.pointsBalance)
        assertEquals(ledgerBefore, database.customerLedgerEntryDao().findByCustomer(id))
        assertEquals(pointsBefore, count("SELECT COUNT(*) FROM loyalty_ledger_entries"))
        assertEquals(id, database.saleDao().findById(saleId)?.customerId)
        for (orderId in listOf(pending, cancelled)) {
            val order = requireNotNull(database.orderDao().findById(orderId))
            assertNull(order.deliveryAddress)
            assertNull(order.deliveryNote)
            assertEquals(id, order.customerId)
        }
        assertEquals("CANCELLED", database.orderDao().findById(cancelled)?.status)
        assertEquals(
            order().deliveryAddress,
            database.orderDao().findById(othersOrder)?.deliveryAddress,
        )
        assertEquals("Otro Cliente", customers.findById(other)?.contact?.name)
        for (sql in listOf(
            CUSTOMER_BALANCE_SQL,
            CUSTOMER_CHAIN_SQL,
            POINTS_BALANCE_SQL,
            POINTS_CHAIN_SQL,
        )) {
            assertEquals(sql, 0L, count("SELECT COUNT(*) FROM ($sql)"))
        }
        assertEquals(changesBefore + 1, changeSeq())
    }

    @Test
    fun aNewCopyOfTheDatabaseNoLongerHasThePersonalData() = runBlocking {
        val id = commit { customers.insert(details(), AT) }
        database.orderDao().insert(
            order().copy(customerId = id, deliveryAddress = ADDRESS, deliveryNote = DELIVERY_NOTE),
        )
        assertEquals(AnonymizeCustomerResult.Done, anonymize(id))

        val snapshot = File(context.cacheDir, "customer-anonymize-snapshot.db")
        try {
            val created = SnapshotCreator(file, Dispatchers.IO).create(snapshot)
            assertTrue(created is SnapshotResult.Created)
            val bytes = String(snapshot.readBytes(), Charsets.ISO_8859_1)
            for (secret in listOf(NAME, PHONE, EMAIL, NOTES, ADDRESS, DELIVERY_NOTE)) {
                val encoded = String(secret.toByteArray(Charsets.UTF_8), Charsets.ISO_8859_1)
                assertFalse(secret, bytes.contains(encoded))
            }
        } finally {
            snapshot.delete()
        }
    }

    @Test
    fun anonymizingTwiceIsRefusedAndKeepsTheFirstInstant() = runBlocking {
        val id = commit { customers.insert(details(), AT) }
        assertEquals(AnonymizeCustomerResult.Done, anonymize(id))
        val first = customers.findById(id)?.anonymizedAt

        assertEquals(AnonymizeCustomerResult.AlreadyAnonymized, anonymize(id))
        assertEquals(
            StorageFailure.INVARIANT_VIOLATION,
            storageFailureOf { commit { customers.anonymize(id, LATER_AT) } },
        )
        assertEquals(first, customers.findById(id)?.anonymizedAt)
        assertEquals(AnonymizeCustomerResult.NotFound, anonymize(MISSING_ID))
    }

    private fun details(name: String = NAME): CustomerDetails = CustomerDetails(
        CustomerContact(name, name.lowercase(), PHONE, EMAIL, NOTES),
        priceListId = null,
    )

    private companion object {
        const val NAME = "Clara Inventada 4F2A"
        const val PHONE = "5550199123"
        const val EMAIL = "clara.4f2a@example.com"
        const val NOTES = "Prefiere pagar los viernes 7C1D"
        const val ADDRESS = "Calle Inventada 9E3B"
        const val DELIVERY_NOTE = "Timbre azul 2A8F"
        const val LEDGER_NOTE = "Saldo de la libreta anterior"
        const val MISSING_ID = 999L
    }
}
