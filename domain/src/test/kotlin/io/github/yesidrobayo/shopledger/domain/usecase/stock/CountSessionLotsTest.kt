package io.github.yesidrobayo.shopledger.domain.usecase.stock

import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.stock.Location
import io.github.yesidrobayo.shopledger.domain.model.stock.LocationDetails
import io.github.yesidrobayo.shopledger.domain.model.stock.LocationKind
import io.github.yesidrobayo.shopledger.domain.model.stock.OpenCountRequest
import io.github.yesidrobayo.shopledger.domain.model.stock.StockAdjustmentReason
import io.github.yesidrobayo.shopledger.domain.model.stock.StockAdjustmentRequest
import io.github.yesidrobayo.shopledger.domain.model.stock.StockKey
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType
import io.github.yesidrobayo.shopledger.domain.testing.FakeCountSessionRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakeLocationRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakeLocationStockSource
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CatalogFixture
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.domain.usecase.region.CurrencyLock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El conteo de un producto que se controla por lotes, también a ciegas: una línea por lote con su
 * esperado congelado, y un ajuste por lote al aplicar. Lotes y cantidades inventados: el lote B
 * vence el 2026-11-10 con 2, el A el 2026-11-20 con 3, a 100 la unidad.
 */
class CountSessionLotsTest {
    private val fixture = StockFixture()
    private val catalog = fixture.catalog
    private val database = catalog.database
    private val sessions = FakeCountSessionRepository(database)
    private val locations = FakeLocationRepository(database)
    private val calendar = BusinessCalendarSource(catalog.settings)
    private val open = OpenCountSessionUseCase(
        catalog.transactions,
        sessions,
        catalog.products,
        catalog.categories,
        locations,
        FakeLocationStockSource(database),
        calendar,
        catalog.clock,
        catalog.lots,
    )
    private val record =
        RecordCountUseCase(catalog.transactions, sessions, catalog.products, catalog.units)
    private val apply = ApplyCountSessionUseCase(
        catalog.transactions,
        sessions,
        catalog.products,
        catalog.ledger,
        catalog.alerts,
        calendar,
        CurrencyLock(catalog.settings),
        catalog.clock,
        catalog.lots,
    )
    private val view = ObserveCountSessionUseCase(
        sessions,
        catalog.products,
        catalog.units,
        locations,
        catalog.categories,
        catalog.lots,
    )

    private var lotA = 0L
    private var lotB = 0L

    private suspend fun seeded() {
        fixture.seedStock(id = LOTTED, units = 0L, unitCost = 100L, tracksLots = true)
        fixture.seedStock(id = PLAIN, units = 4_000L, unitCost = 100L)
        lotA = fixture.seedLot(LOTTED, "A-20", expiry = "2026-11-20")
        lotB = fixture.seedLot(LOTTED, "B-10", expiry = "2026-11-10")
        fixture.receiveIntoLot(LOTTED, lotA, units = 3_000L, unitCost = 100L)
        fixture.receiveIntoLot(LOTTED, lotB, units = 2_000L, unitCost = 100L)
        database.seed { it.copy(locations = it.locations + (MAIN to main())) }
    }

    private suspend fun opened(): Long =
        (open(OpenCountRequest(MAIN)) as OpenCountResult.Opened).sessionId

    private fun linesOf(sessionId: Long) =
        database.tables.value.countLines.values.filter { it.sessionId == sessionId }
            .sortedBy { it.id }

    private fun lineOf(sessionId: Long, lotId: Long?) =
        linesOf(sessionId).single { it.productId == LOTTED && it.lotId == lotId }

    private suspend fun lotOnHand(lotId: Long): Long =
        catalog.ledger.onHandAt(StockKey(LOTTED, MAIN, lotId)).milli

    private fun unassignedId(): Long = database.tables.value.lots.values
        .single { it.productId == LOTTED && it.isUnassigned }.id

    @Test
    fun `opening counts each lot with stock, the one that expires first on top`() = runTest {
        seeded()

        val id = opened()

        assertTrue(database.tables.value.countSessions.getValue(id).isBlind)
        // El que vence antes primero; sin lote sin asignar no hay fila para él.
        assertEquals(
            listOf(
                Triple(LOTTED, lotB, 2_000L),
                Triple(LOTTED, lotA, 3_000L),
                Triple(PLAIN, null, 4_000L),
            ),
            linesOf(id).map { Triple(it.productId, it.lotId, it.expected.milli) },
        )
    }

    @Test
    fun `without the lots at hand a product with lots stays out, as before`() = runTest {
        seeded()
        val withoutLots = OpenCountSessionUseCase(
            catalog.transactions,
            sessions,
            catalog.products,
            catalog.categories,
            locations,
            FakeLocationStockSource(database),
            calendar,
            catalog.clock,
        )

        val id = (withoutLots(OpenCountRequest(MAIN)) as OpenCountResult.Opened).sessionId

        assertEquals(listOf(PLAIN), linesOf(id).map { it.productId })
    }

    @Test
    fun `applying writes one adjustment per lot with a difference, in that lot`() = runTest {
        seeded()
        val id = opened()
        // B: esperado 2, contado 1. A: esperado 3, contado 5. El otro, igual.
        record(id, lineOf(id, lotB).id, QuantityMilli(1_000L))
        record(id, lineOf(id, lotA).id, QuantityMilli(5_000L))
        record(id, linesOf(id).single { it.productId == PLAIN }.id, QuantityMilli(4_000L))

        assertEquals(FinishCountResult.Applied(2), apply(id))

        val rows = fixture.movementsOf(LOTTED).takeLast(2)
        assertTrue(rows.all { it.movement.type == StockMovementType.ADJUSTMENT })
        assertTrue(rows.all { it.reason == StockAdjustmentReason.COUNT_CORRECTION })
        assertTrue(rows.all { it.source.countSessionId == id })
        assertEquals(
            listOf(lotB to -1_000L, lotA to 2_000L),
            rows.map { it.movement.key.lotId to it.movement.quantity.milli },
        )
        assertEquals(1_000L, lotOnHand(lotB))
        assertEquals(5_000L, lotOnHand(lotA))
        assertEquals(QuantityMilli(6_000L), fixture.product(LOTTED).onHand)
        // Nada pasó por el lote sin asignar.
        assertTrue(database.tables.value.lots.values.none { it.isUnassigned })
    }

    @Test
    fun `a lot that moved after opening never goes negative, the rest leaves unassigned`() =
        runTest {
            seeded()
            val id = opened()
            // Después de abrir vencen 2 del lote A: le queda 1.
            fixture.adjust(
                StockAdjustmentRequest.Change(
                    LOTTED,
                    StockAdjustmentReason.EXPIRED,
                    QuantityMilli(2_000L),
                    lotId = lotA,
                ),
            )
            // Se contó 0 de A contra los 3 esperados: −3, pero A solo tiene 1.
            record(id, lineOf(id, lotA).id, QuantityMilli.ZERO)

            assertEquals(FinishCountResult.Applied(2), apply(id))

            val unassigned = unassignedId()
            assertEquals(0L, lotOnHand(lotA))
            assertEquals(-2_000L, lotOnHand(unassigned))
            assertEquals(
                listOf(lotA to -1_000L, unassigned to -2_000L),
                fixture.movementsOf(LOTTED).takeLast(2)
                    .map { it.movement.key.lotId to it.movement.quantity.milli },
            )
        }

    @Test
    fun `a product with lots but no stock is counted on an unassigned lot made on apply`() =
        runTest {
            fixture.seedStock(id = LOTTED, units = 0L, unitCost = 100L, tracksLots = true)
            database.seed { it.copy(locations = it.locations + (MAIN to main())) }
            val id = opened()
            val line = linesOf(id).single()
            assertNull(line.lotId)
            assertEquals(QuantityMilli.ZERO, line.expected)

            record(id, line.id, QuantityMilli(4_000L))
            assertEquals(FinishCountResult.Applied(1), apply(id))

            assertEquals(4_000L, lotOnHand(unassignedId()))
        }

    @Test
    fun `the unassigned lot with stock is counted last and corrected in place`() = runTest {
        seeded()
        // Un sobrante sin lote deja 2 en el lote sin asignar.
        fixture.adjust(
            StockAdjustmentRequest.Change(
                LOTTED,
                StockAdjustmentReason.OTHER,
                QuantityMilli(2_000L),
                increase = true,
                note = "Sobrante",
            ),
        )
        val unassigned = unassignedId()
        val id = opened()
        val line = lineOf(id, unassigned)
        assertEquals(QuantityMilli(2_000L), line.expected)
        assertEquals(unassigned, linesOf(id).last { it.productId == LOTTED }.lotId)

        record(id, line.id, QuantityMilli.ZERO)
        apply(id)

        assertEquals(0L, lotOnHand(unassigned))
    }

    @Test
    fun `the session view names the lot of each line`() = runTest {
        seeded()
        val id = opened()

        val lines = requireNotNull(view(id).first()).lines

        assertEquals(
            listOf(lotB, lotA, null),
            lines.map { it.lot?.id },
        )
        assertEquals(listOf("B-10", "A-20"), lines.mapNotNull { it.lot?.code })
    }

    private companion object {
        const val MAIN = CatalogFixture.MAIN_LOCATION_ID
        const val LOTTED = 1L
        const val PLAIN = 2L

        fun main() = Location(
            id = MAIN,
            systemKey = "MAIN",
            details = LocationDetails(null, null, LocationKind.STORE),
            archivedAt = null,
            createdAt = CatalogFixture.NOW,
            updatedAt = CatalogFixture.NOW,
        )
    }
}
