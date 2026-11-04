package io.github.yesidrobayo.shopledger.data.local.customer

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.local.LedgerDatabaseTest
import io.github.yesidrobayo.shopledger.data.local.catalog.CREATED_AT
import io.github.yesidrobayo.shopledger.data.local.catalog.Emissions
import io.github.yesidrobayo.shopledger.data.local.catalog.LATER
import io.github.yesidrobayo.shopledger.data.local.catalog.MUCH_LATER
import io.github.yesidrobayo.shopledger.data.local.ids
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Los clientes: cada columna vuelve de su lugar, la lista solo muestra a los vigentes con nombre y
 * en orden estable, editar nunca toca los saldos, y anonimizar borra lo personal sin tocar el
 * libro y no se deshace.
 */
@RunWith(AndroidJUnit4::class)
internal class CustomerDaoTest : LedgerDatabaseTest() {
    private val customers get() = database.customerDao()
    private val ledger get() = database.customerLedgerEntryDao()

    @Test
    fun insertedCustomerReadsBackWithEveryColumn() = runBlocking {
        val wholesale = insertPriceList("mayorista")
        val complete =
            CustomerEntity(
                name = "Ana Pérez",
                normalizedName = "ana perez",
                phone = "+00 555 0101",
                email = "ana@example.com",
                notes = "Paga los viernes",
                priceListId = wholesale,
                balance = 3,
                pointsBalance = -5,
                anonymizedAt = null,
                archivedAt = MUCH_LATER,
                createdAt = CREATED_AT,
                updatedAt = LATER,
            )
        val minimal = customer("Beatriz")

        val completeId = customers.insert(complete)
        val minimalId = customers.insert(minimal)

        assertEquals(complete.copy(id = completeId), customers.findById(completeId))
        assertEquals(minimal.copy(id = minimalId), customers.findById(minimalId))
        assertNull(customers.findById(MISSING_ID))
    }

    @Test
    fun theListShowsActiveNamedCustomersByNameThenById() = runBlocking {
        // Dos clientes se llaman igual: el nombre no es único, y el id desempata.
        val zoe = customers.insert(customer("Zoe"))
        val firstAna = customers.insert(customer("Ana"))
        val secondAna = customers.insert(customer("ANA"))
        customers.insert(customer("Berta", archivedAt = LATER))
        val anonymized = customers.insert(customer("Carla"))
        customers.anonymize(anonymized, anonymizedAt = LATER)

        val emissions = Emissions(this, customers.observeActive().ids { it.id })
        try {
            emissions.awaitValue(listOf(firstAna, secondAna, zoe))

            val bruno = customers.insert(customer("Bruno"))
            emissions.awaitValue(listOf(firstAna, secondAna, bruno, zoe))
            customers.setArchivedAt(secondAna, archivedAt = MUCH_LATER, updatedAt = MUCH_LATER)
            emissions.awaitValue(listOf(firstAna, bruno, zoe))
        } finally {
            emissions.stop()
        }
    }

    @Test
    fun editingWhatThePersonWritesNeverTouchesTheBalances() = runBlocking {
        val id = customers.insert(customer("Ana"))
        customers.setBalance(id, balance = 4_500, updatedAt = LATER)
        customers.setPointsBalance(id, pointsBalance = 62, updatedAt = LATER)
        val wholesale = insertPriceList("mayorista")

        val changed =
            customers.update(
                id,
                name = "Ana María",
                normalizedName = "ana maria",
                phone = "+00 555 0102",
                email = "ana.maria@example.com",
                notes = "Nueva dirección de entrega",
                priceListId = wholesale,
                updatedAt = MUCH_LATER,
            )

        assertEquals(1, changed)
        val stored = requireNotNull(customers.findById(id))
        assertEquals(4_500L, stored.balance)
        assertEquals(62L, stored.pointsBalance)
        assertEquals("ana maria", stored.normalizedName)
        assertEquals(wholesale, stored.priceListId)
        assertEquals(CREATED_AT, stored.createdAt)
        assertEquals(MUCH_LATER, stored.updatedAt)
    }

    @Test
    fun anonymizingErasesThePersonalDataKeepsTheLedgerAndCannotBeUndone() = runBlocking {
        val id =
            customers.insert(
                customer("Ana").copy(
                    phone = "+00 555 0101",
                    email = "ana@example.com",
                    notes = "Paga los viernes",
                ),
            )
        val entry = customerEntry(id, "OPENING_BALANCE", 5_000, 5_000).copy(note = "Saldo viejo")
        val entryId = database.appendCustomerEntry(entry)
        database.appendLoyaltyEntry(loyaltyEntry(id, "ADJUSTMENT", 150, 150))

        assertEquals(1, customers.anonymize(id, anonymizedAt = LATER))

        val anonymized = requireNotNull(customers.findById(id))
        assertEquals(
            customer("Ana").copy(
                id = id,
                name = null,
                normalizedName = null,
                balance = 5_000,
                pointsBalance = 150,
                anonymizedAt = LATER,
                updatedAt = LATER,
            ),
            anonymized,
        )
        // El libro, sus montos y sus notas no cambian.
        assertEquals(entry.copy(id = entryId), ledger.findById(entryId))
        // No se deshace: anonimizar otra vez no cambia su instante, y editarlo no le pone nombre.
        assertEquals(0, customers.anonymize(id, anonymizedAt = MUCH_LATER))
        assertEquals(
            0,
            customers.update(id, "Ana", "ana", null, null, null, null, updatedAt = MUCH_LATER),
        )
        assertEquals(anonymized, customers.findById(id))
        // Sus saldos siguen su libro.
        database.appendCustomerEntry(customerEntry(id, "PAYMENT", -5_000, 0))
        assertEquals(0L, customers.findById(id)?.balance)
    }

    private companion object {
        const val MISSING_ID = 999L
    }
}
