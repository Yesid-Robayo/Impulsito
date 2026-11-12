package io.github.yesidrobayo.shopledger.domain.usecase.stock

import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.stock.LowStockNotificationStatus
import io.github.yesidrobayo.shopledger.domain.model.stock.StockAdjustmentReason
import io.github.yesidrobayo.shopledger.domain.model.stock.StockAdjustmentRequest
import io.github.yesidrobayo.shopledger.domain.port.LowStockNotice
import io.github.yesidrobayo.shopledger.domain.port.LowStockNotifier
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CatalogFixture
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lanzar los avisos pendientes: después de confirmar y al iniciar, una tanda con todo lo pendiente,
 * y lo que pasó con cada aviso queda guardado. Productos inventados.
 */
class DeliverLowStockAlertsUseCaseTest {
    private val fixture = StockFixture()
    private val shown = mutableListOf<List<LowStockNotice>>()
    private var systemAllows = true
    private val notifier = LowStockNotifier { notices ->
        if (systemAllows) shown += notices
        systemAllows
    }
    private val deliver = DeliverLowStockAlertsUseCase(
        fixture.catalog.alerts,
        fixture.catalog.products,
        fixture.catalog.units,
        fixture.catalog.settings,
        notifier,
    )

    private suspend fun cross(productId: Long) {
        fixture.seedStock(id = productId, units = 6_000L, unitCost = 100L, minStock = 5_000L)
        fixture.adjust(
            StockAdjustmentRequest.Change(
                productId,
                StockAdjustmentReason.DAMAGED,
                QuantityMilli(1_000L),
            ),
        )
    }

    private fun statusOf(productId: Long) = fixture.alertsOf(productId).single().notificationStatus

    @Test
    fun `pending alerts are shown together, once, with the stock and minimum of the cross`() =
        runTest {
            cross(1L)
            cross(2L)

            assertEquals(2, deliver())
            assertEquals(0, deliver())

            val notices = shown.single()
            assertEquals(listOf(1L, 2L), notices.map { it.productId })
            assertEquals(QuantityMilli(5_000L), notices.first().onHand)
            assertEquals(QuantityMilli(5_000L), notices.first().minStock)
            assertEquals("Producto inventado 1", notices.first().productName)
            assertEquals(CatalogFixture.UNIT_ID, notices.first().unit?.id)
            assertEquals(LowStockNotificationStatus.SHOWN, statusOf(1L))
            assertEquals(LowStockNotificationStatus.SHOWN, statusOf(2L))
        }

    @Test
    fun `an alert left pending by a dead process is shown on the next start`() = runTest {
        // La transacción confirmó y el proceso murió antes de avisar: la alerta quedó pendiente.
        cross(1L)
        assertEquals(LowStockNotificationStatus.PENDING, statusOf(1L))

        // El arranque siguiente crea su propio caso de uso y avisa.
        val onStart = DeliverLowStockAlertsUseCase(
            fixture.catalog.alerts,
            fixture.catalog.products,
            fixture.catalog.units,
            fixture.catalog.settings,
            notifier,
        )
        assertEquals(1, onStart())
        assertEquals(LowStockNotificationStatus.SHOWN, statusOf(1L))
    }

    @Test
    fun `a system that does not allow notices leaves them blocked, and they are not retried`() =
        runTest {
            cross(1L)
            systemAllows = false

            assertEquals(0, deliver())
            assertEquals(LowStockNotificationStatus.BLOCKED, statusOf(1L))

            systemAllows = true
            assertEquals(0, deliver())
            assertTrue(shown.isEmpty())
        }

    @Test
    fun `stock notices turned off in the settings stay quiet`() = runTest {
        cross(1L)
        val settings = fixture.catalog.settings.settings
        settings.value = settings.value.copy(
            preferences = settings.value.preferences.copy(lowStockNotificationsEnabled = false),
        )

        assertEquals(0, deliver())

        assertEquals(LowStockNotificationStatus.SUPPRESSED, statusOf(1L))
        assertTrue(shown.isEmpty())
    }

    @Test
    fun `an alert resolved before its notice is not shown`() = runTest {
        cross(1L)
        fixture.adjust(StockAdjustmentRequest.Count(1L, QuantityMilli(9_000L)))

        assertEquals(0, deliver())

        assertEquals(LowStockNotificationStatus.SUPPRESSED, statusOf(1L))
        assertTrue(shown.isEmpty())
    }

    @Test
    fun `nothing pending does nothing`() = runTest {
        assertEquals(0, deliver())
        assertTrue(shown.isEmpty())
    }
}
