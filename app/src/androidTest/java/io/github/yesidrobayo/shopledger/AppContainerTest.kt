package io.github.yesidrobayo.shopledger

import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.data.DataAccess
import io.github.yesidrobayo.shopledger.data.DataPorts
import io.github.yesidrobayo.shopledger.di.AppContainer
import io.github.yesidrobayo.shopledger.domain.model.settings.BusinessProfile
import io.github.yesidrobayo.shopledger.domain.port.TxResult
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
 * Usa la base de la app instalada para las pruebas, con datos inventados, y deja el perfil como lo
 * encontró. Abre la actividad sin herramientas de interfaz: mira el estado que la actividad leyó.
 */
@RunWith(AndroidJUnit4::class)
class AppContainerTest {
    private val app =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
            as ShopLedgerApp

    private var originalProfile: BusinessProfile? = null

    @After
    fun restoreProfile() {
        runBlocking { originalProfile?.let { profile -> writeProfile(ports(), profile) } }
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
    fun theActivityReadsTheBusinessNameThroughTheContainer() {
        val ports = runBlocking { ports() }
        val original = runBlocking { ports.settings.get().profile }
        originalProfile = original

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            runBlocking { writeProfile(ports, original.copy(businessName = BUSINESS_NAME)) }

            assertEquals(BUSINESS_NAME, awaitShownName(scenario))
        }
    }

    /** Espera a que la actividad muestre [BUSINESS_NAME], que llega desde la base. */
    private fun awaitShownName(scenario: ActivityScenario<MainActivity>): String? {
        val deadline = SystemClock.elapsedRealtime() + WAIT_MILLIS
        var shown: String? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            scenario.onActivity { shown = it.businessName.value }
            if (shown == BUSINESS_NAME) return shown
            Thread.sleep(POLL_MILLIS)
        }
        return shown
    }

    private suspend fun ports(): DataPorts = (app.container.data.open() as DataAccess.Ready).ports

    private suspend fun writeProfile(ports: DataPorts, profile: BusinessProfile) {
        ports.transactions.write {
            ports.settings.updateProfile(profile, app.container.clock.now())
            TxResult.Commit(Unit)
        }
    }

    private companion object {
        /** Un nombre inventado, que no es el de la app. */
        const val BUSINESS_NAME = "Tienda de prueba"
        const val WAIT_MILLIS = 5_000L
        const val POLL_MILLIS = 50L
    }
}
