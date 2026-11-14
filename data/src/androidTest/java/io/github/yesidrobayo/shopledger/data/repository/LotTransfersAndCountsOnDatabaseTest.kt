package io.github.yesidrobayo.shopledger.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.DataPorts
import io.github.yesidrobayo.shopledger.data.backup.InvariantQueries
import io.github.yesidrobayo.shopledger.data.local.LOCATION_ID
import io.github.yesidrobayo.shopledger.data.local.catalog.FIXED_CLOCK
import io.github.yesidrobayo.shopledger.data.local.stock.location
import io.github.yesidrobayo.shopledger.data.transaction.RestoreLock
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.VoidReason
import io.github.yesidrobayo.shopledger.domain.model.stock.NewStockLot
import io.github.yesidrobayo.shopledger.domain.model.stock.OpenCountRequest
import io.github.yesidrobayo.shopledger.domain.model.stock.StockAdjustmentReason
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType
import io.github.yesidrobayo.shopledger.domain.model.stock.StockTransferLineRequest
import io.github.yesidrobayo.shopledger.domain.model.stock.StockTransferRequest
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.domain.usecase.region.CurrencyLock
import io.github.yesidrobayo.shopledger.domain.usecase.stock.ApplyCountSessionUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.stock.FinishCountResult
import io.github.yesidrobayo.shopledger.domain.usecase.stock.OpenCountResult
import io.github.yesidrobayo.shopledger.domain.usecase.stock.OpenCountSessionUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.stock.RecordCountResult
import io.github.yesidrobayo.shopledger.domain.usecase.stock.RecordCountUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.stock.RegisterStockTransferResult
import io.github.yesidrobayo.shopledger.domain.usecase.stock.RegisterStockTransferUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.stock.VoidStockTransferResult
import io.github.yesidrobayo.shopledger.domain.usecase.stock.VoidStockTransferUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Transferencias y conteos de un producto con lotes sobre la base real: cada lote sale y llega con
 * su fila, la anulación lo devuelve a su lote, el conteo ajusta lote por lote, y las consultas de
 * invariantes quedan en cero, también la de los lotes con código en negativo. El lote A vence el
 * 2026-11-10 con 3 y el B el 2026-11-20 con 5, todo a 1.200 en la ubicación inicial. Productos,
 * lotes y cantidades inventados.
 */
@RunWith(AndroidJUnit4::class)
internal class LotTransfersAndCountsOnDatabaseTest : RepositoryTest() {
    private lateinit var ports: DataPorts
    private lateinit var register: RegisterStockTransferUseCase
    private lateinit var void: VoidStockTransferUseCase
    private lateinit var open: OpenCountSessionUseCase
    private lateinit var record: RecordCountUseCase
    private lateinit var apply: ApplyCountSessionUseCase
    private var warehouse = 0L
    private var cheese = 0L
    private var sooner = 0L
    private var later = 0L

    @Before
    fun connectUseCases() = runBlocking<Unit> {
        ports = DataPorts(database, file, Dispatchers.IO, FIXED_CLOCK, RestoreLock { false })
        val calendar = BusinessCalendarSource(ports.settings)
        register = RegisterStockTransferUseCase(
            ports.transactions,
            ports.products,
            ports.units,
            ports.locations,
            ports.stockTransfers,
            ports.stockLedger,
            calendar,
            FIXED_CLOCK,
            ports.stockLots,
        )
        void = VoidStockTransferUseCase(
            ports.transactions,
            ports.products,
            ports.stockTransfers,
            ports.stockLedger,
            calendar,
            FIXED_CLOCK,
            ports.stockLots,
        )
        open = OpenCountSessionUseCase(
            ports.transactions,
            ports.countSessions,
            ports.products,
            ports.categories,
            ports.locations,
            ports.locationStock,
            calendar,
            FIXED_CLOCK,
            ports.stockLots,
        )
        record = RecordCountUseCase(
            ports.transactions,
            ports.countSessions,
            ports.products,
            ports.units,
        )
        apply = ApplyCountSessionUseCase(
            ports.transactions,
            ports.countSessions,
            ports.products,
            ports.stockLedger,
            ports.lowStockAlerts,
            calendar,
            CurrencyLock(ports.settings),
            FIXED_CLOCK,
            ports.stockLots,
        )
        warehouse = database.locationDao().insert(location("Bodega"))
        seedCheese()
    }

    private suspend fun seedCheese() = commit {
        val name = "Queso inventado"
        cheese = ports.products.insert(newProduct(name, productDetails(name, tracksLots = true)))
        fun lot(code: String, expiry: String) = NewStockLot(
            productId = cheese,
            code = code,
            expiryDate = BusinessDate.parse(expiry),
            purchaseLineId = null,
            createdAt = AT,
        )
        later = ports.stockLots.insert(lot("B", "2026-11-20"))
        sooner = ports.stockLots.insert(lot("A", "2026-11-10"))
        ports.stockLedger.append(openingRow(cheese, units = 5, lotId = later))
        ports.stockLedger.append(
            ledgerRow(
                cheese,
                StockMovementType.OPENING_BALANCE,
                quantity = 3_000,
                balanceAfter = 8_000,
                stockValueAfter = 9_600,
                lotId = sooner,
            ),
        )
    }

    private suspend fun move(
        milli: Long,
        lotId: Long? = null,
        from: Long = LOCATION_ID,
        to: Long = warehouse,
    ): Long {
        val line = StockTransferLineRequest(cheese, QuantityMilli(milli), lotId)
        val result = register(StockTransferRequest(from, to, listOf(line)))
        return (result as RegisterStockTransferResult.Registered).transferId
    }

    private suspend fun lotsAt(locationId: Long): Map<Long, Long> =
        ports.stockLots.findStock(cheese, locationId).associate { it.lotId to it.onHand.milli }

    @Test
    fun aTransferByExpiryKeepsEachLotAndItsVoidReturnsThem() = runBlocking {
        val id = move(4_000L)

        val lines = ports.stockTransfers.findLines(id).map { it.line.lotId to it.line.quantity }
        assertEquals(
            listOf(sooner to QuantityMilli(3_000L), later to QuantityMilli(1_000L)),
            lines,
        )
        assertEquals(mapOf(later to 4_000L, sooner to 0L), lotsAt(LOCATION_ID))
        assertEquals(mapOf(later to 1_000L, sooner to 3_000L), lotsAt(warehouse))
        val product = requireNotNull(database.productDao().findById(cheese))
        assertEquals(listOf(8_000L, 9_600L), listOf(product.onHandMilli, product.stockValue))
        assertInvariantsHold()

        assertEquals(VoidStockTransferResult.Voided, void(id, VoidReason.ENTRY_ERROR))

        assertEquals(mapOf(later to 5_000L, sooner to 3_000L), lotsAt(LOCATION_ID))
        assertEquals(mapOf(later to 0L, sooner to 0L), lotsAt(warehouse))
        assertInvariantsHold()
    }

    @Test
    fun whatTheLotsLackLeavesTheUnassignedLotAndOnlyItGoesNegative() = runBlocking {
        val id = move(10_000L, lotId = later)

        val unassigned = requireNotNull(database.stockLotDao().findUnassigned(cheese)).id
        assertEquals(
            listOf(later, sooner, unassigned),
            ports.stockTransfers.findLines(id).map { it.line.lotId },
        )
        assertEquals(-2_000L, lotsAt(LOCATION_ID).getValue(unassigned))
        assertInvariantsHold()
    }

    @Test
    fun voidingIsRefusedWhenALotAlreadyLeftTheDestination() = runBlocking {
        val id = move(4_000L)
        // Vuelve 1 del lote A desde la bodega: allí quedan 2 de los 3 que llegaron.
        move(1_000L, lotId = sooner, from = warehouse, to = LOCATION_ID)
        val rowsBefore = ports.stockLedger.findByProduct(cheese).size

        assertEquals(
            VoidStockTransferResult.LotAlreadyUsed(cheese, sooner, QuantityMilli(1_000L)),
            void(id, VoidReason.ENTRY_ERROR),
        )
        assertEquals(rowsBefore, ports.stockLedger.findByProduct(cheese).size)
        assertEquals("ACTIVE", database.stockTransferDao().findById(id)?.status)
        assertInvariantsHold()
    }

    @Test
    fun aBlindCountByLotAppliesOneAdjustmentPerLot() = runBlocking {
        val id = (open(OpenCountRequest(LOCATION_ID)) as OpenCountResult.Opened).sessionId
        val lines = ports.countSessions.findLines(id).filter { it.productId == cheese }
        // El que vence antes primero, con su esperado congelado; sin lote sin asignar.
        assertEquals(
            listOf(sooner to 3_000L, later to 5_000L),
            lines.map { it.lotId to it.expected.milli },
        )
        val (first, second) = lines

        assertEquals(RecordCountResult.Recorded, record(id, first.id, QuantityMilli(2_000L)))
        assertEquals(RecordCountResult.Recorded, record(id, second.id, QuantityMilli(6_000L)))
        assertEquals(FinishCountResult.Applied(2), apply(id))

        val rows = ports.stockLedger.findByProduct(cheese).takeLast(2)
        assertTrue(rows.all { it.reason == StockAdjustmentReason.COUNT_CORRECTION })
        assertEquals(
            listOf(sooner to -1_000L, later to 1_000L),
            rows.map { it.movement.key.lotId to it.movement.quantity.milli },
        )
        assertEquals(mapOf(later to 6_000L, sooner to 2_000L), lotsAt(LOCATION_ID))
        assertEquals(8_000L, database.productDao().findById(cheese)?.onHandMilli)
        assertInvariantsHold()
    }

    @Test
    fun aLotMovedAfterOpeningTheCountNeverGoesNegative() = runBlocking {
        val id = (open(OpenCountRequest(LOCATION_ID)) as OpenCountResult.Opened).sessionId
        val line = ports.countSessions.findLines(id).single { it.lotId == sooner }
        // Después de abrir, los 3 del lote A se van a la bodega.
        move(3_000L, lotId = sooner)

        record(id, line.id, QuantityMilli.ZERO)
        assertEquals(FinishCountResult.Applied(1), apply(id))

        val unassigned = requireNotNull(database.stockLotDao().findUnassigned(cheese)).id
        assertEquals(0L, lotsAt(LOCATION_ID).getValue(sooner))
        assertEquals(-3_000L, lotsAt(LOCATION_ID).getValue(unassigned))
        assertInvariantsHold()
    }

    /** Todas las consultas de invariantes, también la de los lotes, sin ninguna fila. */
    private suspend fun assertInvariantsHold() {
        for (query in InvariantQueries.MODEL) {
            val rows = query.sql.trimEnd().removeSuffix(";")
            assertEquals(query.label, 0L, count("SELECT COUNT(*) FROM ($rows)"))
        }
    }
}
