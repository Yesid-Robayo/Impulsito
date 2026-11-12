package io.github.yesidrobayo.shopledger.domain.usecase.stock

import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.VoidReason
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleRequest
import io.github.yesidrobayo.shopledger.domain.model.stock.LowStockAlert
import io.github.yesidrobayo.shopledger.domain.model.stock.LowStockNotificationStatus
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.VoidPurchaseResult
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.atUnitCost
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.buy
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.open
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.sell
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleResult
import io.github.yesidrobayo.shopledger.domain.usecase.sale.SaleWorld
import io.github.yesidrobayo.shopledger.domain.usecase.sale.VoidSaleResult
import io.github.yesidrobayo.shopledger.domain.usecase.sale.method
import io.github.yesidrobayo.shopledger.domain.usecase.sale.product
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La alerta de stock bajo dentro de la venta, la compra y sus anulaciones: se crea en la misma
 * transacción al cruzar el mínimo, una sola vez por producto aunque tenga varias líneas, no se
 * repite hasta quedar por encima del mínimo, y se resuelve al volver a subir. Son las filas de los
 * casos borde de las alertas, con mínimo 5. Productos inventados.
 */
class StockAlertsInTransactionTest {
    private val world = SaleWorld().apply {
        withAlerts = true
        addMethod(method())
    }

    /** Un producto de mínimo [minUnits] con [units] unidades a 100 cada una. */
    private suspend fun stocked(id: Long, units: Long, minUnits: Long = 5L) {
        world.addProduct(product(id, salePrice = 500, minStockMilli = minUnits * 1_000))
        if (units > 0) world.open(id, units * 1_000, units * 100)
    }

    private fun alertsOf(productId: Long): List<LowStockAlert> =
        world.tables.alerts.values.filter { it.productId == productId }.sortedBy { it.id }

    private fun activeOf(productId: Long): LowStockAlert? = alertsOf(productId).singleOrNull {
        it.isActive
    }

    @Test
    fun `reaching the minimum creates one pending alert with the stock and the minimum`() =
        runTest {
            stocked(1, 6)

            world.sell(1, 1_000)

            val alert = alertsOf(1).single()
            assertEquals(LowStockNotificationStatus.PENDING, alert.notificationStatus)
            assertEquals(QuantityMilli(5_000L), alert.onHandAtTrigger)
            assertEquals(QuantityMilli(5_000L), alert.minStockAtTrigger)
            assertTrue(alert.isActive)
        }

    @Test
    fun `two lines of the same product count as one change`() = runTest {
        stocked(1, 20)
        val request = SaleRequest(
            listOf(
                SaleLineRequest(1, QuantityMilli(10_000L)),
                SaleLineRequest(1, QuantityMilli(13_000L)),
            ),
            SaleWorld.CASH,
        )

        assertTrue(world.registerSale()(request) is RegisterSaleResult.Registered)

        // Cruza de largo, de 20 a −3, y avisa una vez.
        val alert = alertsOf(1).single()
        assertEquals(QuantityMilli(-3_000L), alert.onHandAtTrigger)
    }

    @Test
    fun `a sale where three products cross leaves three alerts`() = runTest {
        stocked(1, 6)
        stocked(2, 6)
        stocked(3, 6)
        stocked(4, 9)
        val request = SaleRequest(
            (1L..4L).map { SaleLineRequest(it, QuantityMilli(1_000L)) },
            SaleWorld.CASH,
        )

        world.registerSale()(request)

        assertEquals(listOf(1L, 2L, 3L), world.tables.alerts.values.map { it.productId }.sorted())
    }

    @Test
    fun `an alert does not repeat, stays at the minimum and rearms above it`() = runTest {
        stocked(1, 6)
        world.sell(1, 2_000)
        val first = checkNotNull(activeOf(1))

        // Ya estaba bajo: de 4 a 3 no avisa otra vez.
        world.sell(1, 1_000)
        assertEquals(listOf(first.id), alertsOf(1).map { it.id })

        // Sube justo al mínimo: no rearma, no quedó por encima.
        world.buy(atUnitCost(1, 2_000, 100))
        assertEquals(first.id, activeOf(1)?.id)

        // Sube por encima: rearma.
        world.buy(atUnitCost(1, 1_000, 100))
        assertNull(activeOf(1))
        assertNotNull(alertsOf(1).single().resolvedAt)

        // Vuelve a caer al mínimo: avisa de nuevo.
        world.sell(1, 1_000)
        assertEquals(2, alertsOf(1).size)
        assertEquals(LowStockNotificationStatus.PENDING, activeOf(1)?.notificationStatus)
    }

    @Test
    fun `a minimum of zero warns when the stock runs out, and not below`() = runTest {
        stocked(1, 1, minUnits = 0L)

        world.sell(1, 1_000)
        assertEquals(QuantityMilli.ZERO, alertsOf(1).single().onHandAtTrigger)

        world.sell(1, 1_000)
        assertEquals(1, alertsOf(1).size)
    }

    @Test
    fun `fractions cross too`() = runTest {
        world.addProduct(
            product(
                1,
                salePrice = 500,
                unitId = SaleWorld.KILOGRAM,
                minStockMilli = 5_000,
            ),
        )
        world.open(1, 5_100, 510)

        world.sell(1, 200)

        assertEquals(QuantityMilli(4_900L), alertsOf(1).single().onHandAtTrigger)
    }

    @Test
    fun `voiding a sale rearms, and voiding a purchase warns`() = runTest {
        stocked(1, 8)
        val saleId = world.sell(1, 5_000)
        assertNotNull(activeOf(1))

        assertEquals(
            VoidSaleResult.Voided,
            world.voidSale()(saleId, VoidReason.ENTRY_ERROR, null),
        )
        assertNull(activeOf(1))

        val purchaseId = world.buy(atUnitCost(1, 5_000, 100))
        world.sell(1, 5_000)
        assertNull(activeOf(1))
        assertEquals(
            VoidPurchaseResult.Voided,
            world.voidPurchase()(purchaseId, VoidReason.ENTRY_ERROR, null),
        )
        val renewed = checkNotNull(activeOf(1))
        assertEquals(QuantityMilli(3_000L), renewed.onHandAtTrigger)
        assertEquals(2, alertsOf(1).size)
    }

    @Test
    fun `a purchase that leaves the stock at the minimum does not warn`() = runTest {
        stocked(1, 2)

        world.buy(atUnitCost(1, 3_000, 100))

        assertTrue(alertsOf(1).isEmpty())
    }

    @Test
    fun `without the alerts repository nothing is evaluated`() = runTest {
        world.withAlerts = false
        stocked(1, 6)

        world.sell(1, 1_000)

        assertTrue(world.tables.alerts.isEmpty())
    }
}
