package io.github.yesidrobayo.shopledger.domain.usecase.stock

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.stock.CountSessionStatus
import io.github.yesidrobayo.shopledger.domain.model.stock.Location
import io.github.yesidrobayo.shopledger.domain.model.stock.LocationDetails
import io.github.yesidrobayo.shopledger.domain.model.stock.LocationKind
import io.github.yesidrobayo.shopledger.domain.model.stock.OpenCountRequest
import io.github.yesidrobayo.shopledger.domain.testing.FakeCountSessionRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakeLocationRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakeLocationStockSource
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CatalogFixture
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.domain.usecase.region.CurrencyLock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lo que la pantalla lee de los conteos: el progreso de los abiertos, las diferencias de los
 * terminados, una sesión con sus productos y la revisión con su valor. Valores escritos a mano.
 */
class CountSessionReadsTest {
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
    private val overview = ObserveCountOverviewUseCase(sessions, locations, catalog.categories)
    private val session = ObserveCountSessionUseCase(
        sessions,
        catalog.products,
        catalog.units,
        locations,
        catalog.categories,
    )
    private val review = ReviewCountSessionUseCase(sessions)

    private suspend fun opened(): Long {
        // Doce del primero a 100 cada uno y cinco del segundo a 80, en la ubicación inicial.
        fixture.seedStock(id = 1L, units = 12_000L, unitCost = 100L)
        fixture.seedStock(id = 2L, units = 5_000L, unitCost = 80L)
        database.seed { it.copy(locations = it.locations + (MAIN to main())) }
        return (open(OpenCountRequest(MAIN)) as OpenCountResult.Opened).sessionId
    }

    private suspend fun lineIds(id: Long) =
        requireNotNull(session(id).first()).lines.associate { it.line.productId to it.line.id }

    @Test
    fun `an open count shows its progress and survives as long as the database does`() = runTest {
        val id = opened()
        record(id, lineIds(id).getValue(1L), QuantityMilli(10_000L))

        val read = overview().first()

        val open = read.open.single()
        assertEquals(id, open.session.id)
        assertEquals(1, open.counted)
        assertEquals(2, open.total)
        assertEquals(1, open.differences)
        assertEquals(MAIN, open.location?.id)
        assertTrue(read.finished.isEmpty())
        assertFalse(read.showsLocations)
    }

    @Test
    fun `an applied count moves to the previous ones with its differences`() = runTest {
        val id = opened()
        record(id, lineIds(id).getValue(1L), QuantityMilli(10_000L))
        apply(id)

        val read = overview().first()

        assertTrue(read.open.isEmpty())
        val done = read.finished.single()
        assertEquals(CountSessionStatus.APPLIED, done.session.status)
        assertEquals(1, done.differences)
    }

    @Test
    fun `a session lists its products by name with their units`() = runTest {
        val id = opened()

        val view = requireNotNull(session(id).first())

        assertEquals(listOf(1L, 2L), view.lines.map { it.line.productId })
        assertTrue(view.lines.all { it.product != null && it.unit != null })
        assertEquals(MAIN, view.location?.id)
        assertNull(view.category)
        assertNull(session(999L).first())
    }

    @Test
    fun `the review values the surplus and the shortage like applying would`() = runTest {
        val id = opened()
        val lines = lineIds(id)
        // Faltan 2 del primero, a 100: 200. Sobra 1 del segundo, a 80: 80.
        record(id, lines.getValue(1L), QuantityMilli(10_000L))
        record(id, lines.getValue(2L), QuantityMilli(6_000L))

        val result = review(requireNotNull(session(id).first()))

        assertEquals(2, result.adjustments)
        assertEquals(Money(80L), result.surplus)
        assertEquals(Money(200L), result.shortage)
        assertTrue(result.moved.isEmpty())
    }

    @Test
    fun `lines without counting or without difference are not adjustments`() = runTest {
        val id = opened()
        record(id, lineIds(id).getValue(2L), QuantityMilli(5_000L))

        val result = review(requireNotNull(session(id).first()))

        assertEquals(0, result.adjustments)
        assertEquals(Money.ZERO, result.surplus)
        assertEquals(Money.ZERO, result.shortage)
    }

    private companion object {
        const val MAIN = CatalogFixture.MAIN_LOCATION_ID

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
