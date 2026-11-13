package io.github.yesidrobayo.shopledger.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import io.github.yesidrobayo.shopledger.data.local.catalog.CatalogDatabaseTest
import io.github.yesidrobayo.shopledger.data.local.customer.appendCustomerEntry
import io.github.yesidrobayo.shopledger.data.local.customer.appendLoyaltyEntry
import io.github.yesidrobayo.shopledger.data.local.customer.customerEntry
import io.github.yesidrobayo.shopledger.data.local.customer.loyaltyEntry
import io.github.yesidrobayo.shopledger.data.local.document.order
import io.github.yesidrobayo.shopledger.data.local.document.quote
import io.github.yesidrobayo.shopledger.data.local.sale
import io.github.yesidrobayo.shopledger.data.local.sale.SaleDraftEntity
import io.github.yesidrobayo.shopledger.data.transaction.BackupStateChangeCounter
import io.github.yesidrobayo.shopledger.data.transaction.runnerFor
import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.customer.Customer
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerContact
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerDetails
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.rule.TextNormalizer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Los clientes: lo que se guarda, cuándo emiten, cómo se ve un anonimizado y qué es su historia. */
@RunWith(AndroidJUnit4::class)
internal class CustomerRepositoryTest : RepositoryTest() {
    private val customers by lazy { RoomCustomerRepository(database) }

    @Test
    fun aCustomerComesBackWithZeroBalancesAndEmitsEachChange() = runBlocking {
        customers.observe(ArchiveFilter.ACTIVE).test {
            assertEquals(emptyList<Customer>(), awaitItem())

            val id = commit { customers.insert(details("Ana Ríos", phone = "555 0101"), AT) }
            val created =
                Customer(
                    id = id,
                    contact = contact("Ana Ríos", phone = "555 0101"),
                    priceListId = null,
                    balance = Money.ZERO,
                    pointsBalance = 0L,
                    anonymizedAt = null,
                    archivedAt = null,
                    createdAt = AT,
                    updatedAt = AT,
                )
            assertEquals(listOf(created), awaitItem())
            assertEquals(created, customers.findById(id))
            assertEquals(created, customers.observeById(id).first())
            assertEquals(listOf(created), customers.findActiveByNormalizedName("ana rios"))

            commit {
                customers.update(id, details("Ana Paz", priceListId = priceListId), LATER_AT)
            }
            val edited = awaitItem().single()
            assertEquals(contact("Ana Paz"), edited.contact)
            assertEquals(priceListId, edited.priceListId)
            assertEquals(LATER_AT, edited.updatedAt)

            commit { customers.setArchivedAt(id, LATER_AT, LATER_AT) }
            assertEquals(emptyList<Customer>(), awaitItem())
            assertEquals(
                listOf(LATER_AT),
                customers.observe(ArchiveFilter.ARCHIVED).first().map { it.archivedAt },
            )
            assertEquals(emptyList<Customer>(), customers.findActiveByNormalizedName("ana paz"))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun twoCustomersMayShareANameAndTheAnonymizedGoLast() = runBlocking {
        val zoe = commit { customers.insert(details("Zoe"), AT) }
        val firstAna = commit { customers.insert(details("Ana"), AT) }
        val secondAna = commit { customers.insert(details("ANA"), AT) }
        val gone = commit { customers.insert(details("Bruno", phone = "555 0199"), AT) }
        database.customerDao().anonymize(gone, LATER_AT.toEpochMilliseconds())

        assertEquals(
            listOf(firstAna, secondAna),
            customers.findActiveByNormalizedName("ana").map { it.id },
        )
        val list = customers.observe(ArchiveFilter.ACTIVE).first()
        assertEquals(listOf(firstAna, secondAna, zoe, gone), list.map { it.id })
        val anonymous = list.last()
        assertNull(anonymous.contact)
        assertTrue(anonymous.isAnonymized)
        assertEquals(
            listOf(firstAna, secondAna, zoe),
            customers.observeSelectable().first().map { it.id },
        )
    }

    @Test
    fun recentCustomersAreThoseOfTheNewestActiveSalesThatCanStillBeSoldTo() = runBlocking {
        val sales = database.saleDao()
        suspend fun soldTo(id: Long?): Long = sales.insert(sale(priceListId).copy(customerId = id))
        val ana = commit { customers.insert(details("Ana"), AT) }
        val bruno = commit { customers.insert(details("Bruno"), AT) }
        val carla = commit { customers.insert(details("Carla"), AT) }
        val dani = commit { customers.insert(details("Dani"), AT) }
        val eva = commit { customers.insert(details("Eva"), AT) }

        customers.observeRecent(3).test {
            assertEquals(emptyList<Customer>(), awaitItem())
            soldTo(ana)
            assertEquals(listOf(ana), awaitItem().map { it.id })
            cancelAndIgnoreRemainingEvents()
        }
        soldTo(bruno)
        soldTo(ana)
        soldTo(null)
        // Una venta anulada no cuenta, ni un archivado ni un anonimizado: no se les vende.
        val voided = soldTo(carla)
        sales.markVoided(voided, LATER_AT.toEpochMilliseconds(), "ENTRY_ERROR", null)
        soldTo(dani)
        commit { customers.setArchivedAt(dani, LATER_AT, LATER_AT) }
        soldTo(eva)
        database.customerDao().anonymize(eva, LATER_AT.toEpochMilliseconds())

        assertEquals(listOf(ana, bruno), customers.observeRecent(3).first().map { it.id })

        // Primero el de la venta más nueva, por id, hasta el límite.
        val fer = commit { customers.insert(details("Fer"), AT) }
        val gabi = commit { customers.insert(details("Gabi"), AT) }
        soldTo(fer)
        soldTo(gabi)
        assertEquals(listOf(gabi, fer, ana), customers.observeRecent(3).first().map { it.id })
    }

    @Test
    fun anAnonymizedCustomerIsNotEditedAndTheWriteIsUndone() = runBlocking {
        val id = commit { customers.insert(details("Ana"), AT) }
        database.customerDao().anonymize(id, LATER_AT.toEpochMilliseconds())
        val before = changeSeq()

        assertEquals(
            StorageFailure.INVARIANT_VIOLATION,
            storageFailureOf { commit { customers.update(id, details("Otra"), LATER_AT) } },
        )
        assertNull(customers.findById(id)?.contact)
        assertEquals(before, changeSeq())
    }

    @Test
    fun salesOrdersQuotesAndBothLedgersCountAsHistory() = runBlocking {
        val cases: Map<String, suspend (Long) -> Unit> =
            mapOf(
                "venta" to
                    { id -> database.saleDao().insert(sale(priceListId).copy(customerId = id)) },
                "pedido" to { id -> database.orderDao().insert(order().copy(customerId = id)) },
                "cotización" to
                    { id -> database.quoteDao().insert(quote().copy(customerId = id)) },
                "libro" to
                    { id ->
                        database.appendCustomerEntry(
                            customerEntry(id, "OPENING_BALANCE", 5_000, 5_000),
                        )
                    },
                "puntos" to
                    { id ->
                        database.appendLoyaltyEntry(loyaltyEntry(id, "ADJUSTMENT", 150, 150))
                    },
            )
        for ((case, addHistory) in cases) {
            val id = commit { customers.insert(details("Cliente de $case"), AT) }
            assertFalse(case, customers.hasHistory(id))

            addHistory(id)

            assertTrue(case, customers.hasHistory(id))
            assertEquals(
                case,
                StorageFailure.INVARIANT_VIOLATION,
                storageFailureOf { commit { customers.delete(id) } },
            )
            assertTrue(case, customers.findById(id) != null)
        }
    }

    @Test
    fun deletingWithoutHistoryLeavesAHeldSaleWithoutCustomer() = runBlocking {
        val id = commit { customers.insert(details("Ana"), AT) }
        database.saleDraftDao().insert(
            SaleDraftEntity(
                label = null,
                customerId = id,
                priceListId = null,
                createdAt = AT.toEpochMilliseconds(),
                updatedAt = AT.toEpochMilliseconds(),
            ),
        )
        assertFalse(customers.hasHistory(id))

        commit { customers.delete(id) }

        assertNull(customers.findById(id))
        assertEquals(1L, count("SELECT COUNT(*) FROM sale_drafts WHERE customer_id IS NULL"))
    }

    @Test
    fun theDatabaseRejectsWhatTheFormWouldNotLetThrough() = runBlocking {
        assertEquals(
            StorageFailure.INVARIANT_VIOLATION,
            storageFailureOf { commit { customers.insert(details("a".repeat(81)), AT) } },
        )
        assertEquals(0, customers.observe(ArchiveFilter.ACTIVE).first().size)
    }

    private fun contact(name: String, phone: String? = null): CustomerContact =
        CustomerContact(name, TextNormalizer.normalize(name), phone, null, null)

    private fun details(
        name: String,
        phone: String? = null,
        priceListId: Long? = null,
    ): CustomerDetails = CustomerDetails(contact(name, phone), priceListId)
}

/**
 * Los conteos neutros del respaldo cuentan clientes: una base que solo tiene un cliente ya no está
 * vacía, y el cliente suma al catálogo, también anonimizado. Sin producto ni otra fila del negocio,
 * para que solo decida el cliente.
 */
@RunWith(AndroidJUnit4::class)
internal class CustomerCountsTest : CatalogDatabaseTest() {
    @Test
    fun aSingleCustomerCountsAsCatalogAndTheDatabaseIsNoLongerEmpty() = runBlocking {
        val runner = runnerFor(database, BackupStateChangeCounter(database.backupStateDao()))
        val customers = RoomCustomerRepository(database)
        val counts = database.backupStateDao()
        assertTrue(counts.isEmpty())
        assertEquals(0L, counts.catalogCount())

        val id =
            runner.write {
                TxResult.Commit(
                    customers.insert(
                        CustomerDetails(CustomerContact("Ana", "ana", null, null, null), null),
                        AT,
                    ),
                )
            }

        assertFalse(counts.isEmpty())
        assertEquals(1L, counts.catalogCount())
        // Anonimizar no lo saca del catálogo: la fila sigue, con su libro.
        database.customerDao().anonymize(id, LATER_AT.toEpochMilliseconds())
        assertFalse(counts.isEmpty())
        assertEquals(1L, counts.catalogCount())
    }
}
