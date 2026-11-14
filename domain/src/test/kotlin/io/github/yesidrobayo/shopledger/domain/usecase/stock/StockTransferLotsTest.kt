package io.github.yesidrobayo.shopledger.domain.usecase.stock

import io.github.yesidrobayo.shopledger.domain.model.DocumentStatus
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.VoidReason
import io.github.yesidrobayo.shopledger.domain.model.stock.Location
import io.github.yesidrobayo.shopledger.domain.model.stock.LocationDetails
import io.github.yesidrobayo.shopledger.domain.model.stock.LocationKind
import io.github.yesidrobayo.shopledger.domain.model.stock.StockKey
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType
import io.github.yesidrobayo.shopledger.domain.model.stock.StockTransferLineRejection
import io.github.yesidrobayo.shopledger.domain.model.stock.StockTransferLineRequest
import io.github.yesidrobayo.shopledger.domain.model.stock.StockTransferRequest
import io.github.yesidrobayo.shopledger.domain.testing.FakeLocationRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakeStockTransferRepository
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CatalogFixture
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Transferir un producto que se controla por lotes, y anularlo. Lotes, cantidades y montos
 * inventados: el lote B vence el 2026-11-10 con 2, el A el 2026-11-20 con 3 y el C no vence, con
 * 4, todos en la ubicación inicial y a 1000 la unidad.
 */
class StockTransferLotsTest {
    private val fixture = StockFixture()
    private val catalog = fixture.catalog
    private val database = catalog.database
    private val transfers = FakeStockTransferRepository(database)
    private val calendar = BusinessCalendarSource(catalog.settings)
    private val register = RegisterStockTransferUseCase(
        catalog.transactions,
        catalog.products,
        catalog.units,
        FakeLocationRepository(database),
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

    private var lotA = 0L
    private var lotB = 0L
    private var lotC = 0L

    private suspend fun seeded() {
        fixture.seedStock(id = PRODUCT, units = 0L, unitCost = 1_000L, tracksLots = true)
        fixture.seedStock(id = PLAIN, units = 5_000L, unitCost = 1_000L)
        lotA = fixture.seedLot(PRODUCT, "A-20", expiry = "2026-11-20")
        lotB = fixture.seedLot(PRODUCT, "B-10", expiry = "2026-11-10")
        lotC = fixture.seedLot(PRODUCT, "C-00")
        fixture.receiveIntoLot(PRODUCT, lotA, units = 3_000L, unitCost = 1_000L)
        fixture.receiveIntoLot(PRODUCT, lotB, units = 2_000L, unitCost = 1_000L)
        fixture.receiveIntoLot(PRODUCT, lotC, units = 4_000L, unitCost = 1_000L)
        database.seed {
            it.copy(
                locations = it.locations +
                    (MAIN to location(MAIN, "MAIN")) +
                    (STALL to location(STALL, null)),
            )
        }
    }

    private suspend fun move(
        milli: Long,
        lotId: Long? = null,
        from: Long = MAIN,
        to: Long = STALL,
        productId: Long = PRODUCT,
    ): RegisterStockTransferResult = register(
        StockTransferRequest(
            from,
            to,
            listOf(StockTransferLineRequest(productId, QuantityMilli(milli), lotId)),
        ),
    )

    private fun at(locationId: Long, lotId: Long?): Long =
        database.tables.value.stockBalances[StockKey(PRODUCT, locationId, lotId)]?.milli ?: 0L

    private fun unassignedId(): Long = database.tables.value.lots.values
        .single { it.productId == PRODUCT && it.isUnassigned }.id

    private fun linesOf(transferId: Long) = database.tables.value.stockTransferLines.values
        .filter { it.transferId == transferId }
        .sortedBy { it.id }
        .map { it.line.lotId to it.line.quantity.milli }

    @Test
    fun `without a choice the lot that expires first goes first, and keeps its lot there`() =
        runTest {
            seeded()
            val before = fixture.product(PRODUCT)

            val id = (move(4_000L) as RegisterStockTransferResult.Registered).transferId

            // B da sus 2 y A da 2: una línea por lote, con su salida y su entrada.
            assertEquals(listOf(lotB to 2_000L, lotA to 2_000L), linesOf(id))
            assertEquals(0L, at(MAIN, lotB))
            assertEquals(1_000L, at(MAIN, lotA))
            assertEquals(2_000L, at(STALL, lotB))
            assertEquals(2_000L, at(STALL, lotA))
            // El lote llega con su mismo vencimiento: es el mismo lote, en otro sitio.
            assertEquals(
                "2026-11-10",
                database.tables.value.lots.getValue(lotB).expiryDate.toString(),
            )
            val rows = fixture.movementsOf(PRODUCT).takeLast(4)
            assertEquals(
                listOf(lotB, lotB, lotA, lotA),
                rows.map { it.movement.key.lotId },
            )
            assertTrue(rows.all { it.totalCost == Money.ZERO })
            // Ni la cantidad total, ni el costo, ni el valor cambian.
            val after = fixture.product(PRODUCT)
            assertEquals(before.onHand, after.onHand)
            assertEquals(before.averageCost, after.averageCost)
            assertEquals(before.stockValue, after.stockValue)
            // Ningún lote quedó sin asignar: alcanzó con los lotes con código.
            assertTrue(database.tables.value.lots.values.none { it.isUnassigned })
        }

    @Test
    fun `a chosen lot goes first and the rest follows the expiry order`() = runTest {
        seeded()

        val id = (move(5_000L, lotId = lotC) as RegisterStockTransferResult.Registered).transferId

        assertEquals(listOf(lotC to 4_000L, lotB to 1_000L), linesOf(id))
        assertEquals(4_000L, at(STALL, lotC))
        assertEquals(1_000L, at(STALL, lotB))
    }

    @Test
    fun `what the lots lack comes from the unassigned lot, the only one left negative`() =
        runTest {
            seeded()

            val id = (move(10_000L) as RegisterStockTransferResult.Registered).transferId

            val unassigned = unassignedId()
            assertEquals(
                listOf(lotB to 2_000L, lotA to 3_000L, lotC to 4_000L, unassigned to 1_000L),
                linesOf(id),
            )
            assertEquals(-1_000L, at(MAIN, unassigned))
            assertEquals(1_000L, at(STALL, unassigned))
            assertEquals(listOf(0L, 0L, 0L), listOf(lotA, lotB, lotC).map { at(MAIN, it) })
        }

    @Test
    fun `two lines of one product split from what the first one left`() = runTest {
        seeded()
        val request = StockTransferRequest(
            MAIN,
            STALL,
            listOf(
                StockTransferLineRequest(PRODUCT, QuantityMilli(2_000L)),
                StockTransferLineRequest(PRODUCT, QuantityMilli(2_000L)),
            ),
        )

        val id = (register(request) as RegisterStockTransferResult.Registered).transferId

        assertEquals(listOf(lotB to 2_000L, lotA to 2_000L), linesOf(id))
    }

    @Test
    fun `voiding gives every lot back where it came from`() = runTest {
        seeded()
        val id = (move(4_000L) as RegisterStockTransferResult.Registered).transferId

        assertEquals(VoidStockTransferResult.Voided, void(id, VoidReason.ENTRY_ERROR))

        assertEquals(2_000L, at(MAIN, lotB))
        assertEquals(3_000L, at(MAIN, lotA))
        assertEquals(0L, at(STALL, lotB))
        assertEquals(0L, at(STALL, lotA))
        val voids = fixture.movementsOf(PRODUCT).takeLast(4)
        assertTrue(voids.all { it.movement.type == StockMovementType.TRANSFER_VOID })
        // Cada compensatorio va al lote y a la ubicación de la fila que compensa.
        assertEquals(
            listOf(STALL to lotA, MAIN to lotA, STALL to lotB, MAIN to lotB),
            voids.map { it.movement.key.locationId to it.movement.key.lotId },
        )
    }

    @Test
    fun `voiding is refused when a lot already left the destination`() = runTest {
        seeded()
        val id = (move(4_000L) as RegisterStockTransferResult.Registered).transferId
        // Vuelve 1 del lote B: en el puesto quedan 1 de los 2 que llegaron.
        assertTrue(
            move(1_000L, lotId = lotB, from = STALL, to = MAIN) is
                RegisterStockTransferResult.Registered,
        )
        val before = database.tables.value.movements

        val result = void(id, VoidReason.ENTRY_ERROR)

        assertEquals(
            VoidStockTransferResult.LotAlreadyUsed(PRODUCT, lotB, QuantityMilli(1_000L)),
            result,
        )
        assertEquals(before, database.tables.value.movements)
        assertEquals(
            DocumentStatus.ACTIVE,
            database.tables.value.stockTransfers.getValue(id).status,
        )
    }

    @Test
    fun `the unassigned lot may go negative at the destination when voiding`() = runTest {
        seeded()
        val id = (move(10_000L) as RegisterStockTransferResult.Registered).transferId
        val unassigned = unassignedId()
        // Sale del puesto, sin lote, el doble de lo que llegó así: queda en -1.
        assertTrue(
            move(2_000L, lotId = unassigned, from = STALL, to = MAIN) is
                RegisterStockTransferResult.Registered,
        )

        assertEquals(VoidStockTransferResult.Voided, void(id, VoidReason.UNDO))
        // Los lotes con código seguían enteros en el puesto; el sin asignar baja a -2.
        assertEquals(-2_000L, at(STALL, unassigned))
    }

    @Test
    fun `a lot of another product or on a product without lots is rejected`() = runTest {
        seeded()
        val before = database.tables.value

        assertEquals(
            RegisterStockTransferResult.LineRejected(0, StockTransferLineRejection.LOT_NOT_FOUND),
            move(1_000L, lotId = lotA, productId = PLAIN),
        )
        assertEquals(
            RegisterStockTransferResult.LineRejected(0, StockTransferLineRejection.LOT_NOT_FOUND),
            move(1_000L, lotId = 999L),
        )
        assertEquals(before.movements, database.tables.value.movements)
        assertTrue(database.tables.value.stockTransfers.isEmpty())
    }

    @Test
    fun `a product without lots keeps a single line without lot`() = runTest {
        seeded()

        val id = (move(1_000L, productId = PLAIN) as RegisterStockTransferResult.Registered)
            .transferId

        assertEquals(listOf(null to 1_000L), linesOf(id))
    }

    private companion object {
        const val MAIN = CatalogFixture.MAIN_LOCATION_ID
        const val STALL = 40L
        const val PRODUCT = 5L
        const val PLAIN = 6L

        fun location(id: Long, systemKey: String?) = Location(
            id = id,
            systemKey = systemKey,
            details = LocationDetails(
                if (systemKey == null) "Lugar $id" else null,
                if (systemKey == null) "lugar $id" else null,
                LocationKind.STALL,
            ),
            archivedAt = null,
            createdAt = CatalogFixture.NOW,
            updatedAt = CatalogFixture.NOW,
        )
    }
}
