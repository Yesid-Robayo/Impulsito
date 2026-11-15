package io.github.yesidrobayo.shopledger.ui.privacy

import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationInfo
import io.github.yesidrobayo.shopledger.domain.model.backup.EncryptionStatus
import io.github.yesidrobayo.shopledger.domain.model.backup.SecondDestinationView
import io.github.yesidrobayo.shopledger.domain.model.safety.PolicyConsentStatus
import io.github.yesidrobayo.shopledger.domain.model.settings.AppLockTimeout
import io.github.yesidrobayo.shopledger.domain.model.settings.AppPreferences
import io.github.yesidrobayo.shopledger.domain.model.settings.ColorPalette
import io.github.yesidrobayo.shopledger.domain.model.settings.MeteredBackupPolicy
import io.github.yesidrobayo.shopledger.domain.model.settings.ThemeMode
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * `Qué datos hay y adónde van` lee el estado real: cada dato aparte, uno que falla dice que no
 * está disponible sin esconder los demás, y volver a la pantalla lo lee otra vez. Aceptar la
 * política nueva desde aquí la guarda y lee de nuevo; si no se guardó, lo dice. Datos inventados.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DataTransparencyViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private var consent = PolicyConsentStatus.ACCEPTED
    private var destination: BackupDestinationInfo? =
        BackupDestinationInfo.WebDav(account = "negocio", host = "example.com")
    private var encryption: EncryptionStatus = EncryptionStatus.Off(wasOn = false)
    private var second: SecondDestinationView = SecondDestinationView.None
    private var destinationFails = false
    private var acceptResult = true
    private var acceptFails = false
    private var accepted = 0
    private val preferences = MutableStateFlow(prefsOf(lock = true, updates = false))

    @Before
    fun useTestMain() = Dispatchers.setMain(dispatcher)

    @After
    fun resetTestMain() = Dispatchers.resetMain()

    private fun model(prefs: Flow<AppPreferences> = preferences) = DataTransparencyViewModel(
        consentStatus = { consent },
        acceptPolicy = {
            if (acceptFails) throw IOException("sin espacio")
            accepted++
            if (acceptResult) consent = PolicyConsentStatus.ACCEPTED
            acceptResult
        },
        describeDestination = {
            if (destinationFails) throw IOException("no se pudo leer")
            destination
        },
        encryptionStatus = { encryption },
        secondDestination = { second },
        preferences = prefs,
        computation = dispatcher,
    )

    private fun TestScope.started(
        prefs: Flow<AppPreferences> = preferences,
    ): DataTransparencyViewModel {
        val model = model(prefs)
        backgroundScope.launch { model.uiState.collect {} }
        advanceUntilIdle()
        return model
    }

    @Test
    fun `it shows every fact read from the phone`() = runTest(dispatcher) {
        second =
            SecondDestinationView.Connected(
                folderName = "Copias",
                lastCopyAt = null,
                followMainBackup = false,
                problem = null,
            )

        val state = started().uiState.value

        assertFalse(state.loading)
        assertEquals(Fact.Known(PolicyConsentStatus.ACCEPTED), state.consent)
        assertEquals(Fact.Known(destination), state.destination)
        assertEquals(Fact.Known(false), state.encryptionOn)
        assertEquals(Fact.Known(SecondFact.Folder("Copias")), state.second)
        assertEquals(Fact.Known(true), state.appLock)
        assertEquals(Fact.Known(false), state.updateCheck)
        assertFalse(state.showPolicyNotice)
        assertFalse(state.uploadsPaused)
    }

    @Test
    fun `without a destination it says so instead of an unknown state`() = runTest(dispatcher) {
        destination = null

        val state = started().uiState.value

        assertEquals(Fact.Known(null), state.destination)
        assertFalse(state.uploadsPaused)
    }

    @Test
    fun `a fact that fails is unavailable and the rest still show`() = runTest(dispatcher) {
        destinationFails = true

        val state = started().uiState.value

        assertEquals(Fact.Unavailable, state.destination)
        assertEquals(Fact.Known(PolicyConsentStatus.ACCEPTED), state.consent)
        assertEquals(Fact.Known(true), state.appLock)
    }

    @Test
    fun `it never guesses the encryption or an unreadable folder`() = runTest(dispatcher) {
        encryption = EncryptionStatus.Unknown
        second = SecondDestinationView.Unreadable

        val state = started().uiState.value

        assertEquals(Fact.Unavailable, state.encryptionOn)
        assertEquals(Fact.Unavailable, state.second)
    }

    @Test
    fun `a lost code keeps encryption on and no code here is off`() = runTest(dispatcher) {
        encryption = EncryptionStatus.KeyUnavailable
        val model = started()
        assertEquals(Fact.Known(true), model.uiState.value.encryptionOn)

        encryption = EncryptionStatus.NoCodeHere
        model.onAction(DataTransparencyAction.Resumed)
        advanceUntilIdle()
        assertEquals(Fact.Known(false), model.uiState.value.encryptionOn)

        encryption = EncryptionStatus.On(enabledAtUtcMillis = 1L)
        model.onAction(DataTransparencyAction.Resumed)
        advanceUntilIdle()
        assertEquals(Fact.Known(true), model.uiState.value.encryptionOn)
    }

    @Test
    fun `returning to the screen reads the real state again`() = runTest(dispatcher) {
        val model = started()

        destination = BackupDestinationInfo.Slots(providerLabel = "Drive")
        model.onAction(DataTransparencyAction.Resumed)
        advanceUntilIdle()

        assertEquals(Fact.Known(destination), model.uiState.value.destination)
    }

    @Test
    fun `settings that cannot be read leave lock and version check unavailable`() =
        runTest(dispatcher) {
            val failing = flow<AppPreferences> { throw IOException("la base no responde") }

            val state = started(failing).uiState.value

            assertFalse(state.loading)
            assertEquals(Fact.Unavailable, state.appLock)
            assertEquals(Fact.Unavailable, state.updateCheck)
        }

    @Test
    fun `it keeps loading until the settings arrive`() = runTest(dispatcher) {
        val waiting = flow<AppPreferences> { awaitCancellation() }

        val state = started(waiting).uiState.value

        assertTrue(state.loading)
    }

    @Test
    fun `an older accepted policy shows the notice and pauses the backups`() = runTest(dispatcher) {
        consent = PolicyConsentStatus.OUTDATED

        val state = started().uiState.value

        assertTrue(state.showPolicyNotice)
        assertTrue(state.uploadsPaused)
    }

    @Test
    fun `never accepted pauses the backups but shows no notice`() = runTest(dispatcher) {
        consent = PolicyConsentStatus.NEVER_ACCEPTED

        val state = started().uiState.value

        assertFalse(state.showPolicyNotice)
        assertTrue(state.uploadsPaused)
    }

    @Test
    fun `deciding later hides the notice without recording anything`() = runTest(dispatcher) {
        consent = PolicyConsentStatus.OUTDATED
        val model = started()

        model.onAction(DataTransparencyAction.DecideLater)
        advanceUntilIdle()

        assertFalse(model.uiState.value.showPolicyNotice)
        assertTrue(model.uiState.value.uploadsPaused)
        assertEquals(0, accepted)
    }

    @Test
    fun `accepting saves it and reads the state again`() = runTest(dispatcher) {
        consent = PolicyConsentStatus.OUTDATED
        val model = started()

        model.onAction(DataTransparencyAction.AcceptPolicy)
        advanceUntilIdle()

        val state = model.uiState.value
        assertEquals(1, accepted)
        assertEquals(Fact.Known(PolicyConsentStatus.ACCEPTED), state.consent)
        assertFalse(state.showPolicyNotice)
        assertFalse(state.uploadsPaused)
        assertFalse(state.accepting)
        assertFalse(state.acceptFailed)
    }

    @Test
    fun `an acceptance that was not saved says so and changes nothing`() = runTest(dispatcher) {
        consent = PolicyConsentStatus.OUTDATED
        acceptResult = false
        val model = started()

        model.onAction(DataTransparencyAction.AcceptPolicy)
        advanceUntilIdle()

        assertTrue(model.uiState.value.acceptFailed)
        assertTrue(model.uiState.value.showPolicyNotice)

        model.onAction(DataTransparencyAction.MessageShown)
        advanceUntilIdle()
        assertFalse(model.uiState.value.acceptFailed)
    }

    @Test
    fun `an acceptance that throws is not saved`() = runTest(dispatcher) {
        consent = PolicyConsentStatus.OUTDATED
        acceptFails = true
        val model = started()

        model.onAction(DataTransparencyAction.AcceptPolicy)
        advanceUntilIdle()

        assertTrue(model.uiState.value.acceptFailed)
        assertFalse(model.uiState.value.accepting)
    }

    @Test
    fun `navigation actions do not change the state`() = runTest(dispatcher) {
        val model = started()
        val before = model.uiState.value

        model.onAction(DataTransparencyAction.Back)
        model.onAction(DataTransparencyAction.OpenPrivacy)
        model.onAction(DataTransparencyAction.OpenTerms)
        advanceUntilIdle()

        assertEquals(before, model.uiState.value)
    }

    private fun prefsOf(lock: Boolean, updates: Boolean) = AppPreferences(
        themeMode = ThemeMode.SYSTEM,
        colorPalette = ColorPalette.FOREST_HONEY,
        lowStockNotificationsEnabled = true,
        appLockEnabled = lock,
        appLockTimeout = AppLockTimeout.ONE_MINUTE,
        meteredBackupPolicy = MeteredBackupPolicy.EVERY_60_MIN,
        discreetModeEnabled = false,
        mediaOnMetered = false,
        updateCheckEnabled = updates,
    )
}
