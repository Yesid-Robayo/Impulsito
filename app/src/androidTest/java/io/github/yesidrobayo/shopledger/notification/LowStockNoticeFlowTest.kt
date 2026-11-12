package io.github.yesidrobayo.shopledger.notification

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationManagerCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.MainActivity
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.ShopLedgerApp
import io.github.yesidrobayo.shopledger.data.DataAccess
import io.github.yesidrobayo.shopledger.data.DataPorts
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductForm
import io.github.yesidrobayo.shopledger.domain.model.stock.LowStockAlert
import io.github.yesidrobayo.shopledger.domain.model.stock.LowStockNotificationStatus
import io.github.yesidrobayo.shopledger.domain.model.stock.StockAdjustmentRequest
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CreateProductUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveProductResult
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.domain.usecase.region.CurrencyLock
import io.github.yesidrobayo.shopledger.domain.usecase.stock.AdjustStockUseCase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * El aviso de stock bajo de punta a punta, con la app entera y su base: con la app abierta, un
 * producto inventado baja de su mínimo y su alerta sale como aviso una sola vez. Tocarlo abre el
 * detalle del producto. Si el sistema no deja avisar, la alerta queda bloqueada y no sale nada.
 *
 * Escribe un producto y un ajuste de verdad en la base de la app y da por terminado el primer uso,
 * así que solo corre si se pide con el argumento `lowStockFlow=true` del runner. Con
 * `keepNotice=true` deja el aviso a la vista, para mirarlo después con el teléfono bloqueado.
 */
@RunWith(AndroidJUnit4::class)
class LowStockNoticeFlowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as ShopLedgerApp
    private val arguments = InstrumentationRegistry.getArguments()

    @Test
    fun crossingTheMinimumNotifiesOnceAndTheNoticeOpensTheProduct(): Unit = runBlocking {
        assumeTrue(arguments.getString(ARGUMENT) == "true")
        val ports = (app.container.data.open() as DataAccess.Ready).ports
        val clock = app.container.clock
        if (ports.settings.get().onboardingCompletedAt == null) {
            ports.settings.markOnboardingCompleted(clock.now())
        }
        val name = "Producto de aviso ${System.nanoTime() % NAME_SUFFIX}"
        val productId = create(ports, name)
        val allowed = NotificationManagerCompat.from(app).areNotificationsEnabled()

        // Se abre como desde el lanzador: así el aviso llega a la misma actividad, como en el uso.
        app.startActivity(
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setClass(app, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        run {
            adjust(ports, productId, counted = 3L)

            val alert = awaitDelivered(ports, productId)
            if (!allowed) {
                assertEquals(LowStockNotificationStatus.BLOCKED, alert.notificationStatus)
                assertNull(posted(productId))
                return@run
            }
            assertEquals(LowStockNotificationStatus.SHOWN, alert.notificationStatus)
            val notice = requireNotNull(awaitPosted(productId)).notification
            val texts = app.container.localizedContext.context()
            assertEquals(
                texts.getString(R.string.notif_stock_low_title, name),
                notice.extras.getString(Notification.EXTRA_TITLE),
            )
            assertEquals(Notification.VISIBILITY_PRIVATE, notice.visibility)
            assertEquals(
                texts.getString(R.string.notif_stock_public),
                notice.publicVersion?.extras?.getString(Notification.EXTRA_TITLE),
            )

            // Bajar más no vuelve a avisar: ya hay una alerta activa.
            adjust(ports, productId, counted = 2L)
            SystemClock.sleep(SETTLE_MILLIS)
            assertEquals(1, ports.lowStockAlerts.findActiveByProducts(listOf(productId)).size)

            requireNotNull(notice.contentIntent).send()
            // El historial de movimientos solo está en el detalle: Inicio también nombra lo que
            // se está acabando.
            val history = texts.getString(R.string.stock_movement_opening_balance)
            val missing = awaitOnScreen(name, history)
            assertTrue("El aviso no abrió el producto. En pantalla: $missing", missing == null)
            if (arguments.getString(KEEP_ARGUMENT) != "true") manager().cancelAll()
        }
        instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
    }

    private suspend fun create(ports: DataPorts, name: String): Long {
        val create =
            CreateProductUseCase(
                ports.transactions, ports.products, ports.categories, ports.suppliers, ports.units,
                ports.systemRows, ports.priceHistory, ports.stockLedger, ports.lowStockAlerts,
                BusinessCalendarSource(ports.settings), CurrencyLock(ports.settings),
                app.container.clock,
            )
        val form = ProductForm(name = name, minStock = QuantityMilli.ofUnits(MIN_STOCK))
        val saved = create(form, QuantityMilli.ofUnits(START_STOCK))
        return (saved as SaveProductResult.Saved).productId
    }

    private suspend fun adjust(ports: DataPorts, productId: Long, counted: Long) {
        AdjustStockUseCase(
            ports.transactions, ports.products, ports.units, ports.systemRows, ports.stockLedger,
            ports.lowStockAlerts, BusinessCalendarSource(ports.settings),
            CurrencyLock(ports.settings), app.container.clock,
        )(StockAdjustmentRequest.Count(productId, QuantityMilli.ofUnits(counted)))
    }

    /** La alerta activa del producto cuando deja de esperar aviso, o falla tras unos segundos. */
    private suspend fun awaitDelivered(ports: DataPorts, productId: Long): LowStockAlert {
        val deadline = SystemClock.uptimeMillis() + WAIT_MILLIS
        while (true) {
            val alert = ports.lowStockAlerts.findActiveByProducts(listOf(productId)).singleOrNull()
            if (alert != null && alert.notificationStatus != LowStockNotificationStatus.PENDING) {
                return alert
            }
            assertTrue("La alerta no se entregó", SystemClock.uptimeMillis() < deadline)
            SystemClock.sleep(POLL_MILLIS)
        }
    }

    private fun posted(productId: Long): StatusBarNotification? =
        manager().activeNotifications.firstOrNull {
            it.tag == "low_stock" && it.id == productId.toInt()
        }

    private fun awaitPosted(productId: Long): StatusBarNotification? {
        val deadline = SystemClock.uptimeMillis() + WAIT_MILLIS
        while (SystemClock.uptimeMillis() < deadline) {
            posted(productId)?.let { return it }
            SystemClock.sleep(POLL_MILLIS)
        }
        return null
    }

    /**
     * Espera unos segundos a que la ventana activa muestre todos los [texts]. Devuelve `null` si
     * aparecieron, o los textos que sí se veían, para explicar la falla.
     */
    private fun awaitOnScreen(vararg texts: String): String? {
        val deadline = SystemClock.uptimeMillis() + WAIT_MILLIS
        var seen = ""
        while (SystemClock.uptimeMillis() < deadline) {
            // Compose no responde a la búsqueda por texto de accesibilidad: se recorre el árbol.
            val root: AccessibilityNodeInfo? = instrumentation.uiAutomation.rootInActiveWindow
            seen = root?.let(::textsOf).orEmpty()
            val shown = seen.split(" | ")
            if (texts.all { it in shown }) return null
            SystemClock.sleep(POLL_MILLIS)
        }
        return seen
    }

    private fun textsOf(node: AccessibilityNodeInfo): String = buildList {
        node.text?.let { add(it.toString()) }
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { add(textsOf(it)) }
        }
    }.filter { it.isNotBlank() }.joinToString(" | ")

    private fun manager(): NotificationManager =
        app.getSystemService(NotificationManager::class.java)

    private companion object {
        const val ARGUMENT = "lowStockFlow"
        const val KEEP_ARGUMENT = "keepNotice"
        const val START_STOCK = 8L
        const val MIN_STOCK = 5L
        const val NAME_SUFFIX = 10_000L
        const val WAIT_MILLIS = 10_000L
        const val POLL_MILLIS = 100L
        const val SETTLE_MILLIS = 1_000L
    }
}
