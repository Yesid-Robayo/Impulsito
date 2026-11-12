package io.github.yesidrobayo.shopledger.notification

import android.app.Notification
import android.app.NotificationManager
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.ShopLedgerApp
import io.github.yesidrobayo.shopledger.data.DataAccess
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.settings.AppSettings
import io.github.yesidrobayo.shopledger.domain.port.LowStockNotice
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * En el teléfono: el aviso de stock bajo sale en el canal de stock, con producto, stock y mínimo
 * solo en su versión privada. Con el teléfono bloqueado se ve la pública, que dice que un producto
 * está por agotarse, sin nombre ni cantidad. Con el modo discreto, tampoco desbloqueado. Varios a
 * la vez van en un grupo con su resumen.
 *
 * Si el sistema no deja salir avisos, como sin el permiso desde Android 13, responde que no salió,
 * sin fallar, y no aparece ninguno. Al terminar borra lo que lanzó.
 */
@RunWith(AndroidJUnit4::class)
class LowStockNotifierDeviceTest {
    private val app =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
            as ShopLedgerApp
    private val texts = app.container.localizedContext
    private var discreet = false
    private val notifier =
        AndroidLowStockNotifier(app, texts) { settings(discreet) }

    @Before
    fun channels() = runBlocking {
        NotificationChannels(app, NotificationChannels.APP_CHANNELS).refresh(texts.context())
    }

    @After
    fun dismiss() {
        manager().cancelAll()
    }

    @Test
    fun theStockChannelHasItsFixedIdAndItsName() {
        val channel = manager().getNotificationChannel("low_stock")
        assertNotNull(channel)
        assertEquals(texts.context().getString(R.string.notif_channel_stock), channel.name)
    }

    @Test
    fun aNoticeShowsTheProductOnlyInItsPrivateVersion() = runBlocking {
        val allowed = NotificationManagerCompat.from(app).areNotificationsEnabled()

        val shown = notifier.show(listOf(notice(7L, "Producto de prueba", 3_000L)))

        assertEquals(allowed, shown)
        if (!shown) {
            assertNull(awaitPosted(1).singleOrNull())
            return@runBlocking
        }
        val notification = awaitPosted(1).single().notification
        val context = texts.context()
        assertEquals("low_stock", notification.channelId)
        assertEquals(
            context.getString(R.string.notif_stock_low_title, "Producto de prueba"),
            notification.extras.getString(Notification.EXTRA_TITLE),
        )
        assertTrue(
            notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("3"),
        )
        assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
        val public = requireNotNull(notification.publicVersion)
        assertEquals(
            context.getString(R.string.notif_stock_public),
            public.extras.getString(Notification.EXTRA_TITLE),
        )
        assertNull(public.extras.getCharSequence(Notification.EXTRA_TEXT))
        assertNotNull(notification.contentIntent)
    }

    @Test
    fun anExhaustedProductSaysItRanOut() = runBlocking {
        if (!notifier.show(listOf(notice(8L, "Producto agotado", 0L)))) return@runBlocking

        val title =
            awaitPosted(1).single().notification.extras.getString(Notification.EXTRA_TITLE)

        assertEquals(
            texts.context().getString(R.string.notif_stock_out_title, "Producto agotado"),
            title,
        )
    }

    @Test
    fun inDiscreetModeNotEvenThePrivateVersionNamesIt() = runBlocking {
        discreet = true
        if (!notifier.show(listOf(notice(9L, "Producto oculto", 1_000L)))) return@runBlocking

        val notification = awaitPosted(1).single().notification
        val title = notification.extras.getString(Notification.EXTRA_TITLE)

        assertEquals(texts.context().getString(R.string.notif_stock_public), title)
        assertNull(notification.extras.getCharSequence(Notification.EXTRA_TEXT))
        assertNotNull(notification.contentIntent)
    }

    @Test
    fun severalAtOnceGoInAGroupWithASummary() = runBlocking {
        val notices = listOf(notice(10L, "Uno", 1_000L), notice(11L, "Dos", 0L))
        if (!notifier.show(notices)) return@runBlocking

        val posted = awaitPosted(3)

        assertEquals(3, posted.size)
        val summary =
            posted.single { (it.notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0 }
        assertEquals(
            texts.context().resources.getQuantityString(R.plurals.notif_stock_many_title, 2, 2),
            summary.notification.extras.getString(Notification.EXTRA_TITLE),
        )
        assertEquals(Notification.VISIBILITY_PUBLIC, summary.notification.visibility)
        assertTrue(posted.all { it.notification.group == "low_stock" })
    }

    @Test
    fun nothingToShowIsNotShown() = runBlocking {
        assertFalse(notifier.show(emptyList()))
        assertTrue(awaitPosted(1).isEmpty())
    }

    private fun notice(productId: Long, name: String, onHand: Long) = LowStockNotice(
        alertId = productId,
        productId = productId,
        productName = name,
        onHand = QuantityMilli(onHand),
        minStock = QuantityMilli(MIN_STOCK),
        unit = null,
    )

    /** Los ajustes de la base de la app, con el modo discreto que pide cada prueba. */
    private suspend fun settings(discreet: Boolean): AppSettings {
        val access = app.container.data.open() as DataAccess.Ready
        val current = access.ports.settings.get()
        return current.copy(preferences = current.preferences.copy(discreetModeEnabled = discreet))
    }

    private fun posted(): List<StatusBarNotification> = manager().activeNotifications.toList()

    /** Las notificaciones a la vista, cuando lleguen a [count] o tras 3 segundos. */
    private fun awaitPosted(count: Int): List<StatusBarNotification> {
        val deadline = SystemClock.uptimeMillis() + WAIT_MILLIS
        while (true) {
            val active = posted()
            if (active.size >= count || SystemClock.uptimeMillis() > deadline) return active
            SystemClock.sleep(POLL_MILLIS)
        }
    }

    private fun manager(): NotificationManager =
        app.getSystemService(NotificationManager::class.java)

    private companion object {
        const val WAIT_MILLIS = 3_000L
        const val POLL_MILLIS = 50L
        const val MIN_STOCK = 5_000L
    }
}
