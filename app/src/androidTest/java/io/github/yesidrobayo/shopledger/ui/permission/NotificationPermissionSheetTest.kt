package io.github.yesidrobayo.shopledger.ui.permission

import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * La hoja del permiso de avisos al terminar la configuración inicial: explica para qué sirven los
 * avisos, con su botón, y sale una sola vez. Sin permiso que pedir no sale. No toca el diálogo del
 * sistema: la hoja se cierra sin seguir.
 */
@RunWith(AndroidJUnit4::class)
class NotificationPermissionSheetTest {
    @get:Rule
    val compose = createComposeRule()

    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources

    private var asked = false

    private fun show(needsAsking: Boolean): () -> NotificationPermissionPrompt {
        var created: NotificationPermissionPrompt? = null
        compose.setContent {
            val scope = rememberCoroutineScope()
            val prompt =
                created ?: NotificationPermissionPrompt(
                    needsAsking = { needsAsking },
                    wasAsked = { asked },
                    markAsked = {
                        asked = true
                        true
                    },
                    scope = scope,
                ).also { created = it }
            PreviewTheme { NotificationPermissionHost(prompt) }
        }
        compose.waitForIdle()
        return { requireNotNull(created) }
    }

    @Test
    fun afterTheSetupTheSheetExplainsTheAlertsOnce() {
        val prompt = show(needsAsking = true)

        compose.runOnIdle { prompt().offerOnce() }
        compose.waitForIdle()

        compose
            .onNodeWithText(resources.getString(R.string.permission_notifications_rationale))
            .assertIsDisplayed()
        compose
            .onNodeWithText(resources.getString(R.string.permission_notifications_action))
            .assertIsDisplayed()
        assertTrue(asked)

        // Cerrada, no vuelve aunque se pida otra vez sola.
        compose.runOnIdle {
            prompt().dismiss()
            prompt().offerOnce()
        }
        compose.waitForIdle()
        assertTrue(
            compose
                .onAllNodesWithText(
                    resources.getString(R.string.permission_notifications_rationale),
                )
                .fetchSemanticsNodes()
                .isEmpty(),
        )
    }

    @Test
    fun withoutAPermissionToAskNoSheetAppears() {
        val prompt = show(needsAsking = false)

        compose.runOnIdle { prompt().offerOnce() }
        compose.waitForIdle()

        assertTrue(
            compose
                .onAllNodesWithText(
                    resources.getString(R.string.permission_notifications_rationale),
                )
                .fetchSemanticsNodes()
                .isEmpty(),
        )
    }
}
