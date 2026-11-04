package io.github.yesidrobayo.shopledger.data.local.stock

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.data.local.LedgerDatabaseTest
import io.github.yesidrobayo.shopledger.data.local.catalog.CREATED_AT
import io.github.yesidrobayo.shopledger.data.local.catalog.Emissions
import io.github.yesidrobayo.shopledger.data.local.catalog.FOREIGN_KEY
import io.github.yesidrobayo.shopledger.data.local.catalog.LATER
import io.github.yesidrobayo.shopledger.data.local.catalog.MUCH_LATER
import io.github.yesidrobayo.shopledger.data.local.catalog.UNIQUE
import io.github.yesidrobayo.shopledger.data.local.catalog.constraintFailure
import io.github.yesidrobayo.shopledger.data.local.catalog.guardFailure
import io.github.yesidrobayo.shopledger.data.local.foreignKeysOf
import io.github.yesidrobayo.shopledger.data.local.ids
import io.github.yesidrobayo.shopledger.data.local.indexesOf
import io.github.yesidrobayo.shopledger.data.local.openingBalance
import io.github.yesidrobayo.shopledger.data.local.purchase
import io.github.yesidrobayo.shopledger.data.local.sale
import io.github.yesidrobayo.shopledger.data.local.stock.StockBalanceEntity.Companion.NO_LOT
import io.github.yesidrobayo.shopledger.domain.port.Clock
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Las ubicaciones: la inicial `MAIN`, que nace con la base sin nombre y nunca se archiva ni se
 * borra, junto a las de la persona; clave y nombre únicos; orden por creación; archivo y
 * reactivación, y una ubicación con stock, compras o ventas, que no se borra. Además, los índices y
 * las llaves de ubicaciones, lotes y saldos.
 */
@RunWith(AndroidJUnit4::class)
internal class LocationDaoTest : LedgerDatabaseTest() {
    private val locations get() = database.locationDao()

    @Test
    fun aNewDatabaseHasOnlyTheMainLocationWithoutAName() = runBlocking {
        assertEquals(
            listOf(
                LocationEntity(
                    id = MAIN_LOCATION_ID,
                    systemKey = "MAIN",
                    name = null,
                    normalizedName = null,
                    kind = "OTHER",
                    archivedAt = null,
                    createdAt = CREATED_AT,
                    updatedAt = CREATED_AT,
                ),
            ),
            locations.observeActive().first(),
        )
        assertEquals(1, locations.observeActiveCount().first())
        assertEquals(MAIN_LOCATION_ID, locations.findBySystemKey("MAIN")?.id)
        assertEquals(emptyList<LocationEntity>(), locations.observeArchived().first())
    }

    @Test
    fun anExistingDatabaseDoesNotReceiveTheMainLocationAgain() = runBlocking {
        locations.update(MAIN_LOCATION_ID, "Local", "local", "STORE", LATER)
        database.close()

        // Otro reloj: si naciera otra vez, saldría con este instante.
        val later = Clock { Instant.fromEpochMilliseconds(MUCH_LATER) }
        database = AppDatabase.open(context, file, Dispatchers.IO, later)

        assertEquals(1L, count("SELECT COUNT(*) FROM locations"))
        assertEquals("local", locations.findById(MAIN_LOCATION_ID)?.normalizedName)
        assertEquals(LATER, locations.findById(MAIN_LOCATION_ID)?.updatedAt)
    }

    @Test
    fun aPersonLocationReadsBackWithEveryColumn() = runBlocking {
        val own = location("Puesto de la plaza", kind = "STALL").copy(updatedAt = LATER)

        val id = locations.insert(own)

        assertEquals(own.copy(id = id), locations.findById(id))
        assertEquals(id, locations.findActiveByNormalizedName("puesto de la plaza")?.id)
        assertNull(locations.findById(MISSING_ID))
        assertNull(locations.findBySystemKey("SECOND"))
        assertNull(locations.findActiveByNormalizedName("puesto"))
    }

    @Test
    fun keysNeverRepeatAndNamesOnlyAmongActiveOnes() = runBlocking {
        constraintFailure(UNIQUE) {
            locations.insert(
                location("Otra").copy(systemKey = "MAIN", name = null, normalizedName = null),
            )
        }
        // Mayúsculas y espacios de más no hacen otro nombre: la guarda lo rechaza.
        val cellar = locations.insert(location("Bodega"))
        guardFailure(GUARD) { locations.insert(location(" BODEGA ")) }
        val stall = locations.insert(location("Puesto"))
        guardFailure(GUARD) { locations.update(stall, "Bodega", "bodega", "STALL", LATER) }

        // Una archivada no reserva su nombre, y no se reactiva si otra vigente ya lo tiene.
        locations.setArchivedAt(cellar, archivedAt = LATER, updatedAt = LATER)
        val again = locations.insert(location("Bodega"))
        assertEquals(again, locations.findActiveByNormalizedName("bodega")?.id)
        guardFailure(GUARD) { locations.setArchivedAt(cellar, null, updatedAt = MUCH_LATER) }
        assertEquals("puesto", locations.findById(stall)?.normalizedName)
        assertEquals(LATER, locations.findById(cellar)?.archivedAt)
    }

    @Test
    fun listsFollowCreationOrderAndEmitAgainOnArchiveAndReactivation() = runBlocking {
        val zeta = locations.insert(location("Zona franca"))
        val alfa = locations.insert(location("Almacén"))

        val active = Emissions(this, locations.observeActive().ids { it.id })
        val archived = Emissions(this, locations.observeArchived().ids { it.id })
        val count = Emissions(this, locations.observeActiveCount())
        try {
            active.awaitValue(listOf(MAIN_LOCATION_ID, zeta, alfa))
            archived.awaitValue(emptyList())
            count.awaitValue(3)

            locations.setArchivedAt(zeta, archivedAt = LATER, updatedAt = LATER)
            locations.setArchivedAt(alfa, archivedAt = LATER, updatedAt = LATER)
            // Con una sola vigente, la interfaz vuelve a ocultar las ubicaciones.
            active.awaitValue(listOf(MAIN_LOCATION_ID))
            archived.awaitValue(listOf(zeta, alfa))
            count.awaitValue(1)

            locations.setArchivedAt(zeta, archivedAt = null, updatedAt = MUCH_LATER)
            active.awaitValue(listOf(MAIN_LOCATION_ID, zeta))
            archived.awaitValue(listOf(alfa))
            count.awaitValue(2)
        } finally {
            active.stop()
            archived.stop()
            count.stop()
        }
        assertEquals(MUCH_LATER, locations.findById(zeta)?.updatedAt)
    }

    @Test
    fun updateChangesWhatThePersonWritesAndKeepsTheRest() = runBlocking {
        val before = locations.findById(MAIN_LOCATION_ID)

        // La persona nombra la inicial: desde ahí se muestra su nombre, y la clave se queda.
        assertEquals(1, locations.update(MAIN_LOCATION_ID, "Local", "local", "STORE", MUCH_LATER))

        assertEquals(
            before?.copy(
                name = "Local",
                normalizedName = "local",
                kind = "STORE",
                updatedAt = MUCH_LATER,
            ),
            locations.findById(MAIN_LOCATION_ID),
        )
        assertEquals(0, locations.update(MISSING_ID, "Otra", "otra", "OTHER", LATER))
    }

    @Test
    fun theMainLocationIsNeverArchivedNorDeleted() = runBlocking {
        assertEquals(0, locations.setArchivedAt(MAIN_LOCATION_ID, LATER, updatedAt = LATER))
        assertEquals(0, locations.delete(MAIN_LOCATION_ID))
        assertNull(locations.findById(MAIN_LOCATION_ID)?.archivedAt)
        assertEquals(1, locations.observeActiveCount().first())
    }

    @Test
    fun aLocationInUseIsNotDeletedAndAnUnusedOneIs() = runBlocking {
        // Una fila del libro, un saldo, una compra y una venta: cada uso basta para que su llave
        // detenga el borrado.
        val ledgerOnly = locations.insert(location("Bodega"))
        val balanceOnly = locations.insert(location("Puesto"))
        val purchaseOnly = locations.insert(location("Taller"))
        val saleOnly = locations.insert(location("Feria"))
        database.stockMovementDao().insert(openingBalance(productId).copy(locationId = ledgerOnly))
        database.stockBalanceDao().add(productId, balanceOnly, NO_LOT, 1_000)
        database.purchaseDao().insert(purchase(supplierId = null).copy(locationId = purchaseOnly))
        database.saleDao().insert(sale(priceListId).copy(locationId = saleOnly))

        for (used in listOf(ledgerOnly, balanceOnly, purchaseOnly, saleOnly)) {
            assertTrue("$used está en uso", locations.isInUse(used))
            constraintFailure(FOREIGN_KEY) { locations.delete(used) }
        }
        assertFalse(locations.isInUse(MAIN_LOCATION_ID))
        assertEquals(5L, count("SELECT COUNT(*) FROM locations"))

        val unused = locations.insert(location("Casa"))
        assertFalse(locations.isInUse(unused))
        assertEquals(1, locations.delete(unused))
        assertEquals(0, locations.delete(MISSING_ID))
    }

    @Test
    fun indexesAndKeysAreTheOnesOfTheDataModel() = runBlocking {
        val indexes =
            mapOf(
                "locations" to
                    listOf(
                        "index_locations_normalized_name (normalized_name)",
                        "unique index_locations_system_key (system_key)",
                    ),
                "stock_lots" to
                    listOf(
                        "index_stock_lots_expiry_date (expiry_date)",
                        "unique index_stock_lots_product_id_lot_code (product_id, lot_code)",
                    ),
                "stock_balances" to
                    listOf(
                        "index_stock_balances_location_id (location_id)",
                        "unique index_stock_balances_product_id_location_id_lot_key " +
                            "(product_id, location_id, lot_key)",
                    ),
            )
        val keys =
            mapOf(
                "locations" to emptyList(),
                "stock_lots" to
                    listOf(
                        "product_id -> products RESTRICT",
                        // La línea hacia su lote es RESTRICT: esta va al revés y deja el lote sin
                        // ella, para que las dos llaves no formen un ciclo.
                        "purchase_line_id -> purchase_lines SET NULL",
                    ),
                // lot_key no es llave: vale 0 sin lote.
                "stock_balances" to
                    listOf("location_id -> locations RESTRICT", "product_id -> products RESTRICT"),
            )

        for ((table, expected) in indexes) assertEquals(table, expected, database.indexesOf(table))
        for ((table, expected) in keys) assertEquals(table, expected, database.foreignKeysOf(table))
    }

    private companion object {
        const val MISSING_ID = 999L
        const val GUARD = "guard:locations"
    }
}
