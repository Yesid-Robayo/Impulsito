package io.github.yesidrobayo.shopledger.ui.safety

import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.ShopLedgerApp
import io.github.yesidrobayo.shopledger.domain.usecase.safety.PolicyConsentUseCase
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * La compuerta en el teléfono, con la aceptación real de la app: sin aceptar no se ve la pantalla que
 * conecta; aceptar la guarda en `consents.json` y deja pasar; atrás no guarda nada y vuelve.
 */
@RunWith(AndroidJUnit4::class)
class PolicyGateDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val app = context.applicationContext as ShopLedgerApp
    private val consents = File(context.noBackupFilesDir, "consents.json")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var declined = false

    @Before
    fun startWithoutAccepting() {
        consents.delete()
    }

    @After
    fun clean() {
        scope.cancel()
        consents.delete()
    }

    private fun show() {
        val prompt = PolicyPrompt(PolicyConsentUseCase(app.container.data.policyConsent), scope)
        compose.setContent {
            PreviewTheme {
                CompositionLocalProvider(LocalPolicyPrompt provides prompt) {
                    Box {
                        PolicyGated(onDeclined = { declined = true }) { Text(CONNECT) }
                        PolicyPromptHost(prompt)
                    }
                }
            }
        }
    }

    @Test
    fun acceptingSavesTheVersionAndOpensTheScreen() {
        show()
        val accept = context.getString(R.string.legal_accept_action)
        compose.waitUntil(WAIT_MS) {
            compose.onAllNodesWithText(accept).fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(compose.onAllNodesWithText(CONNECT).fetchSemanticsNodes().isEmpty())

        compose.onNodeWithText(accept).performClick()

        compose.waitUntil(WAIT_MS) {
            compose.onAllNodesWithText(CONNECT).fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(consents.exists())
    }

    @Test
    fun goingBackSavesNothingAndLeaves() {
        show()
        val accept = context.getString(R.string.legal_accept_action)
        compose.waitUntil(WAIT_MS) {
            compose.onAllNodesWithText(accept).fetchSemanticsNodes().isNotEmpty()
        }

        pressBack()

        compose.waitUntil(WAIT_MS) { declined }
        assertFalse(consents.exists())
        assertTrue(compose.onAllNodesWithText(CONNECT).fetchSemanticsNodes().isEmpty())
    }

    private fun pressBack() {
        for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            val time = SystemClock.uptimeMillis()
            val event = KeyEvent(time, time, action, KeyEvent.KEYCODE_BACK, 0)
            assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true))
        }
    }

    private companion object {
        const val CONNECT = "Pantalla que conecta"
        const val WAIT_MS = 10_000L
    }
}
