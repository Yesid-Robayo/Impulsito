package io.github.yesidrobayo.shopledger

import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.data.DataAccess
import io.github.yesidrobayo.shopledger.data.DataPorts
import io.github.yesidrobayo.shopledger.di.AppContainer
import io.github.yesidrobayo.shopledger.domain.model.settings.AppPreferences
import io.github.yesidrobayo.shopledger.domain.model.settings.ColorPalette
import io.github.yesidrobayo.shopledger.domain.model.settings.PreferenceChange
import io.github.yesidrobayo.shopledger.domain.model.settings.ThemeMode
import io.github.yesidrobayo.shopledger.ui.theme.Appearance
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * La app arma sus dependencias a mano: el proceso crea un solo contenedor, y la actividad recibe
 * por él un repositorio de la base real de la app de pruebas.
 *
 * Usa la base de la app instalada para las pruebas, y deja la paleta y el modo como los encontró.
 * Abre la actividad sin herramientas de interfaz: mira el estado que la actividad leyó.
 */
@RunWith(AndroidJUnit4::class)
class AppContainerTest {
    private val app =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
            as ShopLedgerApp

    private var original: AppPreferences? = null

    @After
    fun restorePreferences() {
        runBlocking {
            original?.let { writeAppearance(ports(), Appearance(it.colorPalette, it.themeMode)) }
        }
    }

    @Test
    fun theProcessHasASingleContainer() {
        val container: AppContainer = app.container

        assertSame(container, app.container)
        assertSame(container.data.log, container.log)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertSame(container, (activity.application as ShopLedgerApp).container)
            }
        }
    }

    @Test
    fun theDataOpensOnceForTheWholeProcess() = runBlocking {
        val first = app.container.data.open()
        val second = app.container.data.open()

        assertTrue(first is DataAccess.Ready)
        assertSame(first, second)
    }

    @Test
    fun theActivityReadsTheAppearanceThroughTheContainer() {
        val ports = runBlocking { ports() }
        original = runBlocking { ports.settings.get().preferences }

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            runBlocking { writeAppearance(ports, CHOSEN) }

            assertEquals(CHOSEN, awaitAppearance(scenario))
        }
    }

    /** Espera a que la actividad aplique [CHOSEN], que llega desde la base. */
    private fun awaitAppearance(scenario: ActivityScenario<MainActivity>): Appearance? {
        val deadline = SystemClock.elapsedRealtime() + WAIT_MILLIS
        var shown: Appearance? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            scenario.onActivity { shown = it.appearance.value }
            if (shown == CHOSEN) return shown
            Thread.sleep(POLL_MILLIS)
        }
        return shown
    }

    private suspend fun ports(): DataPorts = (app.container.data.open() as DataAccess.Ready).ports

    // Las preferencias se cambian fuera de una transacción: no cuentan como un cambio sin respaldar.
    private suspend fun writeAppearance(ports: DataPorts, appearance: Appearance) {
        val now = app.container.clock.now()
        ports.settings.updatePreference(PreferenceChange.Palette(appearance.palette), now)
        ports.settings.updatePreference(PreferenceChange.Theme(appearance.themeMode), now)
    }

    private companion object {
        /** Una paleta y un modo que no son los iniciales. */
        val CHOSEN = Appearance(ColorPalette.GARNET_HONEY, ThemeMode.DARK)
        const val WAIT_MILLIS = 5_000L
        const val POLL_MILLIS = 50L
    }
}
