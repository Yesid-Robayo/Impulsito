package io.github.yesidrobayo.shopledger.data.local.stock

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.local.catalog.CREATED_AT
import io.github.yesidrobayo.shopledger.data.local.catalog.CatalogDatabaseTest
import io.github.yesidrobayo.shopledger.data.local.catalog.Emissions
import io.github.yesidrobayo.shopledger.data.local.catalog.LATER
import io.github.yesidrobayo.shopledger.data.local.catalog.MUCH_LATER
import io.github.yesidrobayo.shopledger.data.local.catalog.UNIQUE
import io.github.yesidrobayo.shopledger.data.local.catalog.constraintFailure
import io.github.yesidrobayo.shopledger.data.local.catalog.guardFailure
import io.github.yesidrobayo.shopledger.data.local.catalog.product
import io.github.yesidrobayo.shopledger.data.local.functionsOf
import io.github.yesidrobayo.shopledger.data.local.ids
import io.github.yesidrobayo.shopledger.domain.model.Limit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Las alertas de stock bajo: cada columna en su lugar, activas y pendientes en el orden en que se
 * crearon, una resolución que no se reescribe, el estado del aviso que cambia solo, y alertas que
 * siguen a su producto. Todos los productos y cantidades son inventados.
 */
@RunWith(AndroidJUnit4::class)
internal class LowStockAlertDaoTest : CatalogDatabaseTest() {
    private val alerts get() = database.lowStockAlertDao()
    private var coffee = 0L
    private var tea = 0L

    @Before
    fun createProducts() = runBlocking {
        coffee = database.productDao().insert(product("Café molido"))
        tea = database.productDao().insert(product("Té verde"))
    }

    @Test
    fun everyColumnReadsBackFromItsOwnPlace() = runBlocking {
        // Saldo negativo en el cruce: una venta nunca se bloquea por falta de stock.
        val alert =
            lowStock(coffee).copy(onHandMilliAtTrigger = -1_500, minStockMilliAtTrigger = 2_000)
        val id = alerts.insert(alert)

        assertEquals(alert.copy(id = id), alerts.findById(id))
        assertNull(alerts.findById(MISSING_ID))
        val largest =
            lowStock(tea).copy(
                onHandMilliAtTrigger = Limit.ON_HAND_MILLI.max,
                minStockMilliAtTrigger = Limit.ON_HAND_MILLI.max,
            )
        val largestId = alerts.insert(largest)
        assertEquals(largest.copy(id = largestId), alerts.findById(largestId))
    }

    @Test
    fun activeAlertsFollowCreationOrderAndEmitAgain() = runBlocking {
        // El reloj del teléfono puede ir hacia atrás: el orden es el de creación.
        val first = alerts.insert(lowStock(tea, occurredAt = MUCH_LATER))
        val second = alerts.insert(lowStock(coffee, occurredAt = CREATED_AT))

        val emissions = Emissions(this, alerts.observeActive().ids { it.id })
        try {
            emissions.awaitValue(listOf(first, second))

            alerts.resolve(first, resolvedAt = LATER)
            emissions.awaitValue(listOf(second))

            // Rearmada, la siguiente caída crea otra alerta, que va al final.
            val again = alerts.insert(lowStock(tea, occurredAt = MUCH_LATER))
            emissions.awaitValue(listOf(second, again))
        } finally {
            emissions.stop()
        }
    }

    @Test
    fun findsOnlyTheActiveAlertOfEachRequestedProduct() = runBlocking {
        val other = database.productDao().insert(product("Azúcar"))
        val resolved = alerts.insert(lowStock(coffee))
        alerts.resolve(resolved, resolvedAt = LATER)
        val coffeeActive = alerts.insert(lowStock(coffee))
        val teaActive = alerts.insert(lowStock(tea))
        alerts.insert(lowStock(other))

        assertEquals(
            listOf(coffeeActive, teaActive),
            alerts.findActiveByProducts(listOf(tea, coffee)).map { it.id },
        )
        assertEquals(listOf(teaActive), alerts.findActiveByProducts(listOf(tea)).map { it.id })
        assertEquals(emptyList<Long>(), alerts.findActiveByProducts(emptyList()).map { it.id })
    }

    @Test
    fun resolvingHappensOnceAndKeepsItsInstant() = runBlocking {
        val id = alerts.insert(lowStock(coffee))
        val active = requireNotNull(alerts.findById(id))

        assertEquals(1, alerts.resolve(id, resolvedAt = LATER))
        assertEquals(active.copy(resolvedAt = LATER), alerts.findById(id))

        assertEquals(0, alerts.resolve(id, resolvedAt = MUCH_LATER))
        assertEquals(LATER, alerts.findById(id)?.resolvedAt)
        assertEquals(0, alerts.resolve(MISSING_ID, resolvedAt = LATER))
    }

    @Test
    fun pendingNotificationsAreTheOutboxAndTheirStatusChangesAlone() = runBlocking {
        val sugar = database.productDao().insert(product("Azúcar"))
        // Una alerta ya resuelta cuyo aviso no salió sigue en la bandeja de salida. Nace activa y
        // se resuelve después: una alerta no nace resuelta, y hay una sola activa por producto.
        val resolvedPending = alerts.insert(lowStock(coffee))
        alerts.resolve(resolvedPending, resolvedAt = LATER)
        val shown = alerts.insert(lowStock(coffee))
        val suppressed = alerts.insert(lowStock(tea).copy(notificationStatus = "SUPPRESSED"))
        val pending = alerts.insert(lowStock(sugar))

        assertEquals(
            listOf(resolvedPending, shown, pending),
            alerts.findPendingNotifications().map { it.id },
        )

        val before = requireNotNull(alerts.findById(shown))
        assertEquals(1, alerts.setNotificationStatus(shown, "SHOWN"))
        assertEquals(1, alerts.setNotificationStatus(pending, "BLOCKED"))
        assertEquals(before.copy(notificationStatus = "SHOWN"), alerts.findById(shown))
        assertEquals(listOf(resolvedPending), alerts.findPendingNotifications().map { it.id })
        assertEquals("SUPPRESSED", alerts.findById(suppressed)?.notificationStatus)
        assertEquals(0, alerts.setNotificationStatus(MISSING_ID, "SHOWN"))
    }

    @Test
    fun alertsFollowTheirProduct() = runBlocking {
        // Un producto que no existe lo rechaza la guarda, antes que la llave: la alerta pide un
        // producto con libro de stock.
        guardFailure("guard:low_stock_alerts") { alerts.insert(lowStock(MISSING_ID)) }
        val coffeeAlert = alerts.insert(lowStock(coffee))
        val teaAlert = alerts.insert(lowStock(tea))

        // Un producto sin historia se borra, y sus alertas con él.
        database.productDao().delete(coffee)

        assertNull(alerts.findById(coffeeAlert))
        assertEquals(teaAlert, alerts.findById(teaAlert)?.id)
    }

    @Test
    fun insertingWithAnExistingIdFailsAndKeepsTheOriginal() = runBlocking {
        val original = lowStock(coffee)
        val id = alerts.insert(original)

        // Un INSERT OR REPLACE borraría la alerta y escribiría otra en su lugar.
        constraintFailure(UNIQUE) {
            alerts.insert(lowStock(tea).copy(id = id, notificationStatus = "SHOWN"))
        }

        assertEquals(original.copy(id = id), alerts.findById(id))
    }

    @Test
    fun theDaoHasOnlyTheseFunctions() {
        // Una función nueva rompe esta prueba a propósito: quien la agregue revisa que no reescriba
        // el producto, el saldo o el mínimo del cruce, ni una resolución.
        assertEquals(
            setOf(
                "observeActive",
                "findById",
                "findActiveByProducts",
                "findPendingNotifications",
                "insert",
                "resolve",
                "setNotificationStatus",
            ),
            functionsOf(LowStockAlertDao::class.java),
        )
    }

    private companion object {
        const val MISSING_ID = 999L

        /** Una alerta activa con su aviso pendiente: dos unidades con un mínimo de tres. */
        fun lowStock(productId: Long, occurredAt: Long = CREATED_AT): LowStockAlertEntity =
            LowStockAlertEntity(
                productId = productId,
                onHandMilliAtTrigger = 2_000,
                minStockMilliAtTrigger = 3_000,
                notificationStatus = "PENDING",
                occurredAt = occurredAt,
                businessDate = "2026-01-01",
                resolvedAt = null,
            )
    }
}
