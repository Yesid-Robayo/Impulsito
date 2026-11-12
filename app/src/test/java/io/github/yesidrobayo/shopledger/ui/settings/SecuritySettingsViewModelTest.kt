package io.github.yesidrobayo.shopledger.ui.settings

import io.github.yesidrobayo.shopledger.domain.model.settings.AppLockTimeout
import io.github.yesidrobayo.shopledger.domain.model.settings.PreferenceChange
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.usecase.settings.AppLockSettings
import io.github.yesidrobayo.shopledger.domain.usecase.settings.ChangePreferenceResult
import io.github.yesidrobayo.shopledger.lock.AuthOutcome
import io.github.yesidrobayo.shopledger.lock.AuthPurpose
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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
 * El bloqueo en Ajustes: solo cambia tras autenticarse, una verificación cancelada no cambia nada, y
 * sin bloqueo de pantalla en el teléfono se explica en vez de encenderlo. Apagarlo pide la
 * verificación más fuerte.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SecuritySettingsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val lock = MutableStateFlow(AppLockSettings(false, AppLockTimeout.ONE_MINUTE))
    private val changes = mutableListOf<PreferenceChange>()
    private var result: ChangePreferenceResult = ChangePreferenceResult.Saved

    @Before
    fun useTestMain() = Dispatchers.setMain(dispatcher)

    @After
    fun resetTestMain() = Dispatchers.resetMain()

    private fun model() = SecuritySettingsViewModel(
        lockSource = { lock },
        changePreference = { change ->
            changes += change
            if (result == ChangePreferenceResult.Saved) {
                lock.value = when (change) {
                    is PreferenceChange.AppLock -> lock.value.copy(enabled = change.enabled)

                    is PreferenceChange.AppLockDelay -> lock.value.copy(timeout = change.timeout)

                    is PreferenceChange.Theme,
                    is PreferenceChange.Palette,
                    is PreferenceChange.LowStockNotifications,
                    is PreferenceChange.MeteredBackup,
                    is PreferenceChange.DiscreetMode,
                    -> error("El grupo solo cambia el bloqueo")
                }
            }
            result
        },
    )

    private fun TestScope.started(): SecuritySettingsViewModel {
        val model = model()
        backgroundScope.launch { model.uiState.collect {} }
        advanceUntilIdle()
        return model
    }

    @Test
    fun `it shows the saved lock`() = runTest(dispatcher) {
        val model = started()

        assertEquals(AppLockSettings(false, AppLockTimeout.ONE_MINUTE), model.uiState.value.lock)
    }

    @Test
    fun `the lock is turned on only after authenticating`() = runTest(dispatcher) {
        val model = started()

        model.onLockAuthenticated(true, AuthOutcome.Success)
        advanceUntilIdle()

        assertEquals(listOf<PreferenceChange>(PreferenceChange.AppLock(true)), changes)
        assertEquals(true, model.uiState.value.lock?.enabled)
    }

    @Test
    fun `a cancelled or failed check changes nothing`() = runTest(dispatcher) {
        val model = started()

        model.onLockAuthenticated(true, AuthOutcome.Canceled)
        model.onLockAuthenticated(true, AuthOutcome.Failed("Demasiados intentos"))
        advanceUntilIdle()

        assertEquals(emptyList<PreferenceChange>(), changes)
        assertEquals(false, model.uiState.value.lock?.enabled)
        assertFalse(model.uiState.value.needsDeviceLock)
    }

    @Test
    fun `without a screen lock it explains instead of turning it on`() = runTest(dispatcher) {
        val model = started()

        model.onLockAuthenticated(true, AuthOutcome.NoDeviceCredential)
        advanceUntilIdle()

        assertTrue(model.uiState.value.needsDeviceLock)
        assertEquals(emptyList<PreferenceChange>(), changes)
    }

    @Test
    fun `turning it off on a phone that lost its screen lock saves it off`() = runTest(dispatcher) {
        lock.value = AppLockSettings(true, AppLockTimeout.ONE_MINUTE)
        val model = started()

        model.onLockAuthenticated(false, AuthOutcome.NoDeviceCredential)
        advanceUntilIdle()

        assertFalse(model.uiState.value.needsDeviceLock)
        assertEquals(listOf<PreferenceChange>(PreferenceChange.AppLock(false)), changes)
        assertEquals(false, model.uiState.value.lock?.enabled)
    }

    @Test
    fun `turning it off after authenticating saves it off`() = runTest(dispatcher) {
        lock.value = AppLockSettings(true, AppLockTimeout.ONE_MINUTE)
        val model = started()

        model.onLockAuthenticated(false, AuthOutcome.Success)
        advanceUntilIdle()

        assertEquals(listOf<PreferenceChange>(PreferenceChange.AppLock(false)), changes)
    }

    @Test
    fun `turning it on asks for the unlock check and off for the sensitive one`() {
        assertEquals(AuthPurpose.UNLOCK, SecuritySettingsViewModel.purposeFor(true))
        assertEquals(AuthPurpose.SENSITIVE, SecuritySettingsViewModel.purposeFor(false))
    }

    @Test
    fun `a new delay is saved and the same one writes nothing`() = runTest(dispatcher) {
        lock.value = AppLockSettings(true, AppLockTimeout.ONE_MINUTE)
        val model = started()

        model.onTimeoutChange(AppLockTimeout.ONE_MINUTE)
        model.onTimeoutChange(AppLockTimeout.IMMEDIATELY)
        advanceUntilIdle()

        assertEquals(
            listOf<PreferenceChange>(PreferenceChange.AppLockDelay(AppLockTimeout.IMMEDIATELY)),
            changes,
        )
        assertEquals(AppLockTimeout.IMMEDIATELY, model.uiState.value.lock?.timeout)
    }

    @Test
    fun `a save failure is shown and the next change clears it`() = runTest(dispatcher) {
        val model = started()
        result = ChangePreferenceResult.Failed(StorageFailure.RESTORE_IN_PROGRESS)

        model.onLockAuthenticated(true, AuthOutcome.Success)
        advanceUntilIdle()
        assertTrue(model.uiState.value.saveFailed)
        assertEquals(false, model.uiState.value.lock?.enabled)

        result = ChangePreferenceResult.Saved
        model.onLockAuthenticated(true, AuthOutcome.Success)
        advanceUntilIdle()
        assertFalse(model.uiState.value.saveFailed)
        assertEquals(true, model.uiState.value.lock?.enabled)
    }

    @Test
    fun `without the setting the group is not drawn`() = runTest(dispatcher) {
        val model =
            SecuritySettingsViewModel(
                lockSource = { flow { throw IllegalStateException("Sin base") } },
                changePreference = { ChangePreferenceResult.Saved },
            )
        backgroundScope.launch { model.uiState.collect {} }
        advanceUntilIdle()

        assertNull(model.uiState.value.lock)
    }
}
