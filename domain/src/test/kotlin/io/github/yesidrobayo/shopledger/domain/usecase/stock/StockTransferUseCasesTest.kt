package io.github.yesidrobayo.shopledger.domain.usecase.stock

import io.github.yesidrobayo.shopledger.domain.model.DocumentStatus
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.VoidReason
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.stock.Location
import io.github.yesidrobayo.shopledger.domain.model.stock.LocationDetails
import io.github.yesidrobayo.shopledger.domain.model.stock.LocationKind
import io.github.yesidrobayo.shopledger.domain.model.stock.StockKey
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType
import io.github.yesidrobayo.shopledger.domain.model.stock.StockTransferLineRejection
import io.github.yesidrobayo.shopledger.domain.model.stock.StockTransferLineRequest
import io.github.yesidrobayo.shopledger.domain.model.stock.StockTransferRequest
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.rule.CostReplayResult
import io.github.yesidrobayo.shopledger.domain.rule.WeightedAverageCost
import io.github.yesidrobayo.shopledger.domain.testing.FakeLocationRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakeStockTransferRepository
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CatalogFixture
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Transferir stock entre ubicaciones y anularlo: con 10 unidades y un valor de 15000, mover 4 deja
 * 6 y 4 y el valor sigue en 15000, también si se mueve todo. Valores escritos a mano.
 */
class StockTransferUseCasesTest {
    private val fixture = StockFixture()
    private val catalog = fixture.catalog
    private val database = catalog.database
    private val transfers = FakeStockTransferRepository(database)
    private val locations = FakeLocationRepository(database)
    private val calendar = BusinessCalendarSource(catalog.settings)
    private val register = RegisterStockTransferUseCase(
        catalog.transactions,
        catalog.products,
        catalog.units,
        locations,
        transfers,
        catalog.ledger,
        calendar,
        catalog.clock,
        catalog.lots,
    )
    private val void = VoidStockTransferUseCase(
        catalog.transactions,
        catalog.products,
        transfers,
        catalog.ledger,
        calendar,
        catalog.clock,
        catalog.lots,
    )

    private suspend fun seeded() {
        // 10 unidades a 1500: el valor es 15000, todo en la ubicación inicial.
        fixture.seedStock(id = 1L, units = 10_000L, unitCost = 1_500L)
        database.seed {
            it.copy(
                locations = it.locations +
                    (MAIN to location(MAIN, "MAIN", archived = false)) +
                    (STALL to location(STALL, null, archived = false)) +
                    (CLOSED to location(CLOSED, null, archived = true)),
            )
        }
    }

    private fun move(milli: Long, from: Long = MAIN, to: Long = STALL, productId: Long = 1L) =
        StockTransferRequest(
            from,
            to,
            listOf(StockTransferLineRequest(productId, QuantityMilli(milli))),
        )

    private fun part(locationId: Long) =
        database.tables.value.stockBalances[StockKey(1L, locationId, null)] ?: QuantityMilli.ZERO

    @Test
    fun `moving 4 of 10 leaves 6 and 4 and keeps quantity, cost and value`() = runTest {
        seeded()
        val before = fixture.product(1L)

        val result = register(move(4_000L))

        assertTrue(result is RegisterStockTransferResult.Registered)
        assertEquals(QuantityMilli(6_000L), part(MAIN))
        assertEquals(QuantityMilli(4_000L), part(STALL))
        val after = fixture.product(1L)
        assertEquals(QuantityMilli(10_000L), after.onHand)
        assertEquals(before.averageCost, after.averageCost)
        assertEquals(Money(15_000L), after.stockValue)
        val rows = fixture.movementsOf(1L).takeLast(2)
        assertEquals(
            listOf(StockMovementType.TRANSFER_OUT, StockMovementType.TRANSFER_IN),
            rows.map { it.movement.type },
        )
        // Una salida y una entrada que suman cero, sin costo, en sus ubicaciones (I-21).
        assertEquals(0L, rows.sumOf { it.movement.quantity.milli })
        assertEquals(listOf(MAIN, STALL), rows.map { it.movement.key.locationId })
        assertEquals(listOf(Money.ZERO, Money.ZERO), rows.map { it.totalCost })
        // El saldo baja con la salida y vuelve con la entrada.
        assertEquals(listOf(6_000L, 10_000L), rows.map { it.balanceAfter.milli })
        // El inventario inicial y la transferencia: una transacción cada uno.
        assertEquals(2, catalog.transactions.commits)
    }

    @Test
    fun `moving all the stock does not erase its value, and the replay skips transfers`() =
        runTest {
            seeded()

            register(move(10_000L))

            val exit = fixture.movementsOf(1L)
                .first { it.movement.type == StockMovementType.TRANSFER_OUT }
            assertEquals(QuantityMilli.ZERO, exit.balanceAfter)
            assertEquals(Money(15_000L), exit.stockValueAfter)
            val book = fixture.movementsOf(1L).map { it.toCostMovement() }
            val replay = WeightedAverageCost.replayCostState(fixture.product(1L).initialCost, book)
            assertEquals(Money(15_000L), (replay as CostReplayResult.Replayed).state.stockValue)
        }

    @Test
    fun `moving more than the origin has is allowed and leaves it negative`() = runTest {
        seeded()

        assertTrue(register(move(12_000L)) is RegisterStockTransferResult.Registered)

        assertEquals(QuantityMilli(-2_000L), part(MAIN))
        assertEquals(QuantityMilli(12_000L), part(STALL))
    }

    @Test
    fun `voiding brings the stock back with a compensation per row, once`() = runTest {
        seeded()
        val id = (register(move(4_000L)) as RegisterStockTransferResult.Registered).transferId

        assertEquals(VoidStockTransferResult.Voided, void(id, VoidReason.ENTRY_ERROR))

        assertEquals(QuantityMilli(10_000L), part(MAIN))
        assertEquals(QuantityMilli.ZERO, part(STALL))
        val voids = fixture.movementsOf(1L).takeLast(2)
        assertEquals(
            listOf(StockMovementType.TRANSFER_VOID, StockMovementType.TRANSFER_VOID),
            voids.map { it.movement.type },
        )
        // Primero baja el destino y después recupera el origen.
        assertEquals(listOf(STALL, MAIN), voids.map { it.movement.key.locationId })
        assertEquals(listOf(6_000L, 10_000L), voids.map { it.balanceAfter.milli })
        assertEquals(Money(15_000L), fixture.product(1L).stockValue)
        assertNotNull(database.tables.value.stockTransfers.getValue(id).voiding)
        assertEquals(
            DocumentStatus.VOIDED,
            database.tables.value.stockTransfers.getValue(id).status,
        )
        assertEquals(VoidStockTransferResult.AlreadyVoided, void(id, VoidReason.ENTRY_ERROR))
    }

    @Test
    fun `voiding checks reason, note and existence before writing`() = runTest {
        seeded()
        val id = (register(move(1_000L)) as RegisterStockTransferResult.Registered).transferId

        assertEquals(
            VoidStockTransferResult.ReasonNotAllowed,
            void(id, VoidReason.CUSTOMER_RETURN),
        )
        assertEquals(
            VoidStockTransferResult.NoteTooLong,
            void(id, VoidReason.OTHER, "n".repeat(201)),
        )
        assertEquals(VoidStockTransferResult.NotFound, void(99L, VoidReason.UNDO))
        assertEquals(VoidStockTransferResult.Voided, void(id, VoidReason.OTHER, " Otra nota "))
        assertEquals("Otra nota", database.tables.value.stockTransfers.getValue(id).voiding?.note)
    }

    @Test
    fun `the request is checked before anything is written`() = runTest {
        seeded()
        catalog.seedProduct(id = 2L, name = "Servicio", stockMode = ProductStockMode.UNTRACKED)
        catalog.seedProduct(id = 3L, name = "Archivado", archived = true)
        val writesBefore = database.tables.value

        assertEquals(
            RegisterStockTransferResult.EmptyTransfer,
            register(StockTransferRequest(MAIN, STALL, emptyList())),
        )
        assertEquals(
            RegisterStockTransferResult.TooManyLines,
            register(
                StockTransferRequest(
                    MAIN,
                    STALL,
                    List(201) { StockTransferLineRequest(1L, QuantityMilli(1_000L)) },
                ),
            ),
        )
        assertEquals(
            RegisterStockTransferResult.NoteTooLong,
            register(move(1_000L).copy(note = "x".repeat(201))),
        )
        assertEquals(RegisterStockTransferResult.SameLocation, register(move(1_000L, to = MAIN)))
        assertEquals(
            RegisterStockTransferResult.LocationUnavailable,
            register(move(1_000L, to = CLOSED)),
        )
        assertEquals(
            RegisterStockTransferResult.LocationUnavailable,
            register(move(1_000L, from = 77L)),
        )
        assertEquals(
            rejected(StockTransferLineRejection.NOT_TRACKED),
            register(move(1L, productId = 2L)),
        )
        assertEquals(
            rejected(StockTransferLineRejection.PRODUCT_ARCHIVED),
            register(move(1_000L, productId = 3L)),
        )
        assertEquals(
            rejected(StockTransferLineRejection.PRODUCT_NOT_FOUND),
            register(move(1_000L, productId = 9L)),
        )
        assertEquals(rejected(StockTransferLineRejection.INVALID_QUANTITY), register(move(1_500L)))
        assertEquals(rejected(StockTransferLineRejection.INVALID_QUANTITY), register(move(0L)))
        assertEquals(
            rejected(StockTransferLineRejection.QUANTITY_TOO_LARGE),
            register(move(1_000_001_000L)),
        )
        assertEquals(writesBefore.movements, database.tables.value.movements)
        assertTrue(database.tables.value.stockTransfers.isEmpty())
    }

    @Test
    fun `a restore in progress writes nothing`() = runTest {
        seeded()
        catalog.transactions.restoreInProgress = true

        assertEquals(
            RegisterStockTransferResult.StorageFailed(StorageFailure.RESTORE_IN_PROGRESS),
            register(move(1_000L)),
        )
        assertEquals(
            VoidStockTransferResult.StorageFailed(StorageFailure.RESTORE_IN_PROGRESS),
            void(1L, VoidReason.UNDO),
        )
    }

    @Test
    fun `an unknown time zone has no date to give the transfer or its void`() = runTest {
        val unknown = StockFixture(zoneId = "Zona/Inexistente")
        val unknownCatalog = unknown.catalog
        val unknownCalendar = BusinessCalendarSource(unknownCatalog.settings)
        unknownCatalog.seedProduct(id = 1L, name = "Arroz", onHand = QuantityMilli(5_000L))
        unknownCatalog.database.seed {
            it.copy(
                locations = it.locations +
                    (MAIN to location(MAIN, "MAIN", archived = false)) +
                    (STALL to location(STALL, null, archived = false)),
            )
        }
        val unknownTransfers = FakeStockTransferRepository(unknownCatalog.database)
        val result = RegisterStockTransferUseCase(
            unknownCatalog.transactions,
            unknownCatalog.products,
            unknownCatalog.units,
            FakeLocationRepository(unknownCatalog.database),
            unknownTransfers,
            unknownCatalog.ledger,
            unknownCalendar,
            unknownCatalog.clock,
            unknownCatalog.lots,
        )(move(1_000L))

        assertEquals(RegisterStockTransferResult.UnknownTimeZone, result)
    }

    private fun rejected(reason: StockTransferLineRejection) =
        RegisterStockTransferResult.LineRejected(0, reason)

    private companion object {
        const val MAIN = CatalogFixture.MAIN_LOCATION_ID
        const val STALL = 40L
        const val CLOSED = 41L

        fun location(id: Long, systemKey: String?, archived: Boolean) = Location(
            id = id,
            systemKey = systemKey,
            details = LocationDetails(
                if (systemKey == null) "Lugar $id" else null,
                if (systemKey == null) "lugar $id" else null,
                LocationKind.STALL,
            ),
            archivedAt = if (archived) CatalogFixture.NOW else null,
            createdAt = CatalogFixture.NOW,
            updatedAt = CatalogFixture.NOW,
        )
    }
}
