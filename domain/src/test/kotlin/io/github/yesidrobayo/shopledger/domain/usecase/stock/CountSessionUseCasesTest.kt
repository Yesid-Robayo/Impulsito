package io.github.yesidrobayo.shopledger.domain.usecase.stock

import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.stock.CountSessionStatus
import io.github.yesidrobayo.shopledger.domain.model.stock.Location
import io.github.yesidrobayo.shopledger.domain.model.stock.LocationDetails
import io.github.yesidrobayo.shopledger.domain.model.stock.LocationKind
import io.github.yesidrobayo.shopledger.domain.model.stock.OpenCountRequest
import io.github.yesidrobayo.shopledger.domain.model.stock.StockAdjustmentReason
import io.github.yesidrobayo.shopledger.domain.model.stock.StockAdjustmentRequest
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.rule.UnitQuantityCheck
import io.github.yesidrobayo.shopledger.domain.testing.FakeCountSessionRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakeLocationRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakeLocationStockSource
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CatalogFixture
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.domain.usecase.region.CurrencyLock
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Conteos por sesión, a ciegas: abrir congela lo esperado, anotar no toca el libro, y aplicar crea
 * un ajuste por diferencia en una transacción. Esperado 12 y contado 10 dan −2000. Valores a mano.
 */
class CountSessionUseCasesTest {
    private val fixture = StockFixture()
    private val catalog = fixture.catalog
    private val database = catalog.database
    private val sessions = FakeCountSessionRepository(database)
    private val calendar = BusinessCalendarSource(catalog.settings)
    private val open = OpenCountSessionUseCase(
        catalog.transactions,
        sessions,
        catalog.products,
        catalog.categories,
        FakeLocationRepository(database),
        FakeLocationStockSource(database),
        calendar,
        catalog.clock,
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
    )
    private val cancel = CancelCountSessionUseCase(catalog.transactions, sessions, catalog.clock)

    private suspend fun seeded() {
        // Doce de uno y cinco de otro, en la ubicación inicial, a 100 cada unidad.
        fixture.seedStock(id = 1L, units = 12_000L, unitCost = 100L)
        fixture.seedStock(id = 2L, units = 5_000L, unitCost = 100L)
        catalog.seedProduct(id = 3L, name = "Servicio", stockMode = ProductStockMode.UNTRACKED)
        database.seed {
            it.copy(
                locations = it.locations + (MAIN to location(MAIN, archived = false)) +
                    (CLOSED to location(CLOSED, archived = true)),
            )
        }
    }

    private suspend fun opened(): Long =
        (open(OpenCountRequest(MAIN)) as OpenCountResult.Opened).sessionId

    private fun linesOf(sessionId: Long) =
        database.tables.value.countLines.values.filter { it.sessionId == sessionId }
            .sortedBy { it.id }

    @Test
    fun `opening freezes what each tracked product has, blind by default`() = runTest {
        seeded()

        val id = opened()

        val session = database.tables.value.countSessions.getValue(id)
        assertTrue(session.isBlind)
        assertEquals(CountSessionStatus.OPEN, session.status)
        assertEquals(
            listOf(1L to 12_000L, 2L to 5_000L),
            linesOf(id).map { it.productId to it.expected.milli },
        )
        assertTrue(linesOf(id).all { it.counted == null && it.difference == null })
        assertEquals(OpenCountResult.AlreadyOpen(id), open(OpenCountRequest(MAIN)))
    }

    @Test
    fun `expected 12 and counted 10 apply an adjustment of minus 2000 with the session`() =
        runTest {
            seeded()
            val id = opened()
            val (first, second) = linesOf(id)
            assertEquals(RecordCountResult.Recorded, record(id, first.id, QuantityMilli(10_000L)))
            // El segundo cuenta lo mismo que se esperaba: no hay diferencia que ajustar.
            assertEquals(RecordCountResult.Recorded, record(id, second.id, QuantityMilli(5_000L)))

            assertEquals(FinishCountResult.Applied(1), apply(id))

            val row = fixture.movementsOf(1L).last()
            assertEquals(StockMovementType.ADJUSTMENT, row.movement.type)
            assertEquals(StockAdjustmentReason.COUNT_CORRECTION, row.reason)
            assertEquals(QuantityMilli(-2_000L), row.movement.quantity)
            assertEquals(id, row.source.countSessionId)
            assertEquals(MAIN, row.movement.key.locationId)
            assertEquals(QuantityMilli(10_000L), fixture.product(1L).onHand)
            assertEquals(QuantityMilli(5_000L), fixture.product(2L).onHand)
            val session = database.tables.value.countSessions.getValue(id)
            assertEquals(CountSessionStatus.APPLIED, session.status)
            assertEquals(FinishCountResult.AlreadyFinished, apply(id))
            assertEquals(
                RecordCountResult.SessionClosed,
                record(id, first.id, QuantityMilli(1_000L)),
            )
        }

    @Test
    fun `lines without a count do not change, and a later movement is seen`() = runTest {
        seeded()
        val id = opened()
        // Un minuto después de abrir se registra una merma del primero.
        fixture.now = CatalogFixture.NOW + 1.minutes
        fixture.adjust(
            StockAdjustmentRequest.Change(1L, StockAdjustmentReason.DAMAGED, QuantityMilli(1_000L)),
        )

        assertEquals(setOf(1L), sessions.productsMovedSinceOpen(id))
        assertEquals(FinishCountResult.Applied(0), apply(id))
        assertEquals(QuantityMilli(11_000L), fixture.product(1L).onHand)
    }

    @Test
    fun `cancelling writes no movement and keeps what was counted`() = runTest {
        seeded()
        val id = opened()
        val first = linesOf(id).first()
        record(id, first.id, QuantityMilli(3_000L))
        val movements = fixture.movementsOf(1L).size

        assertEquals(FinishCountResult.Cancelled, cancel(id))

        assertEquals(movements, fixture.movementsOf(1L).size)
        assertEquals(QuantityMilli(3_000L), linesOf(id).first().counted)
        assertEquals(FinishCountResult.AlreadyFinished, cancel(id))
        assertEquals(FinishCountResult.NotFound, cancel(99L))
        assertEquals(FinishCountResult.NotFound, apply(99L))
    }

    @Test
    fun `a count is checked against its unit and the stock limit, and can be erased`() = runTest {
        seeded()
        val id = opened()
        val line = linesOf(id).first()

        assertEquals(
            RecordCountResult.InvalidQuantity(UnitQuantityCheck.FractionNotAllowed),
            record(id, line.id, QuantityMilli(1_500L)),
        )
        assertEquals(
            RecordCountResult.AboveLimit,
            record(id, line.id, QuantityMilli(8_000_001_000L)),
        )
        assertEquals(RecordCountResult.NotFound, record(id, 999L, QuantityMilli(1_000L)))
        assertEquals(RecordCountResult.NotFound, record(77L, line.id, QuantityMilli(1_000L)))
        record(id, line.id, QuantityMilli(4_000L))
        assertEquals(RecordCountResult.Recorded, record(id, line.id, null))
        assertNull(linesOf(id).first().counted)
    }

    @Test
    fun `opening checks location, category, scope and note before writing`() = runTest {
        seeded()

        assertEquals(OpenCountResult.LocationUnavailable, open(OpenCountRequest(CLOSED)))
        assertEquals(OpenCountResult.LocationUnavailable, open(OpenCountRequest(88L)))
        assertEquals(
            OpenCountResult.CategoryNotFound,
            open(OpenCountRequest(MAIN, categoryId = 5L)),
        )
        assertEquals(
            OpenCountResult.NoteTooLong,
            open(OpenCountRequest(MAIN, note = "n".repeat(201))),
        )
        assertTrue(database.tables.value.countSessions.isEmpty())
    }

    @Test
    fun `an empty catalog has nothing to count, and a restore writes nothing`() = runTest {
        database.seed { it.copy(locations = it.locations + (MAIN to location(MAIN, false))) }

        assertEquals(OpenCountResult.NothingToCount, open(OpenCountRequest(MAIN)))
        catalog.transactions.restoreInProgress = true
        assertEquals(
            OpenCountResult.StorageFailed(StorageFailure.RESTORE_IN_PROGRESS),
            open(OpenCountRequest(MAIN)),
        )
    }

    private companion object {
        const val MAIN = CatalogFixture.MAIN_LOCATION_ID
        const val CLOSED = 51L

        fun location(id: Long, archived: Boolean) = Location(
            id = id,
            systemKey = if (id == MAIN) "MAIN" else null,
            details = if (id == MAIN) {
                LocationDetails(null, null, LocationKind.STORE)
            } else {
                LocationDetails("Lugar $id", "lugar $id", LocationKind.STALL)
            },
            archivedAt = if (archived) CatalogFixture.NOW else null,
            createdAt = CatalogFixture.NOW,
            updatedAt = CatalogFixture.NOW,
        )
    }
}
