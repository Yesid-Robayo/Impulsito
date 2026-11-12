package io.github.yesidrobayo.shopledger.ui.lock

import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.settings.AppLockTimeout
import io.github.yesidrobayo.shopledger.lock.AppLock
import io.github.yesidrobayo.shopledger.lock.AppLockState
import io.github.yesidrobayo.shopledger.lock.AuthOutcome
import io.github.yesidrobayo.shopledger.lock.AuthPurpose
import io.github.yesidrobayo.shopledger.lock.DeviceAuthenticator
import io.github.yesidrobayo.shopledger.lock.LocalDeviceAuthenticator
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * La compuerta del bloqueo en el teléfono. Bloqueada, el árbol de Compose no contiene ningún nodo
 * del negocio, solo la pantalla del bloqueo; lo guardado detrás vuelve igual al desbloquear. La
 * verificación se abre sola al entrar, y cancelarla, que falle o que el teléfono no tenga bloqueo de
 * pantalla nunca deja a nadie fuera ni pierde nada. Datos inventados.
 */
@RunWith(AndroidJUnit4::class)
class AppLockGateTest {
    @get:Rule
    val compose = createComposeRule()

    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources

    private fun text(id: Int) = resources.getString(id)

    @Test
    fun aLockedAppHasNoBusinessNodeInTheTree() {
        compose.setContent {
            PreviewTheme {
                AppLockGate(AppLockState.Locked, locked = { LockScreen(null, {}) }) {
                    Text(BUSINESS_SENTINEL)
                }
            }
        }

        compose.onNodeWithText(BUSINESS_SENTINEL, substring = true).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.lock_screen_body)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.lock_unlock)).assertIsDisplayed()
    }

    @Test
    fun beforeTheSettingIsReadNothingIsShown() {
        compose.setContent {
            PreviewTheme {
                AppLockGate(AppLockState.Unknown, locked = { LockScreen(null, {}) }) {
                    Text(BUSINESS_SENTINEL)
                }
            }
        }

        compose.onNodeWithText(BUSINESS_SENTINEL).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.lock_unlock)).assertDoesNotExist()
    }

    @Test
    fun whatWasWrittenBehindTheLockComesBackUnchanged() {
        var state by mutableStateOf<AppLockState>(AppLockState.Open(lockEnabled = true))
        var created = 0
        compose.setContent {
            PreviewTheme {
                AppLockGate(state, locked = { LockScreen(null, {}) }) {
                    // Como la venta a medio escribir: vive en el estado guardado de la pantalla. Si
                    // volviera a nacer, el número cambiaría.
                    val draft = rememberSaveable {
                        created += 1
                        "$DRAFT $created"
                    }
                    Text(draft)
                }
            }
        }
        compose.onNodeWithText("$DRAFT 1").assertIsDisplayed()

        state = AppLockState.Locked
        compose.waitForIdle()
        compose.onNodeWithText(DRAFT, substring = true).assertDoesNotExist()

        state = AppLockState.Open(lockEnabled = true)
        compose.waitForIdle()
        compose.onNodeWithText("$DRAFT 1").assertIsDisplayed()
        assertEquals(1, created)
    }

    @Test
    fun theCheckOpensOnEntryAndASuccessShowsTheBusiness() {
        val lock = lockedApp()
        val authenticator = ScriptedAuthenticator(AuthOutcome.Success)
        showRoute(lock, authenticator)

        compose.waitUntil(TIMEOUT_MILLIS) { lock.state.value is AppLockState.Open }
        assertEquals(AppLockState.Open(lockEnabled = true), lock.state.value)
        assertEquals(listOf(AuthPurpose.UNLOCK), authenticator.purposes)
    }

    @Test
    fun aCancelledCheckStaysLockedAndUnlockAsksAgain() {
        val lock = lockedApp()
        val authenticator = ScriptedAuthenticator(AuthOutcome.Canceled, AuthOutcome.Success)
        showRoute(lock, authenticator)
        compose.waitUntil(TIMEOUT_MILLIS) { authenticator.purposes.size == 1 }
        assertEquals(AppLockState.Locked, lock.state.value)

        compose.onNodeWithText(text(R.string.lock_unlock)).performClick()

        compose.waitUntil(TIMEOUT_MILLIS) { lock.state.value is AppLockState.Open }
        assertEquals(2, authenticator.purposes.size)
    }

    @Test
    fun aFailedCheckShowsTheSystemMessageAndStaysLocked() {
        val lock = lockedApp()
        showRoute(lock, ScriptedAuthenticator(AuthOutcome.Failed(SYSTEM_MESSAGE)))

        compose.onNodeWithText(SYSTEM_MESSAGE).assertIsDisplayed()
        assertEquals(AppLockState.Locked, lock.state.value)
    }

    @Test
    fun aPhoneWithoutScreenLockIsNeverLockedOut() {
        val lock = lockedApp()
        var turnedOff = false
        showRoute(lock, ScriptedAuthenticator(AuthOutcome.NoDeviceCredential)) {
            turnedOff = true
            lock.noDeviceCredential()
        }

        compose.waitUntil(TIMEOUT_MILLIS) { lock.state.value is AppLockState.Open }
        assertTrue(turnedOff)
        assertEquals(AppLockState.Open(lockEnabled = false), lock.state.value)
    }

    private fun lockedApp(): AppLock = AppLock { 0L }.also {
        it.apply(enabled = true, AppLockTimeout.ONE_MINUTE)
    }

    private fun showRoute(
        lock: AppLock,
        authenticator: DeviceAuthenticator,
        onNoDeviceCredential: () -> Unit = {},
    ) {
        compose.setContent {
            PreviewTheme {
                CompositionLocalProvider(LocalDeviceAuthenticator provides authenticator) {
                    LockRoute(lock, onNoDeviceCredential)
                }
            }
        }
    }

    /** Responde cada verificación con el siguiente resultado de la lista; el último se repite. */
    private class ScriptedAuthenticator(vararg outcomes: AuthOutcome) : DeviceAuthenticator {
        private val script = outcomes.toList()
        val purposes = mutableListOf<AuthPurpose>()

        override fun hasDeviceCredential(): Boolean = true

        override suspend fun authenticate(
            purpose: AuthPurpose,
            title: String,
            subtitle: String?,
        ): AuthOutcome {
            purposes += purpose
            return script[minOf(purposes.size, script.size) - 1]
        }
    }

    private companion object {
        const val BUSINESS_SENTINEL = "Ganancia 98765"
        const val DRAFT = "Venta a medio escribir"
        const val SYSTEM_MESSAGE = "Demasiados intentos."
        const val TIMEOUT_MILLIS = 5_000L
    }
}
