package io.github.yesidrobayo.shopledger

import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.data.DataAccess
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.settings.ColorPalette
import io.github.yesidrobayo.shopledger.domain.model.settings.PreferenceChange
import io.github.yesidrobayo.shopledger.domain.port.LowStockNotice
import io.github.yesidrobayo.shopledger.notification.AndroidLowStockNotifier
import io.github.yesidrobayo.shopledger.notification.NotificationChannels
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Prepara las pruebas a mano de la pantalla de inicio y del ícono de notificación.
 *
 * La pantalla de inicio: guarda la paleta del argumento `splashPalette`
 * y abre la app hasta que la lee, que es cuando le pasa al sistema el tema de arranque. El arranque
 * en frío siguiente, que se mira desde fuera, debe salir con esa paleta.
 *
 * No deja nada que limpiar aparte de la paleta guardada: la prueba a mano la devuelve con otra
 * corrida, con la predeterminada. Sin el argumento no corre.
 */
@RunWith(AndroidJUnit4::class)
class SplashPaletteProbe {
    private val app =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
            as ShopLedgerApp

    @Test
    fun storesThePaletteAndLetsTheAppPassItsStartingTheme() {
        val name = InstrumentationRegistry.getArguments().getString("splashPalette")
        assumeTrue("Sin splashPalette no hay nada que preparar", name != null)
        val palette = ColorPalette.valueOf(requireNotNull(name))
        runBlocking {
            val access = app.container.data.open()
            check(access is DataAccess.Ready) { "La base no abrió" }
            access.ports.settings.updatePreference(
                PreferenceChange.Palette(palette),
                app.container.clock.now(),
            )
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val deadline = SystemClock.elapsedRealtime() + WAIT_MILLIS
            var shown: ColorPalette? = null
            while (SystemClock.elapsedRealtime() < deadline && shown != palette) {
                scenario.onActivity { shown = it.appearance.value?.palette }
                if (shown != palette) SystemClock.sleep(POLL_MILLIS)
            }
            assertEquals(palette, shown)
        }
    }

    /**
     * Con el argumento `notificationIcon`, deja a la vista un aviso de stock de un producto inventado,
     * para mirar a mano el ícono de notificación en la barra y en el panel. Se borra con la otra
     * corrida o al desinstalar.
     */
    @Test
    fun leavesAStockNoticeToLookAtItsIcon() {
        assumeTrue(
            "Sin notificationIcon no hay aviso que dejar",
            InstrumentationRegistry.getArguments().getString("notificationIcon") != null,
        )
        val texts = app.container.localizedContext
        runBlocking {
            NotificationChannels(app, NotificationChannels.APP_CHANNELS).refresh(texts.context())
            val notifier =
                AndroidLowStockNotifier(app, texts) {
                    val access = app.container.data.open()
                    check(access is DataAccess.Ready) { "La base no abrió" }
                    access.ports.settings.get()
                }
            val notice =
                LowStockNotice(
                    alertId = 1L,
                    productId = 1L,
                    productName = "Producto de prueba",
                    onHand = QuantityMilli(2_000L),
                    minStock = QuantityMilli(5_000L),
                    unit = null,
                )
            check(notifier.show(listOf(notice))) { "El sistema no dejó salir el aviso" }
        }
    }

    private companion object {
        const val WAIT_MILLIS = 10_000L
        const val POLL_MILLIS = 100L
    }
}
