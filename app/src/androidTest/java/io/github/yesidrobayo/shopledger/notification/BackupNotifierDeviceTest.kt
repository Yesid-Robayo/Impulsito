package io.github.yesidrobayo.shopledger.notification

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.ShopLedgerApp
import io.github.yesidrobayo.shopledger.data.BackupNotification
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * En el teléfono: la notificación de un día sin copia sale en el canal del respaldo, con sus textos
 * en el idioma de la interfaz, sin datos del negocio y visible entera con el teléfono bloqueado. Si
 * el sistema no deja salir avisos, como sin el permiso desde Android 13, responde que no salió, y
 * no aparece ninguna.
 *
 * Al terminar borra la notificación que lanzó. El canal del respaldo queda, como en la app.
 */
@RunWith(AndroidJUnit4::class)
class BackupNotifierDeviceTest {
    private val app =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
            as ShopLedgerApp
    private val notifier = app.container.backupNotifier

    @After
    fun dismiss() {
        manager().cancelAll()
    }

    @Test
    fun theBackupChannelHasItsFixedIdAndItsName() = runBlocking {
        NotificationChannels(app, NotificationChannels.APP_CHANNELS)
            .refresh(app.container.localizedContext.context())

        val channel = manager().getNotificationChannel("backup")
        assertNotNull(channel)
        assertEquals(
            app.container.localizedContext.context().getString(R.string.notif_channel_backup),
            channel.name,
        )
    }

    @Test
    fun aDayWithoutACopyGoesOutOnlyIfTheSystemLetsIt() = runBlocking {
        NotificationChannels(app, NotificationChannels.APP_CHANNELS)
            .refresh(app.container.localizedContext.context())
        val allowed = NotificationManagerCompat.from(app).areNotificationsEnabled()

        val shown = notifier.show(BackupNotification.Stale)

        assertEquals(allowed, shown)
        val posted = if (shown) awaitPosted(1).singleOrNull() else posted().singleOrNull()
        if (!shown) {
            assertNull(posted)
            return@runBlocking
        }
        val notification = requireNotNull(posted).notification
        val texts: Context = app.container.localizedContext.context()
        assertEquals("backup", notification.channelId)
        assertEquals(
            texts.getString(R.string.notif_backup_stale_title),
            notification.extras.getString(Notification.EXTRA_TITLE),
        )
        assertEquals(
            texts.getString(R.string.notif_backup_stale_body),
            notification.extras.getString(Notification.EXTRA_TEXT),
        )
        assertEquals(Notification.VISIBILITY_PUBLIC, notification.visibility)
        assertNotNull(notification.contentIntent)
    }

    @Test
    fun aPendingCheckNamesThatCloudAndGoesAlongsideADayWithoutACopy() = runBlocking {
        NotificationChannels(app, NotificationChannels.APP_CHANNELS)
            .refresh(app.container.localizedContext.context())
        if (!notifier.show(BackupNotification.CheckDue("Nube"))) return@runBlocking
        notifier.show(BackupNotification.Stale)

        val texts: Context = app.container.localizedContext.context()
        val titles =
            awaitPosted(2).map {
                it.notification.extras.getString(Notification.EXTRA_TITLE)
            }
        assertEquals(2, titles.size)
        assertEquals(
            true,
            texts.getString(R.string.notif_backup_check_title, "Nube") in titles,
        )
    }

    @Test
    fun aSecondOneReplacesTheFirstInsteadOfPilingUp() = runBlocking {
        NotificationChannels(app, NotificationChannels.APP_CHANNELS)
            .refresh(app.container.localizedContext.context())
        if (!notifier.show(BackupNotification.Stale)) return@runBlocking

        notifier.show(BackupNotification.Stale)

        // Solo cuentan los de la copia vieja: otro aviso del respaldo, como el recordatorio de
        // buscar versión, puede estar a la vista y no es lo que se prueba.
        val stale = awaitPosted(2).filter { it.id == STALE_NOTIFICATION_ID }
        assertEquals(1, stale.size)
    }

    private fun posted(): List<StatusBarNotification> = manager().activeNotifications.toList()

    /**
     * Las notificaciones a la vista, cuando lleguen a [count] o tras 3 segundos: el sistema las
     * publica aparte de la llamada, y una que no debía salir tampoco aparece en ese tiempo.
     */
    private fun awaitPosted(count: Int): List<StatusBarNotification> {
        val deadline = SystemClock.uptimeMillis() + WAIT_MILLIS
        while (true) {
            val active = posted()
            if (active.size >= count || SystemClock.uptimeMillis() > deadline) return active
            SystemClock.sleep(POLL_MILLIS)
        }
    }

    private companion object {
        const val WAIT_MILLIS = 3_000L
        const val POLL_MILLIS = 50L
    }

    private fun manager(): NotificationManager =
        app.getSystemService(NotificationManager::class.java)
}

/** El id de la notificación de copia vieja, el mismo que usa `AndroidBackupNotifier`. */
private const val STALE_NOTIFICATION_ID = 1
