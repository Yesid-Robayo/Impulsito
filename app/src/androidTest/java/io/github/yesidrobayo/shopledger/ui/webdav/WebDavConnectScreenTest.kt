package io.github.yesidrobayo.shopledger.ui.webdav

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.backup.WebDavTestFailure
import io.github.yesidrobayo.shopledger.domain.model.backup.WebDavTestStep
import io.github.yesidrobayo.shopledger.lock.LocalWindowProtection
import io.github.yesidrobayo.shopledger.lock.WindowProtection
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `Conectar el servicio` en el teléfono: el botón espera los tres campos, la contraseña va oculta y
 * marcada como dato sensible, los pasos dicen su estado a TalkBack, un fallo nombra su paso,
 * `Reintentar` solo sin respuesta, el servidor se confirma en ASCII y la ventana lleva
 * `FLAG_SECURE`.
 */
@RunWith(AndroidJUnit4::class)
class WebDavConnectScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val resources get() = compose.activity.resources
    private val actions = mutableListOf<WebDavConnectAction>()
    private var state by mutableStateOf(WebDavConnectUiState())

    private fun show(initial: WebDavConnectUiState) {
        state = initial
        compose.setContent {
            PreviewTheme { WebDavConnectScreen(state, onAction = { actions += it }) }
        }
        compose.waitForIdle()
    }

    private fun string(id: Int) = resources.getString(id)

    @Test
    fun theButtonWaitsForTheThreeFields() {
        show(PREVIEW_FILLED.copy(password = ""))
        val connect = string(R.string.webdav_connect_action)
        compose.onNodeWithText(connect).assertIsNotEnabled()

        state = PREVIEW_FILLED
        compose.waitForIdle()
        compose.onNodeWithText(connect).assertIsEnabled().performClick()

        assertEquals(listOf<WebDavConnectAction>(WebDavConnectAction.Connect), actions)
    }

    @Test
    fun thePasswordIsHiddenSensitiveAndCanBeShown() {
        show(PREVIEW_FILLED)
        val password =
            compose.onNode(
                hasSetTextAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.Password),
            )
        password.assertIsDisplayed()
        assertTrue(
            password.fetchSemanticsNode().config.getOrElse(SemanticsProperties.IsSensitiveData) {
                false
            },
        )
        // Oculta, se dibuja con puntos: el texto visible no es la contraseña.
        assertTrue(
            compose.onAllNodes(hasText(PREVIEW_FILLED.password, substring = false))
                .fetchSemanticsNodes()
                .none { node ->
                    node.config.getOrElse(SemanticsProperties.Text) { emptyList() }
                        .any { it.text == PREVIEW_FILLED.password }
                },
        )

        compose.onNodeWithContentDescription(string(R.string.webdav_password_show_action))
            .performClick()

        assertEquals(listOf<WebDavConnectAction>(WebDavConnectAction.TogglePassword), actions)
    }

    @Test
    fun testingShowsEveryStepWithItsStateAndCanBeCancelled() {
        show(
            PREVIEW_FILLED.copy(
                phase = WebDavPhase.TESTING,
                steps = previewSteps(WebDavTestStep.MOVE),
            ),
        )
        compose.onNodeWithText(string(R.string.webdav_testing)).assertIsDisplayed()
        val done = string(R.string.webdav_step_state_done)
        val running = string(R.string.webdav_step_state_running)
        val pending = string(R.string.webdav_step_state_pending)
        compose.onNode(hasText(string(R.string.webdav_step_list)))
            .performScrollTo()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, done))
        compose.onNode(hasText(string(R.string.webdav_step_move)))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, running))
        compose.onNode(hasText(string(R.string.webdav_step_delete)))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, pending))

        compose.onNodeWithText(string(R.string.action_cancel)).performClick()

        assertEquals(listOf<WebDavConnectAction>(WebDavConnectAction.Cancel), actions)
    }

    @Test
    fun aFailureNamesItsStepAndOnlyNoAnswerOffersRetry() {
        show(
            PREVIEW_FILLED.copy(
                steps = previewSteps(WebDavTestStep.NO_OVERWRITE, failed = true),
                notice =
                    WebDavNotice.Failed(WebDavTestStep.NO_OVERWRITE, WebDavTestFailure.UNSUPPORTED),
            ),
        )
        val text =
            resources.getString(
                R.string.webdav_error_unsupported,
                string(R.string.webdav_step_no_overwrite),
            )
        compose.onNodeWithText(text).performScrollTo().assertIsDisplayed()
        val retry = string(R.string.action_retry)
        assertTrue(compose.onAllNodesWithText(retry).fetchSemanticsNodes().isEmpty())

        state =
            state.copy(
                notice = WebDavNotice.Failed(WebDavTestStep.LIST, WebDavTestFailure.UNREACHABLE),
            )
        compose.waitForIdle()
        compose.onNodeWithText(retry).performScrollTo().performClick()

        assertEquals(listOf<WebDavConnectAction>(WebDavConnectAction.Connect), actions)
    }

    @Test
    fun aRejectedPasswordIsSaidInItsField() {
        show(PREVIEW_FILLED.copy(passwordRejected = true))

        compose.onNodeWithText(string(R.string.webdav_error_auth)).assertIsDisplayed()
    }

    @Test
    fun theHostIsConfirmedInAscii() {
        show(
            PREVIEW_FILLED.copy(
                phase = WebDavPhase.CONFIRM_HOST,
                host = "xn--tenda-zsa.example.com",
            ),
        )

        val body = resources.getString(
            R.string.webdav_confirm_host_body,
            "xn--tenda-zsa.example.com",
        )
        compose.onNodeWithText(body).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.webdav_confirm_host_action)).performClick()

        assertEquals(listOf<WebDavConnectAction>(WebDavConnectAction.ConfirmHost), actions)
    }

    @Test
    fun aSuggestionFillsTheAddress() {
        show(WebDavConnectUiState(suggestions = listOf(PREVIEW_SUGGESTION)))

        compose.onNodeWithText(PREVIEW_SUGGESTION.name).performClick()

        assertEquals(
            listOf<WebDavConnectAction>(WebDavConnectAction.PickSuggestion(PREVIEW_SUGGESTION.key)),
            actions,
        )
    }

    @Test
    fun theWindowIsSecureOnlyWhileTheScreenIsShown() {
        var shown by mutableStateOf(true)
        val protection = WindowProtection.of(compose.activity.window)
        compose.setContent {
            CompositionLocalProvider(LocalWindowProtection provides protection) {
                if (shown) SecureWindow()
            }
        }
        compose.waitForIdle()
        val flags = { compose.activity.window.attributes.flags }

        assertTrue(flags() and WindowManager.LayoutParams.FLAG_SECURE != 0)

        shown = false
        compose.waitForIdle()

        assertFalse(flags() and WindowManager.LayoutParams.FLAG_SECURE != 0)
    }

    @Test
    fun leavingTheScreenKeepsTheWindowSecureWhileTheAppLockIsOn() {
        var shown by mutableStateOf(true)
        val protection = WindowProtection.of(compose.activity.window)
        compose.runOnUiThread { protection.lockEnabled(true) }
        compose.setContent {
            CompositionLocalProvider(LocalWindowProtection provides protection) {
                if (shown) SecureWindow()
            }
        }
        compose.waitForIdle()

        shown = false
        compose.waitForIdle()

        val flags = compose.activity.window.attributes.flags
        assertTrue(flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
    }
}
