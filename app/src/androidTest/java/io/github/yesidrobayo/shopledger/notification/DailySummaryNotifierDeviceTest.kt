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
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.report.DailySummary
import io.github.yesidrobayo.shopledger.domain.model.settings.RegionalSettings
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * En el teléfono: el resumen del día sale en su canal, sin sonido, con montos solo en su versión
 * privada. Con el teléfono bloqueado se ve la pública, que dice que el resumen está listo, sin
 * cifras. Con el modo discreto, la privada solo dice cuántas ventas hubo. Sin permiso de avisos,
 * responde que no salió, sin fallar. Montos inventados; al terminar borra lo que lanzó.
 */
@RunWith(AndroidJUnit4::class)
class DailySummaryNotifierDeviceTest {
    private val app =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
            as ShopLedgerApp
    private val texts = app.container.localizedContext
    private val notifier = AndroidDailySummaryNotifier(app, texts)

    @Before
    fun channels() = runBlocking {
        NotificationChannels(app, NotificationChannels.APP_CHANNELS).refresh(texts.context())
    }

    @After
    fun dismiss() {
        manager().cancelAll()
    }

    @Test
    fun theChannelHasItsFixedIdItsNameAndNoSound() {
        val channel = manager().getNotificationChannel("daily_summary")

        assertNotNull(channel)
        assertEquals(
            texts.context().getString(R.string.notif_channel_daily_summary),
            channel.name,
        )
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
    }

    @Test
    fun theAmountsGoOnlyInThePrivateVersion() = runBlocking {
        val allowed = NotificationManagerCompat.from(app).areNotificationsEnabled()

        val shown = notifier.show(summary(discreet = false), regional())

        assertEquals(allowed, shown)
        if (!shown) {
            assertNull(awaitPosted().singleOrNull())
            return@runBlocking
        }
        val notification = awaitPosted().single().notification
        val context = texts.context()
        assertEquals("daily_summary", notification.channelId)
        assertEquals(
            context.getString(R.string.notif_daily_title),
            notification.extras.getString(Notification.EXTRA_TITLE),
        )
        val body = notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        assertFalse(body == context.getString(R.string.notif_daily_body_discreet, "12"))
        assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
        val public = requireNotNull(notification.publicVersion)
        assertEquals(
            context.getString(R.string.notif_daily_public),
            public.extras.getString(Notification.EXTRA_TITLE),
        )
        assertNull(public.extras.getCharSequence(Notification.EXTRA_TEXT))
        assertNotNull(notification.contentIntent)
    }

    @Test
    fun inDiscreetModeNotEvenThePrivateVersionHasAmounts() = runBlocking {
        if (!notifier.show(summary(discreet = true), regional())) return@runBlocking

        val notification = awaitPosted().single().notification

        assertEquals(
            texts.context().getString(R.string.notif_daily_body_discreet, "12"),
            notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString(),
        )
    }

    private fun summary(discreet: Boolean) = DailySummary(
        date = BusinessDate(LocalDate(2026, 11, 2)),
        salesCount = 12,
        netSales = Money(340_000),
        grossProfit = Money(140_000),
        discreet = discreet,
    )

    /** La región de la base de la app. */
    private suspend fun regional(): RegionalSettings {
        val access = app.container.data.open() as DataAccess.Ready
        return access.ports.settings.get().regional
    }

    /** Las notificaciones a la vista, cuando llegue una o tras 3 segundos. */
    private fun awaitPosted(): List<StatusBarNotification> {
        val deadline = SystemClock.uptimeMillis() + WAIT_MILLIS
        while (true) {
            val active = manager().activeNotifications.toList()
            if (active.isNotEmpty() || SystemClock.uptimeMillis() > deadline) return active
            SystemClock.sleep(POLL_MILLIS)
        }
    }

    private fun manager(): NotificationManager =
        app.getSystemService(NotificationManager::class.java)

    private companion object {
        const val WAIT_MILLIS = 3_000L
        const val POLL_MILLIS = 50L
    }
}
