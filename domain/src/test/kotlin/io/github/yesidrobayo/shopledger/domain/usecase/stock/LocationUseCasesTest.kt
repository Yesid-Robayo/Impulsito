package io.github.yesidrobayo.shopledger.domain.usecase.stock

import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.stock.LocationDetails
import io.github.yesidrobayo.shopledger.domain.model.stock.LocationDraft
import io.github.yesidrobayo.shopledger.domain.model.stock.LocationInputError
import io.github.yesidrobayo.shopledger.domain.model.stock.LocationKind
import io.github.yesidrobayo.shopledger.domain.model.stock.ProductLocationStock
import io.github.yesidrobayo.shopledger.domain.model.stock.StockKey
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.testing.FakeLocationRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakeLocationStockSource
import io.github.yesidrobayo.shopledger.domain.testing.FakeTransactionRunner
import io.github.yesidrobayo.shopledger.domain.testing.FixedClock
import io.github.yesidrobayo.shopledger.domain.testing.InMemoryDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Crear, editar, archivar y reactivar ubicaciones, y leer el stock repartido entre ellas. Cada
 * escritura en una sola transacción. Nombres y cantidades inventados, con valores a mano.
 */
class LocationUseCasesTest {
    private val database = InMemoryDatabase()
    private val transactions = FakeTransactionRunner(database)
    private val clock = FixedClock.atEpochMillis(NOW)
    private val locations = FakeLocationRepository(database)
    private val stock = FakeLocationStockSource(database)
    private val save = SaveLocationUseCase(transactions, locations, clock)
    private val archive = ArchiveLocationUseCase(transactions, locations, stock, clock)
    private val reactivate = ReactivateLocationUseCase(transactions, locations, clock)
    private val overview = ObserveLocationsUseCase(locations, stock)
    private val productStock = ObserveProductLocationStockUseCase(locations, stock)
    private val main = locations.seedInitial(clock.now())

    private suspend fun created(name: String, kind: LocationKind = LocationKind.WAREHOUSE): Long =
        (save(null, LocationDraft(name, kind)) as SaveLocationResult.Saved).locationId

    private fun seedBalance(productId: Long, locationId: Long, lotId: Long?, milli: Long) {
        database.seed {
            it.copy(
                stockBalances = it.stockBalances +
                    (StockKey(productId, locationId, lotId) to QuantityMilli(milli)),
            )
        }
    }

    @Test
    fun `a new location keeps its trimmed name, its kind and a normalized name`() = runTest {
        val id = created("  Bodega Norte  ", LocationKind.WAREHOUSE)

        val location = database.tables.value.locations.getValue(id)
        assertEquals(
            LocationDetails("Bodega Norte", "bodega norte", LocationKind.WAREHOUSE),
            location.details,
        )
        assertNull(location.systemKey)
        assertNull(location.archivedAt)
        assertEquals(clock.now(), location.createdAt)
        assertEquals(1, transactions.commits)
        assertEquals(1L, database.tables.value.changeSeq)
    }

    @Test
    fun `an empty or too long name writes nothing`() = runTest {
        val empty = save(null, LocationDraft("   ", LocationKind.STALL))
        val long = save(null, LocationDraft("x".repeat(41), LocationKind.STALL))

        assertEquals(SaveLocationResult.Invalid(setOf(LocationInputError.NAME_EMPTY)), empty)
        assertEquals(SaveLocationResult.Invalid(setOf(LocationInputError.NAME_TOO_LONG)), long)
        assertEquals(setOf(main), database.tables.value.locations.keys)
        assertEquals(0, transactions.commits)
    }

    @Test
    fun `a name of exactly 40 characters is saved`() = runTest {
        val id = created("y".repeat(40))

        assertEquals("y".repeat(40), database.tables.value.locations.getValue(id).details.name)
    }

    @Test
    fun `an active location with the same name without case or accents is a duplicate`() = runTest {
        created("Depósito")

        assertEquals(
            SaveLocationResult.DuplicateName,
            save(null, LocationDraft("DEPOSITO", LocationKind.OTHER)),
        )
        assertEquals(1, transactions.commits)
    }

    @Test
    fun `the initial location is renamed and goes back to its translated name when left blank`() =
        runTest {
            assertEquals(
                SaveLocationResult.Saved(main),
                save(main, LocationDraft("Local centro", LocationKind.STORE)),
            )
            val renamed = database.tables.value.locations.getValue(main)
            assertEquals("Local centro", renamed.details.name)

            assertEquals(
                SaveLocationResult.Saved(main),
                save(main, LocationDraft("  ", LocationKind.STORE)),
            )
            val back = database.tables.value.locations.getValue(main)
            assertEquals(LocationDetails(null, null, LocationKind.STORE), back.details)
            assertEquals(2, transactions.commits)
        }

    @Test
    fun `a location created by the person cannot be left without a name`() = runTest {
        val id = created("Puesto feria")

        assertEquals(
            SaveLocationResult.Invalid(setOf(LocationInputError.NAME_EMPTY)),
            save(id, LocationDraft("", LocationKind.STALL)),
        )
    }

    @Test
    fun `an edit that changes nothing writes nothing`() = runTest {
        val id = created("Casa", LocationKind.OTHER)

        assertEquals(
            SaveLocationResult.Saved(id),
            save(id, LocationDraft(" Casa ", LocationKind.OTHER)),
        )
        assertEquals(1, transactions.commits)
        assertEquals(1L, database.tables.value.changeSeq)
    }

    @Test
    fun `renaming keeps its own name free and clashes with another active one`() = runTest {
        val warehouse = created("Bodega")
        created("Puesto")

        assertEquals(
            SaveLocationResult.Saved(warehouse),
            save(warehouse, LocationDraft("bodega", LocationKind.STORE)),
        )
        assertEquals(
            SaveLocationResult.DuplicateName,
            save(warehouse, LocationDraft("Puesto", LocationKind.STORE)),
        )
    }

    @Test
    fun `editing a location that no longer exists writes nothing`() = runTest {
        assertEquals(
            SaveLocationResult.NotFound,
            save(99L, LocationDraft("Otra", LocationKind.OTHER)),
        )
        assertEquals(0, transactions.commits)
    }

    @Test
    fun `a location without stock is archived and frees its name until it is reactivated`() =
        runTest {
            val stall = created("Puesto")

            assertEquals(LocationLifecycleResult.Done, archive(stall))
            assertEquals(clock.now(), database.tables.value.locations.getValue(stall).archivedAt)
            assertEquals(LocationLifecycleResult.Unchanged, archive(stall))

            // Archivada no reserva su nombre: otra lo toma, y reactivar la primera choca.
            val other = created("puesto")
            assertEquals(
                SaveLocationResult.Saved(stall),
                save(stall, LocationDraft("Puesto", LocationKind.STALL)),
            )
            assertEquals(LocationLifecycleResult.NameTaken, reactivate(stall))

            assertEquals(
                SaveLocationResult.Saved(other),
                save(other, LocationDraft("Puesto dos", LocationKind.STALL)),
            )
            assertEquals(LocationLifecycleResult.Done, reactivate(stall))
            assertNull(database.tables.value.locations.getValue(stall).archivedAt)
            assertEquals(LocationLifecycleResult.Unchanged, reactivate(stall))
        }

    @Test
    fun `the initial location is never archived`() = runTest {
        created("Bodega")

        assertEquals(LocationLifecycleResult.Initial, archive(main))
        assertNull(database.tables.value.locations.getValue(main).archivedAt)
    }

    @Test
    fun `a location with stock, positive or negative, is not archived`() = runTest {
        val positive = created("Bodega")
        val negative = created("Puesto")
        val empty = created("Casa")
        seedBalance(productId = 7L, locationId = positive, lotId = null, milli = 4_000)
        seedBalance(productId = 7L, locationId = negative, lotId = 3L, milli = -500)
        seedBalance(productId = 8L, locationId = empty, lotId = null, milli = 0)

        assertEquals(LocationLifecycleResult.HasStock, archive(positive))
        assertEquals(LocationLifecycleResult.HasStock, archive(negative))
        assertEquals(LocationLifecycleResult.Done, archive(empty))
        assertEquals(4, transactions.commits)
        val archived = database.tables.value.locations.values.filter { it.isArchived }
        assertEquals(listOf(empty), archived.map { it.id })
    }

    @Test
    fun `archiving or reactivating a missing location writes nothing`() = runTest {
        assertEquals(LocationLifecycleResult.NotFound, archive(42L))
        assertEquals(LocationLifecycleResult.NotFound, reactivate(42L))
        assertEquals(0, transactions.commits)
    }

    @Test
    fun `a restore in progress leaves every write as it was`() = runTest {
        val stall = created("Puesto")
        transactions.restoreInProgress = true

        val failed = SaveLocationResult.StorageFailed(StorageFailure.RESTORE_IN_PROGRESS)
        assertEquals(failed, save(null, LocationDraft("Bodega", LocationKind.WAREHOUSE)))
        val lifecycle = LocationLifecycleResult.StorageFailed(StorageFailure.RESTORE_IN_PROGRESS)
        assertEquals(lifecycle, archive(stall))
        assertEquals(lifecycle, reactivate(stall))
        assertEquals(2, database.tables.value.locations.size)
    }

    @Test
    fun `with a single location nothing about locations is shown`() = runTest {
        seedBalance(productId = 7L, locationId = main, lotId = null, milli = 10_000)

        val single = overview().first()
        assertFalse(single.showsLocations)
        assertEquals(listOf(main), single.active.map { it.location.id })
        assertEquals(ProductLocationStock.Hidden, productStock(7L).first())
    }

    @Test
    fun `six in one location and four in another add up to the ten of the product`() = runTest {
        val warehouse = created("Bodega")
        seedBalance(productId = 7L, locationId = main, lotId = null, milli = 6_000)
        seedBalance(productId = 7L, locationId = warehouse, lotId = null, milli = 4_000)

        val shown = productStock(7L).first() as ProductLocationStock.Shown

        assertEquals(
            listOf(main to 6_000L, warehouse to 4_000L),
            shown.rows.map { it.location.id to it.onHand.milli },
        )
        assertEquals(10_000L, shown.rows.sumOf { it.onHand.milli })
        assertTrue(overview().first().showsLocations)
    }

    @Test
    fun `lots of the same location add up, and a location without rows shows zero`() = runTest {
        val warehouse = created("Bodega")
        val stall = created("Puesto")
        seedBalance(productId = 7L, locationId = warehouse, lotId = 1L, milli = 3_000)
        seedBalance(productId = 7L, locationId = warehouse, lotId = 2L, milli = 5_000)

        val shown = productStock(7L).first() as ProductLocationStock.Shown

        assertEquals(
            listOf(main to 0L, warehouse to 8_000L, stall to 0L),
            shown.rows.map { it.location.id to it.onHand.milli },
        )
    }

    @Test
    fun `an archived location shows only while something other than zero is left`() = runTest {
        val warehouse = created("Bodega")
        val stall = created("Puesto")
        val home = created("Casa")
        archive(stall)
        archive(home)
        // Lo que devuelve la anulación de una venta hecha en el puesto vuelve a él, ya archivado.
        seedBalance(productId = 7L, locationId = stall, lotId = null, milli = 2_000)
        seedBalance(productId = 7L, locationId = home, lotId = null, milli = 0)

        val shown = productStock(7L).first() as ProductLocationStock.Shown

        assertEquals(
            listOf(main to 0L, warehouse to 0L, stall to 2_000L),
            shown.rows.map { it.location.id to it.onHand.milli },
        )
        assertEquals(listOf(stall, home), overview().first().archived.map { it.id })
    }

    @Test
    fun `each active location counts the products that have stock in it`() = runTest {
        val warehouse = created("Bodega")
        seedBalance(productId = 7L, locationId = main, lotId = null, milli = 1_000)
        seedBalance(productId = 8L, locationId = main, lotId = null, milli = -1_000)
        seedBalance(productId = 7L, locationId = warehouse, lotId = 1L, milli = 2_000)
        seedBalance(productId = 7L, locationId = warehouse, lotId = 2L, milli = -2_000)
        seedBalance(productId = 9L, locationId = warehouse, lotId = null, milli = 500)

        val counts = overview().first().active.associate { it.location.id to it.stockedProducts }

        assertEquals(mapOf(main to 1, warehouse to 1), counts)
    }

    private companion object {
        const val NOW = 1_773_576_000_000L
    }
}
