package io.github.yesidrobayo.shopledger.domain.usecase.sale

import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleRequest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Vender desde una ubicación: la venta la guarda y el stock sale de ella. Sin elegir, sale de la
 * inicial. Nombres y montos inventados.
 */
class RegisterSaleLocationTest {
    private val stall = testLocation(STALL, archived = false)
    private val closed = testLocation(CLOSED, archived = true)

    private val world = SaleWorld().apply {
        addMethod(method())
        addProduct(product(1, salePrice = 2_000))
        locationRepository = TestLocations(listOf(stall, closed))
    }

    private suspend fun sell(locationId: Long?) = world.registerSale()(
        SaleRequest(
            listOf(SaleLineRequest(1, QuantityMilli(3_000))),
            SaleWorld.CASH,
            locationId = locationId,
        ),
    )

    @Test
    fun withoutChoosingTheSaleAndItsStockGoToTheInitialLocation() = runTest {
        assertTrue(sell(null) is RegisterSaleResult.Registered)

        assertEquals(SaleWorld.MAIN_LOCATION, world.tables.sales.values.single().content.locationId)
        assertEquals(
            listOf(SaleWorld.MAIN_LOCATION),
            world.tables.movements.values.map { it.movement.key.locationId },
        )
    }

    @Test
    fun anActiveLocationKeepsTheSaleAndTheStockLeavesFromIt() = runTest {
        assertTrue(sell(STALL) is RegisterSaleResult.Registered)

        assertEquals(STALL, world.tables.sales.values.single().content.locationId)
        val exit = world.tables.movements.values.single()
        assertEquals(STALL, exit.movement.key.locationId)
        assertEquals(QuantityMilli(-3_000), exit.movement.quantity)
    }

    @Test
    fun choosingTheInitialLocationByItsIdIsTheSameAsNotChoosing() = runTest {
        world.locationRepository = null

        assertTrue(sell(SaleWorld.MAIN_LOCATION) is RegisterSaleResult.Registered)
        assertEquals(SaleWorld.MAIN_LOCATION, world.tables.sales.values.single().content.locationId)
    }

    @Test
    fun anArchivedMissingOrUnreadableLocationRejectsTheSaleAndWritesNothing() = runTest {
        assertEquals(RegisterSaleResult.LocationUnavailable, sell(CLOSED))
        assertEquals(RegisterSaleResult.LocationUnavailable, sell(99L))
        world.locationRepository = null
        assertEquals(RegisterSaleResult.LocationUnavailable, sell(STALL))

        assertTrue(world.tables.sales.isEmpty())
        assertTrue(world.tables.movements.isEmpty())
    }

    private companion object {
        const val STALL = 2L
        const val CLOSED = 3L
    }
}
