package io.github.yesidrobayo.shopledger.data.local.settings

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.local.catalog.CatalogDatabaseTest
import io.github.yesidrobayo.shopledger.data.local.catalog.Emissions
import io.github.yesidrobayo.shopledger.data.local.catalog.LATER
import io.github.yesidrobayo.shopledger.data.local.catalog.MUCH_LATER
import io.github.yesidrobayo.shopledger.data.local.catalog.guardFailure
import io.github.yesidrobayo.shopledger.data.local.functionsOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Los ajustes, en su única fila: cada escritura cambia solo sus columnas y `updated_at`, ninguna
 * pisa otra con un valor viejo, y la fila no se inserta ni se borra desde un DAO.
 *
 * La base nace sin moneda, como la de un negocio nuevo: así se prueba confirmarla y bloquearla.
 */
@RunWith(AndroidJUnit4::class)
internal class AppSettingsDaoTest : CatalogDatabaseTest() {
    override val confirmsCurrency: Boolean = false

    private val settings get() = database.appSettingsDao()

    @Test
    fun theCurrencyChangesOnlyItsColumnsAndLocksOnlyOnce() = runBlocking {
        val initial = requireNotNull(settings.get())

        assertEquals(1, settings.updateCurrency("XTS", 3, LATER))
        val confirmed = initial.copy(currencyCode = "XTS", currencyDecimals = 3, updatedAt = LATER)
        assertEquals(confirmed, settings.get())

        assertEquals(1, settings.lockCurrency(MUCH_LATER))
        val locked = confirmed.copy(currencyLockedAt = MUCH_LATER, updatedAt = MUCH_LATER)
        assertEquals(locked, settings.get())
        // El segundo registro con dinero no mueve el bloqueo ni el último cambio.
        assertEquals(0, settings.lockCurrency(MUCH_LATER + 1))
        assertEquals(locked, settings.get())
    }

    @Test
    fun eachPreferenceChangesOnlyItsColumn() = runBlocking {
        val initial = requireNotNull(settings.get())

        assertEquals(1, settings.updateThemeMode("DARK", LATER))
        assertEquals(initial.copy(themeMode = "DARK", updatedAt = LATER), settings.get())
        // La paleta es independiente del tema: cambiarla no toca el modo oscuro.
        assertEquals(1, settings.updateColorPalette("FOREST_HONEY", LATER))
        assertEquals(
            initial.copy(themeMode = "DARK", colorPalette = "FOREST_HONEY", updatedAt = LATER),
            settings.get(),
        )

        assertEquals(1, settings.updateLowStockNotificationsEnabled(0, MUCH_LATER))
        assertEquals(1, settings.updateAppLockEnabled(1, MUCH_LATER))
        assertEquals(1, settings.updateAppLockTimeoutSeconds(300, MUCH_LATER))
        assertEquals(1, settings.updateMeteredBackupPolicy("WIFI_ONLY", MUCH_LATER))

        // Cada cambio conserva los anteriores: ninguno reescribe la fila entera.
        val expected =
            initial.copy(
                themeMode = "DARK",
                colorPalette = "FOREST_HONEY",
                lowStockNotificationsEnabled = 0,
                appLockEnabled = 1,
                appLockTimeoutSeconds = 300,
                meteredBackupPolicy = "WIFI_ONLY",
                updatedAt = MUCH_LATER,
            )
        assertEquals(expected, settings.get())
    }

    @Test
    fun profileChangesOnlyTheBusinessNameAndMargin() = runBlocking {
        settings.updateThemeMode("LIGHT", LATER)
        val before = requireNotNull(settings.get())

        assertEquals(1, settings.updateProfile("Tienda La Esquina", 4_500, MUCH_LATER))
        assertEquals(
            before.copy(
                businessName = "Tienda La Esquina",
                defaultTargetMarginBp = 4_500,
                updatedAt = MUCH_LATER,
            ),
            settings.get(),
        )

        // La persona puede borrar el nombre del negocio.
        assertEquals(1, settings.updateProfile(null, 4_500, MUCH_LATER))
        assertNull(settings.get()?.businessName)
    }

    @Test
    fun onboardingCompletionIsAlsoTheLastChange() = runBlocking {
        val initial = requireNotNull(settings.get())

        assertEquals(1, settings.markOnboardingCompleted(LATER))

        assertEquals(
            initial.copy(onboardingCompletedAt = LATER, updatedAt = LATER),
            settings.get(),
        )
    }

    @Test
    fun settingsEmitAgainAfterEachChange() = runBlocking {
        val initial = requireNotNull(settings.get())
        val emissions = Emissions(this, settings.observe())
        try {
            emissions.awaitValue(initial)

            settings.updateMeteredBackupPolicy("EVERY_30_MIN", LATER)
            emissions.awaitValue(
                initial.copy(meteredBackupPolicy = "EVERY_30_MIN", updatedAt = LATER),
            )
        } finally {
            emissions.stop()
        }
    }

    @Test
    fun withoutTheRowNothingIsWrittenAndNothingIsRead() = runBlocking {
        // Solo en esta prueba, y a mano: ningún DAO borra la fila, y sin ella cada escritura
        // devuelve 0 en vez de crear otra. Su guarda impide borrarla: la prueba la retira antes,
        // como la encontraría la app en una base dañada.
        guardFailure("guard:app_settings_no_delete") { execute("DELETE FROM app_settings") }
        execute("DROP TRIGGER guard_app_settings_no_delete")
        execute("DELETE FROM app_settings")

        assertNull(settings.get())
        assertEquals(0, settings.updateThemeMode("DARK", LATER))
        assertEquals(0, settings.updateProfile("Otra", 1_000, LATER))
        assertEquals(0, settings.markOnboardingCompleted(LATER))
        assertEquals(0, settings.updateCurrency("XTS", 2, LATER))
        assertEquals(0, settings.lockCurrency(LATER))
        assertEquals(0, settings.updateBusinessTimeZone("Etc/GMT+5", LATER))
        assertEquals(0, settings.updateWeekStartDay(7, LATER))
        assertEquals(0L, count("SELECT COUNT(*) FROM app_settings"))
    }

    @Test
    fun theDaoNeverInsertsNorDeletesTheRow() {
        // La fila nace con la base. Una función nueva rompe esta prueba a propósito: quien la
        // agregue revisa que cambie solo sus columnas.
        assertEquals(
            setOf(
                "observe",
                "get",
                "updateProfile",
                "updateThemeMode",
                "updateColorPalette",
                "updateLowStockNotificationsEnabled",
                "updateAppLockEnabled",
                "updateAppLockTimeoutSeconds",
                "updateMeteredBackupPolicy",
                "updateCurrency",
                "lockCurrency",
                "updateBusinessTimeZone",
                "updateWeekStartDay",
                "markOnboardingCompleted",
            ),
            functionsOf(AppSettingsDao::class.java),
        )
    }
}
