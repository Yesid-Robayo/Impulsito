package io.github.yesidrobayo.shopledger.domain.usecase.purchase

import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseRequest
import io.github.yesidrobayo.shopledger.domain.usecase.sale.SaleWorld
import io.github.yesidrobayo.shopledger.domain.usecase.sale.TestLocations
import io.github.yesidrobayo.shopledger.domain.usecase.sale.product
import io.github.yesidrobayo.shopledger.domain.usecase.sale.testLocation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Comprar para una ubicación: la compra la guarda y el stock entra en ella. Sin elegir, entra en
 * la inicial. El costo promedio es uno solo, sin importar dónde entra. Montos inventados.
 */
class PurchaseLocationTest {
    private val world = SaleWorld().apply {
        addProduct(product(1))
        val open = testLocation(WAREHOUSE, archived = false)
        locationRepository = TestLocations(listOf(open, testLocation(CLOSED, archived = true)))
    }

    private suspend fun buy(locationId: Long?) = world.registerPurchase()(
        PurchaseRequest(listOf(atUnitCost(1, 4_000, cost = 1_000)), locationId = locationId),
    )

    @Test
    fun withoutChoosingThePurchaseEntersTheInitialLocation() = runTest {
        val result = buy(null) as RegisterPurchaseResult.Registered

        val purchase = world.tables.purchases.getValue(result.purchaseId).content
        assertEquals(SaleWorld.MAIN_LOCATION, purchase.locationId)
        assertEquals(SaleWorld.MAIN_LOCATION, world.movements().last().movement.key.locationId)
    }

    @Test
    fun aChosenWarehouseKeepsThePurchaseAndReceivesTheStock() = runTest {
        val result = buy(WAREHOUSE) as RegisterPurchaseResult.Registered

        val purchase = world.tables.purchases.getValue(result.purchaseId).content
        assertEquals(WAREHOUSE, purchase.locationId)
        val row = world.movements().last()
        assertEquals(WAREHOUSE, row.movement.key.locationId)
        assertEquals(QuantityMilli(4_000), row.movement.quantity)
        assertEquals(cost(4_000, 1_000, 4_000), world.costOf(1))
    }

    @Test
    fun anArchivedOrUnknownLocationRejectsThePurchaseAndWritesNothing() = runTest {
        assertEquals(RegisterPurchaseResult.LocationUnavailable, buy(CLOSED))
        assertEquals(RegisterPurchaseResult.LocationUnavailable, buy(55L))
        world.locationRepository = null
        assertEquals(RegisterPurchaseResult.LocationUnavailable, buy(WAREHOUSE))
        assertTrue(buy(SaleWorld.MAIN_LOCATION) is RegisterPurchaseResult.Registered)

        assertEquals(1, world.tables.purchases.size)
    }

    private companion object {
        const val WAREHOUSE = 4L
        const val CLOSED = 5L
    }
}
