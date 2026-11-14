package io.github.yesidrobayo.shopledger.domain.usecase.stock

import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.VoidReason
import io.github.yesidrobayo.shopledger.domain.model.stock.Location
import io.github.yesidrobayo.shopledger.domain.model.stock.LocationDetails
import io.github.yesidrobayo.shopledger.domain.model.stock.LocationKind
import io.github.yesidrobayo.shopledger.domain.model.stock.StockTransferLineRequest
import io.github.yesidrobayo.shopledger.domain.model.stock.StockTransferRequest
import io.github.yesidrobayo.shopledger.domain.testing.FakeLocationRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakeLocationStockSource
import io.github.yesidrobayo.shopledger.domain.testing.FakeStockTransferRepository
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CatalogFixture
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La lista de transferencias por tramos y la hoja de una: cuántos productos lleva cada una, hasta
 * dónde puede haber más, y lo que dejaría en negativo su anulación. Valores escritos a mano.
 */
class StockTransferReadsTest {
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
    )
    private val void = VoidStockTransferUseCase(
        catalog.transactions,
        catalog.products,
        transfers,
        catalog.ledger,
        calendar,
        catalog.clock,
    )
    private val observe =
        ObserveStockTransfersUseCase(transfers, locations, calendar, catalog.clock)
    private val read = ReadStockTransferUseCase(
        transfers,
        catalog.products,
        catalog.units,
        FakeLocationStockSource(database),
    )

    private suspend fun seeded(stallCreatedAt: Instant = CatalogFixture.NOW) {
        fixture.seedStock(id = 1L, units = 10_000L, unitCost = 1_500L)
        fixture.seedStock(id = 2L, units = 5_000L, unitCost = 800L)
        database.seed {
            it.copy(
                locations = it.locations +
                    (MAIN to location(MAIN, "MAIN", CatalogFixture.NOW)) +
                    (STALL to location(STALL, null, stallCreatedAt)),
            )
        }
    }

    private suspend fun move(from: Long, to: Long, vararg lines: Pair<Long, Long>): Long {
        val request = StockTransferRequest(
            from,
            to,
            lines.map { (product, milli) ->
                StockTransferLineRequest(product, QuantityMilli(milli))
            },
        )
        return (register(request) as RegisterStockTransferResult.Registered).transferId
    }

    @Test
    fun `the list counts the products of each transfer, newest first`() = runTest {
        seeded()
        val first = move(MAIN, STALL, 1L to 4_000L, 2L to 1_000L)
        val second = move(STALL, MAIN, 1L to 1_000L)

        val read = observe(days = 30).first() as StockTransfersRead.Ready

        assertEquals(listOf(second, first), read.transfers.map { it.transfer.id })
        assertEquals(listOf(1, 2), read.transfers.map { it.productCount })
        // La ubicación de la persona nació hoy: no puede haber transferencias más viejas.
        assertFalse(read.hasOlder)
    }

    @Test
    fun `older transfers may exist only after the first own location was created`() = runTest {
        seeded(stallCreatedAt = CatalogFixture.NOW - 100.days)

        val short = observe(days = 30).first() as StockTransfersRead.Ready
        val long = observe(days = 101).first() as StockTransfersRead.Ready

        assertTrue(short.hasOlder)
        assertFalse(long.hasOlder)
        assertTrue(short.transfers.isEmpty())
    }

    @Test
    fun `with only the initial location there is nothing older`() = runTest {
        fixture.seedStock(id = 1L, units = 1_000L, unitCost = 100L)

        val read = observe(days = 1).first() as StockTransfersRead.Ready

        assertTrue(read.transfers.isEmpty())
        assertFalse(read.hasOlder)
    }

    @Test
    fun `an unknown zone has no today to count days from`() = runTest {
        val unknown = StockFixture(zoneId = "Zona/Inexistente").catalog
        val observeUnknown = ObserveStockTransfersUseCase(
            FakeStockTransferRepository(unknown.database),
            FakeLocationRepository(unknown.database),
            BusinessCalendarSource(unknown.settings),
            unknown.clock,
        )

        assertEquals(StockTransfersRead.UnknownZone, observeUnknown(days = 7).first())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a window needs at least one day`() {
        observe(days = 0)
    }

    @Test
    fun `the detail warns what voiding would leave below zero at the destination`() = runTest {
        seeded()
        // 4 llegan al puesto y 3 vuelven: quedan 1, y anular la primera dejaría 1 - 4 = -3.
        val first = move(MAIN, STALL, 1L to 4_000L, 2L to 1_000L)
        move(STALL, MAIN, 1L to 3_000L)

        val detail = read(first)

        requireNotNull(detail)
        assertEquals(listOf(1L, 2L), detail.lines.map { it.productId })
        assertEquals(listOf(4_000L, 1_000L), detail.lines.map { it.quantity.milli })
        assertEquals(listOf(1L, 2L), detail.lines.map { it.product?.id })
        assertTrue(detail.lines.all { it.unit != null })
        assertEquals(listOf(1L), detail.voidShortfalls.map { it.product.id })
        assertEquals(QuantityMilli(-3_000L), detail.voidShortfalls.single().remaining)
    }

    @Test
    fun `two lines of one product add up before comparing`() = runTest {
        seeded()
        // 2 + 2 llegan; vuelven 1: quedan 3, y anular dejaría 3 - 4 = -1.
        val first = move(MAIN, STALL, 1L to 2_000L, 1L to 2_000L)
        move(STALL, MAIN, 1L to 1_000L)

        val detail = requireNotNull(read(first))

        assertEquals(QuantityMilli(-1_000L), detail.voidShortfalls.single().remaining)
    }

    @Test
    fun `a voided transfer has nothing left to warn and a missing one is null`() = runTest {
        seeded()
        val id = move(MAIN, STALL, 1L to 4_000L)
        move(STALL, MAIN, 1L to 4_000L)
        void(id, VoidReason.ENTRY_ERROR)

        val detail = requireNotNull(read(id))

        assertTrue(detail.transfer.voiding != null)
        assertTrue(detail.voidShortfalls.isEmpty())
        assertNull(read(999L))
    }

    @Test
    fun `an active transfer whose destination still has the stock warns nothing`() = runTest {
        seeded()
        val id = move(MAIN, STALL, 1L to 4_000L)

        assertTrue(requireNotNull(read(id)).voidShortfalls.isEmpty())
    }

    private companion object {
        const val MAIN = CatalogFixture.MAIN_LOCATION_ID
        const val STALL = 40L

        fun location(id: Long, systemKey: String?, createdAt: Instant) = Location(
            id = id,
            systemKey = systemKey,
            details = LocationDetails(
                if (systemKey == null) "Lugar $id" else null,
                if (systemKey == null) "lugar $id" else null,
                LocationKind.STALL,
            ),
            archivedAt = null,
            createdAt = createdAt,
            updatedAt = createdAt,
        )
    }
}
