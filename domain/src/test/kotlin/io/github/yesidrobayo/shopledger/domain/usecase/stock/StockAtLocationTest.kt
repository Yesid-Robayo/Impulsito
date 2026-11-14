package io.github.yesidrobayo.shopledger.domain.usecase.stock

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.stock.Location
import io.github.yesidrobayo.shopledger.domain.model.stock.LocationDetails
import io.github.yesidrobayo.shopledger.domain.model.stock.LocationKind
import io.github.yesidrobayo.shopledger.domain.model.stock.StockAdjustmentReason
import io.github.yesidrobayo.shopledger.domain.model.stock.StockAdjustmentRequest
import io.github.yesidrobayo.shopledger.domain.testing.FakeLocationRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakeLocationStockSource
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CatalogFixture
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.domain.usecase.region.CurrencyLock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El stock de una sola ubicación: ajustarla, contarla, verla en el inventario y en el ajuste. El
 * costo y el valor siguen siendo uno solo por producto. Nombres y cantidades inventados.
 */
class StockAtLocationTest {
    private val fixture = StockFixture()
    private val catalog = fixture.catalog
    private val locations = FakeLocationRepository(catalog.database)
    private val stock = FakeLocationStockSource(catalog.database)
    private val adjust = AdjustStockUseCase(
        catalog.transactions, catalog.products, catalog.units, catalog.systemRows, catalog.ledger,
        catalog.alerts, BusinessCalendarSource(catalog.settings), CurrencyLock(catalog.settings),
        catalog.clock, locations, stock,
    )
    private val inventory = ObserveInventoryUseCase(catalog.products, catalog.units, stock)
    private val subject = ObserveStockAdjustmentSubjectUseCase(
        catalog.transactions,
        catalog.products,
        catalog.units,
        catalog.ledger,
        stock,
    )

    private suspend fun seeded() {
        // Diez en la inicial, a 100 cada una: el inventario inicial siempre entra allí.
        fixture.seedStock(id = 1L, units = 10_000L, unitCost = 100L, minStock = 3_000L)
        catalog.database.seed {
            it.copy(
                locations = it.locations +
                    (STALL to location(STALL, archived = false)) +
                    (CLOSED to location(CLOSED, archived = true)),
            )
        }
    }

    @Test
    fun aDamageAtTheStallLeavesFromTheStallAndCanLeaveItNegative() = runTest {
        seeded()

        val result = adjust(
            StockAdjustmentRequest.Change(
                1L,
                StockAdjustmentReason.DAMAGED,
                QuantityMilli(2_000L),
                locationId = STALL,
            ),
        )

        assertTrue(result is StockAdjustmentResult.Adjusted)
        assertEquals(STALL, fixture.movementsOf(1L).last().movement.key.locationId)
        assertEquals(QuantityMilli(-2_000L), stock.onHandAt(1L, STALL))
        assertEquals(QuantityMilli(10_000L), stock.onHandAt(1L, CatalogFixture.MAIN_LOCATION_ID))
        assertEquals(QuantityMilli(8_000L), fixture.product(1L).onHand)
    }

    @Test
    fun aCountIsComparedWithWhatIsInTheCountedLocationNotWithTheTotal() = runTest {
        seeded()
        adjust(
            StockAdjustmentRequest.Change(
                1L,
                StockAdjustmentReason.DAMAGED,
                QuantityMilli(2_000L),
                locationId = STALL,
            ),
        )

        // En la inicial hay 10: contar 10 no cambia nada, aunque el producto tenga 8.
        assertEquals(
            StockAdjustmentResult.NoDifference,
            adjust(StockAdjustmentRequest.Count(1L, QuantityMilli(10_000L))),
        )
        // En el puesto hay −2: contar 0 entra 2.
        val counted = adjust(
            StockAdjustmentRequest.Count(1L, QuantityMilli.ZERO, locationId = STALL),
        )

        assertEquals(
            QuantityMilli(10_000L),
            (counted as StockAdjustmentResult.Adjusted).onHandAfter,
        )
        assertEquals(QuantityMilli(2_000L), fixture.movementsOf(1L).last().movement.quantity)
        assertEquals(QuantityMilli.ZERO, stock.onHandAt(1L, STALL))
    }

    @Test
    fun anArchivedOrUnknownLocationIsNotAdjusted() = runTest {
        seeded()
        val before = fixture.movementsOf(1L).size

        val archived =
            adjust(StockAdjustmentRequest.Count(1L, QuantityMilli(1_000L), locationId = CLOSED))
        val unknown =
            adjust(StockAdjustmentRequest.Count(1L, QuantityMilli(1_000L), locationId = 77L))

        assertEquals(StockAdjustmentResult.LocationUnavailable, archived)
        assertEquals(StockAdjustmentResult.LocationUnavailable, unknown)
        assertEquals(before, fixture.movementsOf(1L).size)
    }

    @Test
    fun theInventoryOfALocationShowsItsQuantityAndKeepsTheWholeValue() = runTest {
        seeded()
        adjust(
            StockAdjustmentRequest.Change(
                1L,
                StockAdjustmentReason.DAMAGED,
                QuantityMilli(2_000L),
                locationId = STALL,
            ),
        )

        val all = inventory().first()
        val atStall = inventory(STALL).first()

        assertEquals(QuantityMilli(8_000L), all.tracked.single().onHand)
        assertTrue(all.negative.isEmpty())
        assertEquals(QuantityMilli(-2_000L), atStall.tracked.single().onHand)
        assertEquals(listOf(1L), atStall.negative.map { it.id })
        // El valor es el de todo el stock: el costo no es por ubicación.
        assertEquals(Money(800L), atStall.value)
        assertEquals(all.value, atStall.value)
    }

    @Test
    fun theAdjustedSubjectShowsWhatIsInTheChosenLocation() = runTest {
        seeded()

        assertEquals(QuantityMilli(10_000L), subject(1L).first()?.here)
        assertEquals(QuantityMilli.ZERO, subject(1L, STALL).first()?.here)
        assertEquals(
            QuantityMilli(10_000L),
            subject(1L, CatalogFixture.MAIN_LOCATION_ID).first()?.here,
        )
    }

    private companion object {
        const val STALL = 30L
        const val CLOSED = 31L

        fun location(id: Long, archived: Boolean) = Location(
            id = id,
            systemKey = null,
            details = LocationDetails("Lugar $id", "lugar $id", LocationKind.STALL),
            archivedAt = if (archived) CatalogFixture.NOW else null,
            createdAt = CatalogFixture.NOW,
            updatedAt = CatalogFixture.NOW,
        )
    }
}
