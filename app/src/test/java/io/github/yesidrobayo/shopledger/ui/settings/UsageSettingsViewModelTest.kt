package io.github.yesidrobayo.shopledger.ui.settings

import io.github.yesidrobayo.shopledger.domain.model.settings.AppLockTimeout
import io.github.yesidrobayo.shopledger.domain.model.settings.AppPreferences
import io.github.yesidrobayo.shopledger.domain.model.settings.ColorPalette
import io.github.yesidrobayo.shopledger.domain.model.settings.MeteredBackupPolicy
import io.github.yesidrobayo.shopledger.domain.model.settings.PreferenceChange
import io.github.yesidrobayo.shopledger.domain.model.settings.ThemeMode
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.usecase.settings.ChangePreferenceResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Los avisos de stock y el tema en Ajustes: muestran lo guardado, guardan solo la columna que
 * cambia, nunca la paleta, no escriben si no cambia nada y dicen si no se pudo guardar.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UsageSettingsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val preferences =
        MutableStateFlow(
            AppPreferences(
                themeMode = ThemeMode.SYSTEM,
                colorPalette = ColorPalette.FOREST_HONEY,
                lowStockNotificationsEnabled = true,
                appLockEnabled = false,
                appLockTimeout = AppLockTimeout.ONE_MINUTE,
                meteredBackupPolicy = MeteredBackupPolicy.EVERY_60_MIN,
                discreetModeEnabled = false,
                mediaOnMetered = false,
                updateCheckEnabled = true,
            ),
        )
    private val changes = mutableListOf<PreferenceChange>()
    private var result: ChangePreferenceResult = ChangePreferenceResult.Saved

    @Before
    fun useTestMain() = Dispatchers.setMain(dispatcher)

    @After
    fun resetTestMain() = Dispatchers.resetMain()

    private fun model() = UsageSettingsViewModel(
        preferences = { preferences },
        changePreference = { change ->
            changes += change
            if (result == ChangePreferenceResult.Saved) {
                preferences.value = when (change) {
                    is PreferenceChange.Theme -> preferences.value.copy(themeMode = change.mode)

                    is PreferenceChange.LowStockNotifications ->
                        preferences.value.copy(lowStockNotificationsEnabled = change.enabled)

                    // Esta pantalla no cambia las demás: si lo hiciera, la prueba lo vería.
                    is PreferenceChange.Palette,
                    is PreferenceChange.AppLock,
                    is PreferenceChange.AppLockDelay,
                    is PreferenceChange.MeteredBackup,
                    is PreferenceChange.DiscreetMode,
                    -> preferences.value
                }
            }
            result
        },
    )

    @Test
    fun `it shows the saved theme and alerts`() = runTest(dispatcher) {
        val model = model()
        backgroundScope.launch { model.uiState.collect {} }
        advanceUntilIdle()

        assertEquals(ThemeMode.SYSTEM, model.uiState.value.theme)
        assertEquals(true, model.uiState.value.stockAlerts)
    }

    @Test
    fun `choosing a theme saves only the theme and keeps the palette`() = runTest(dispatcher) {
        val model = model()
        backgroundScope.launch { model.uiState.collect {} }
        advanceUntilIdle()

        model.onThemeChange(ThemeMode.DARK)
        advanceUntilIdle()

        assertEquals(listOf<PreferenceChange>(PreferenceChange.Theme(ThemeMode.DARK)), changes)
        assertEquals(ThemeMode.DARK, model.uiState.value.theme)
        assertEquals(ColorPalette.FOREST_HONEY, preferences.value.colorPalette)
    }

    @Test
    fun `the same theme writes nothing`() = runTest(dispatcher) {
        val model = model()
        backgroundScope.launch { model.uiState.collect {} }
        advanceUntilIdle()

        model.onThemeChange(ThemeMode.SYSTEM)
        model.onStockAlertsChange(true)
        advanceUntilIdle()

        assertTrue(changes.isEmpty())
    }

    @Test
    fun `turning alerts off saves it`() = runTest(dispatcher) {
        val model = model()
        backgroundScope.launch { model.uiState.collect {} }
        advanceUntilIdle()

        model.onStockAlertsChange(false)
        advanceUntilIdle()

        assertEquals(
            listOf<PreferenceChange>(PreferenceChange.LowStockNotifications(false)),
            changes,
        )
        assertEquals(false, model.uiState.value.stockAlerts)
    }

    @Test
    fun `a failed save says so and keeps what was saved`() = runTest(dispatcher) {
        result = ChangePreferenceResult.Failed(StorageFailure.IO_ERROR)
        val model = model()
        backgroundScope.launch { model.uiState.collect {} }
        advanceUntilIdle()

        model.onThemeChange(ThemeMode.LIGHT)
        advanceUntilIdle()

        assertTrue(model.uiState.value.saveFailed)
        assertEquals(ThemeMode.SYSTEM, model.uiState.value.theme)

        result = ChangePreferenceResult.Saved
        model.onThemeChange(ThemeMode.LIGHT)
        advanceUntilIdle()
        assertFalse(model.uiState.value.saveFailed)
    }

    @Test
    fun `unreadable preferences say so and a retry reads them again`() = runTest(dispatcher) {
        var fail = true
        val model =
            UsageSettingsViewModel(
                preferences = {
                    flow {
                        if (fail) error("sin ajustes")
                        emitAll(preferences)
                    }
                },
                changePreference = { ChangePreferenceResult.Saved },
            )
        backgroundScope.launch { model.uiState.collect {} }
        advanceUntilIdle()

        assertTrue(model.uiState.value.readFailed)
        assertNull(model.uiState.value.theme)
        assertNull(model.uiState.value.stockAlerts)

        fail = false
        model.onRetry()
        advanceUntilIdle()

        assertFalse(model.uiState.value.readFailed)
        assertEquals(ThemeMode.SYSTEM, model.uiState.value.theme)
    }
}
