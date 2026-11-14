package io.github.yesidrobayo.shopledger.data.repository

import androidx.room3.useReaderConnection
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.DataPorts
import io.github.yesidrobayo.shopledger.data.backup.InvariantQueries
import io.github.yesidrobayo.shopledger.data.local.LOCATION_ID
import io.github.yesidrobayo.shopledger.data.local.catalog.FIXED_CLOCK
import io.github.yesidrobayo.shopledger.data.local.texts
import io.github.yesidrobayo.shopledger.data.transaction.RestoreLock
import io.github.yesidrobayo.shopledger.domain.model.stock.LocationDraft
import io.github.yesidrobayo.shopledger.domain.model.stock.LocationKind
import io.github.yesidrobayo.shopledger.domain.model.stock.NewStockMovement
import io.github.yesidrobayo.shopledger.domain.model.stock.ProductLocationStock
import io.github.yesidrobayo.shopledger.domain.model.stock.StockAdjustmentReason
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType
import io.github.yesidrobayo.shopledger.domain.usecase.stock.ArchiveLocationUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.stock.LocationLifecycleResult
import io.github.yesidrobayo.shopledger.domain.usecase.stock.ObserveLocationsUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.stock.ObserveProductLocationStockUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.stock.ReactivateLocationUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.stock.SaveLocationResult
import io.github.yesidrobayo.shopledger.domain.usecase.stock.SaveLocationUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Las ubicaciones y el stock repartido entre ellas sobre la base real: lo que escriben sus casos
 * de uso, lo que la guarda de la tabla nunca llega a ver porque el caso de uso lo rechaza antes, y
 * que el saldo del producto sigue siendo la suma de sus partes, y cada parte la de su libro.
 * Nombres y cantidades inventados.
 */
@RunWith(AndroidJUnit4::class)
internal class LocationsOnDatabaseTest : RepositoryTest() {
    private lateinit var ports: DataPorts
    private lateinit var save: SaveLocationUseCase
    private lateinit var archive: ArchiveLocationUseCase
    private lateinit var reactivate: ReactivateLocationUseCase
    private lateinit var overview: ObserveLocationsUseCase
    private lateinit var productStock: ObserveProductLocationStockUseCase

    @Before
    fun connectUseCases() {
        ports = DataPorts(database, file, Dispatchers.IO, FIXED_CLOCK, RestoreLock { false })
        save = SaveLocationUseCase(ports.transactions, ports.locations, FIXED_CLOCK)
        archive = ArchiveLocationUseCase(
            ports.transactions,
            ports.locations,
            ports.locationStock,
            FIXED_CLOCK,
        )
        reactivate = ReactivateLocationUseCase(ports.transactions, ports.locations, FIXED_CLOCK)
        overview = ObserveLocationsUseCase(ports.locations, ports.locationStock)
        productStock = ObserveProductLocationStockUseCase(ports.locations, ports.locationStock)
    }

    @Test
    fun aLocationIsCreatedRenamedArchivedAndReactivatedInOneTransactionEach() = runBlocking {
        val before = changeSeq()

        val warehouse = created("Bodega Norte", LocationKind.WAREHOUSE)
        val row = requireNotNull(database.locationDao().findById(warehouse))
        assertEquals(
            listOf("Bodega Norte", "bodega norte", "WAREHOUSE"),
            listOf(row.name, row.normalizedName, row.kind),
        )
        assertNull(row.systemKey)
        assertEquals(before + 1L, changeSeq())

        assertEquals(
            SaveLocationResult.DuplicateName,
            save(null, LocationDraft("BODEGA norte", LocationKind.STALL)),
        )
        assertEquals(
            SaveLocationResult.Saved(LOCATION_ID),
            save(LOCATION_ID, LocationDraft("Local centro", LocationKind.STORE)),
        )
        assertEquals("Local centro", database.locationDao().findById(LOCATION_ID)?.name)
        assertEquals(before + 2L, changeSeq())

        assertEquals(LocationLifecycleResult.Initial, archive(LOCATION_ID))
        assertEquals(LocationLifecycleResult.Done, archive(warehouse))
        assertEquals(
            listOf("Local centro"),
            overview().first().active.map { it.location.details.name },
        )
        assertEquals(LocationLifecycleResult.Done, reactivate(warehouse))
        assertNull(database.locationDao().findById(warehouse)?.archivedAt)
        assertEquals(before + 4L, changeSeq())
    }

    @Test
    fun withASingleLocationNothingIsShownEvenWithStock() = runBlocking {
        commit { ports.stockLedger.append(openingRow(productId, units = 10)) }

        assertFalse(overview().first().showsLocations)
        assertEquals(ProductLocationStock.Hidden, productStock(productId).first())
        assertEquals(emptyList<String>(), invariantRows())
    }

    @Test
    fun sixHereAndFourThereAddUpAndEachPartIsTheSumOfItsLedger() = runBlocking {
        val warehouse = created("Bodega", LocationKind.WAREHOUSE)
        commit {
            ports.stockLedger.append(openingRow(productId, units = 6))
            ports.stockLedger.append(
                ledgerRow(
                    productId,
                    StockMovementType.OPENING_BALANCE,
                    quantity = 4_000,
                    balanceAfter = 10_000,
                    locationId = warehouse,
                ),
            )
        }

        val shown = productStock(productId).first() as ProductLocationStock.Shown
        assertEquals(
            listOf(LOCATION_ID to 6_000L, warehouse to 4_000L),
            shown.rows.map { it.location.id to it.onHand.milli },
        )
        assertEquals(10_000L, database.productDao().findById(productId)?.onHandMilli)
        val counts = overview().first().active.associate { it.location.id to it.stockedProducts }
        assertEquals(mapOf(LOCATION_ID to 1, warehouse to 1), counts)
        assertEquals(emptyList<String>(), invariantRows())

        // Con stock no se archiva. Cuatro dañadas en la bodega la dejan en cero, y entonces sí.
        assertEquals(LocationLifecycleResult.HasStock, archive(warehouse))
        commit { ports.stockLedger.append(damaged(warehouse, quantity = -4_000, after = 6_000)) }
        assertEquals(LocationLifecycleResult.Done, archive(warehouse))

        assertEquals(ProductLocationStock.Hidden, productStock(productId).first())
        assertEquals(emptyList<String>(), invariantRows())
    }

    @Test
    fun aNegativePartAlsoKeepsTheLocationFromBeingArchived() = runBlocking {
        val stall = created("Puesto", LocationKind.STALL)
        commit { ports.stockLedger.append(openingRow(productId, units = 5)) }
        commit { ports.stockLedger.append(damaged(stall, quantity = -1_000, after = 4_000)) }

        assertEquals(LocationLifecycleResult.HasStock, archive(stall))
        val shown = productStock(productId).first() as ProductLocationStock.Shown
        assertEquals(
            listOf(LOCATION_ID to 5_000L, stall to -1_000L),
            shown.rows.map { it.location.id to it.onHand.milli },
        )
        // Un saldo negativo no es un producto con stock.
        val counts = overview().first().active.associate { it.location.id to it.stockedProducts }
        assertEquals(mapOf(LOCATION_ID to 1, stall to 0), counts)
        assertTrue(overview().first().showsLocations)
        assertEquals(emptyList<String>(), invariantRows())
    }

    @Test
    fun theInvariantsFindAPartThatDoesNotMatchItsLedger() = runBlocking {
        val warehouse = created("Bodega", LocationKind.WAREHOUSE)
        commit { ports.stockLedger.append(openingRow(productId, units = 3)) }
        // Escrito directo, sin su fila del libro: lo que las consultas deben delatar.
        database.stockBalanceDao().add(productId, warehouse, lotKey = 0L, deltaMilli = 2_000)

        assertEquals(listOf("I-10", "I-11"), invariantLabels())
    }

    private suspend fun created(name: String, kind: LocationKind): Long =
        (save(null, LocationDraft(name, kind)) as SaveLocationResult.Saved).locationId

    private fun damaged(locationId: Long, quantity: Long, after: Long): NewStockMovement =
        ledgerRow(
            productId,
            StockMovementType.ADJUSTMENT,
            quantity = quantity,
            balanceAfter = after,
            locationId = locationId,
            reason = StockAdjustmentReason.DAMAGED,
        )

    /** Las filas que devuelven las consultas de los saldos por ubicación. */
    private suspend fun invariantRows(): List<String> = database.useReaderConnection { connection ->
        STOCK_PART_QUERIES.flatMap { connection.texts(it.sql) }
    }

    /** Qué consultas de los saldos por ubicación devuelven alguna fila. */
    private suspend fun invariantLabels(): List<String> =
        database.useReaderConnection { connection ->
            STOCK_PART_QUERIES.filter { connection.texts(it.sql).isNotEmpty() }
                .map { it.label.substringBefore(",") }
                .distinct()
        }

    private companion object {
        val STOCK_PART_QUERIES = InvariantQueries.MODEL.filter {
            it.label == "I-10" || it.label.startsWith("I-11")
        }
    }
}
