package io.github.yesidrobayo.shopledger.localization

import android.app.LocaleManager
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.ShopLedgerApp
import io.github.yesidrobayo.shopledger.data.BackupNotification
import io.github.yesidrobayo.shopledger.notification.NotificationChannels
import io.github.yesidrobayo.shopledger.shortcut.AppShortcuts
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * En el teléfono, con las superficies reales de la app: al elegir un idioma distinto del
 * teléfono, los canales de avisos, los atajos, el widget, el mosaico y un aviso real del
 * respaldo salen en el idioma elegido, nunca en el del teléfono. Repite con lo que hoy existe la
 * prueba que se hizo antes con un canal de prueba.
 *
 * Lo esperado se lee con un contexto propio en el idioma elegido, no con el proveedor que se
 * prueba. Al terminar deja como estaban el idioma de la app, el de Android para la app y el
 * archivo de idioma, borra los avisos y vuelve a escribir las superficies en ese idioma.
 */
@RunWith(AndroidJUnit4::class)
class RealSurfacesLanguageDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as ShopLedgerApp
    private val container = app.container
    private val languageFile = File(app.noBackupFilesDir, LanguagePreferenceFile.FILE_NAME)
    private var languageBytes: ByteArray? = null
    private var languageBefore: UiLanguage = UiLanguage.System
    private var resourcesBefore = ""
    private var systemBefore: LocaleList? = null

    @Before
    fun rememberLanguage() {
        languageBytes = if (languageFile.isFile) languageFile.readBytes() else null
        languageBefore = container.language.language.value
        resourcesBefore = app.getString(R.string.resources_language)
        if (systemManagesLanguage) systemBefore = localeManager().applicationLocales
    }

    @After
    fun restoreLanguage() {
        notificationManager().cancelAll()
        if (systemManagesLanguage) {
            val before = systemBefore ?: LocaleList.getEmptyLocaleList()
            if (localeManager().applicationLocales != before) {
                localeManager().applicationLocales = before
                awaitResourcesLanguage(resourcesBefore)
                runBlocking { container.language.syncWithSystem() }
            }
        } else {
            runBlocking { container.language.choose(languageBefore) }
        }
        val bytes = languageBytes
        if (bytes == null) languageFile.delete() else languageFile.writeBytes(bytes)
        // Volver al idioma ya las reescribe; esta llamada solo confirma que quedaron al día.
        runBlocking { container.localizedSurfaces.refreshLocalizedSurfaces() }
    }

    @Test
    fun everyAppChannelIsNamedInTheChosenLanguage() {
        val other = chooseOtherAndRefresh()
        val texts = textsIn(other)

        NotificationChannels.APP_CHANNELS.forEach { spec ->
            assertEquals(
                spec.id,
                texts.getString(spec.name),
                notificationManager().getNotificationChannel(spec.id)?.name?.toString(),
            )
        }
    }

    @Test
    fun theShortcutsArePublishedInTheChosenLanguage() {
        val other = chooseOtherAndRefresh()
        val texts = textsIn(other)

        val published = ShortcutManagerCompat.getDynamicShortcuts(app).sortedBy { it.rank }

        assertEquals(
            listOf(
                AppShortcuts.ID_SALE,
                AppShortcuts.ID_PHRASE,
                AppShortcuts.ID_EXPENSE,
                AppShortcuts.ID_PURCHASE,
            ),
            published.map { it.id },
        )
        assertEquals(
            listOf(
                R.string.shortcut_sale_short,
                R.string.shortcut_phrase_short,
                R.string.shortcut_expense_short,
                R.string.shortcut_purchase_short,
            ).map(texts::getString),
            published.map { it.shortLabel.toString() },
        )
        assertEquals(
            listOf(
                R.string.shortcut_sale_long,
                R.string.shortcut_phrase_long,
                R.string.shortcut_expense_long,
                R.string.shortcut_purchase_long,
            ).map(texts::getString),
            published.map { it.longLabel.toString() },
        )
    }

    @Test
    fun theWidgetAndTheTileReadTheirTextsInTheChosenLanguage() {
        val other = chooseOtherAndRefresh()
        val texts = textsIn(other)
        // El widget y el mosaico leen sus textos de este contexto al dibujarse.
        val surfaces = container.localizedContext.context()

        listOf(R.string.tile_new_sale, R.string.widget_today, R.string.nav_sell).forEach { id ->
            assertEquals(texts.getString(id), surfaces.getString(id))
        }
        assertEquals(other, surfaces.getString(R.string.resources_language))
    }

    @Test
    fun aRealBackupNoticeGoesOutInTheChosenLanguage() {
        val other = chooseOtherAndRefresh()
        val texts = textsIn(other)

        // Sin permiso de avisos, desde Android 13, no sale ninguno: no hay nada que leer.
        if (!container.backupNotifier.show(BackupNotification.Stale)) return

        val notification = awaitPosted().notification
        assertEquals(BACKUP_CHANNEL, notification.channelId)
        assertEquals(
            texts.getString(R.string.notif_backup_stale_title),
            notification.extras.getString(Notification.EXTRA_TITLE),
        )
        assertEquals(
            texts.getString(R.string.notif_backup_stale_body),
            notification.extras.getString(Notification.EXTRA_TEXT),
        )
    }

    /**
     * Elige un idioma con carpeta propia distinto del que responde ahora y vuelve a escribir las
     * superficies reales. Hasta Android 12L confirma que el contexto de la app sigue en el idioma
     * del teléfono: sin el proveedor, todo habría salido en ese idioma.
     */
    private fun chooseOtherAndRefresh(): String {
        val other = if (resourcesBefore == "es") "pt" else "es"
        val language = requireNotNull(UiLanguage.of(other))
        assertEquals(LanguageChoice.Applied, runBlocking { container.language.choose(language) })
        if (systemManagesLanguage) {
            awaitResourcesLanguage(other)
        } else {
            assertNotEquals(other, app.getString(R.string.resources_language))
        }
        // La elección ya las reescribe, o lo hace el cambio de configuración desde Android 13: al
        // volver, esta llamada las dejó escritas en el idioma nuevo o confirma que ya lo estaban.
        assertNotEquals(
            SurfacesRefresh.FAILED,
            runBlocking { container.localizedSurfaces.refreshLocalizedSurfaces() },
        )
        return other
    }

    private fun textsIn(tag: String): Context {
        val configuration = Configuration(app.resources.configuration)
        configuration.setLocales(LocaleList.forLanguageTags(tag))
        return app.createConfigurationContext(configuration)
    }

    private fun awaitResourcesLanguage(tag: String) {
        val deadline = SystemClock.elapsedRealtime() + WAIT_MILLIS
        while (app.getString(R.string.resources_language) != tag) {
            if (SystemClock.elapsedRealtime() > deadline) {
                throw AssertionError("Android no aplicó el idioma a tiempo")
            }
            Thread.sleep(POLL_MILLIS)
        }
    }

    /** El aviso del respaldo, cuando el sistema lo publique: llega aparte de la llamada. */
    private fun awaitPosted(): StatusBarNotification {
        val deadline = SystemClock.elapsedRealtime() + WAIT_MILLIS
        while (true) {
            notificationManager()
                .activeNotifications
                .firstOrNull { it.notification.channelId == BACKUP_CHANNEL }
                ?.let { return it }
            if (SystemClock.elapsedRealtime() > deadline) {
                throw AssertionError("El aviso del respaldo no apareció")
            }
            Thread.sleep(POLL_MILLIS)
        }
    }

    private fun notificationManager(): NotificationManager =
        app.getSystemService(NotificationManager::class.java)

    private fun localeManager(): LocaleManager = app.getSystemService(LocaleManager::class.java)

    private val systemManagesLanguage: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    private companion object {
        const val BACKUP_CHANNEL = NotificationChannels.BACKUP_CHANNEL_ID
        const val WAIT_MILLIS = 10_000L
        const val POLL_MILLIS = 50L
    }
}
