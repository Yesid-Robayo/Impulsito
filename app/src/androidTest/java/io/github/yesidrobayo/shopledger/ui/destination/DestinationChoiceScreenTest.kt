package io.github.yesidrobayo.shopledger.ui.destination

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationInfo
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `Dónde guardar las copias` en el teléfono: cada opción es un botón de un solo foco, la nube del
 * teléfono no sigue sin `Entendido`, la opción conectada dice `Actual` y `Ahora no` solo aparece sin
 * destino. Nada de esto usa la red.
 */
@RunWith(AndroidJUnit4::class)
class DestinationChoiceScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
    private val slotsTitle = resources.getString(R.string.destination_slots_title)
    private val webDavTitle = resources.getString(R.string.destination_webdav_title)
    private val notNow = resources.getString(R.string.action_not_now)
    private val current = resources.getString(R.string.destination_current_tag)
    private val understood = resources.getString(R.string.destination_slots_gate_action)

    private fun show(describe: suspend () -> BackupDestinationInfo?): DestinationChoiceViewModel {
        val model =
            DestinationChoiceViewModel(describe, false, SlotsHandoff.AUTOMATIC, Dispatchers.Default)
        compose.setContent {
            PreviewTheme {
                val state by model.uiState.collectAsState()
                DestinationChoiceScreen(state, model::onAction)
            }
        }
        compose.waitUntil(WAIT_MS) { model.uiState.value.connected != ConnectedDestination.UNKNOWN }
        compose.waitForIdle()
        return model
    }

    @Test
    fun eachOptionIsOneButtonWithItsWholeText() {
        show { null }
        val button = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)

        val node = compose.onNode(button and hasText(slotsTitle, substring = true))
        val texts = node.fetchSemanticsNode().config[SemanticsProperties.Text].map { it.text }

        assertTrue(texts.contains(resources.getString(R.string.destination_slots_tag)))
        assertTrue(texts.contains(resources.getString(R.string.destination_slots_note)))
        compose.onNode(button and hasText(webDavTitle, substring = true)).assertIsDisplayed()
    }

    @Test
    fun thePhoneCloudNeedsUnderstoodBeforeGoingOn() {
        val model = show { null }

        compose.onNodeWithText(slotsTitle).performClick()
        compose.onNodeWithText(understood).assertIsDisplayed()
        assertEquals(null, model.uiState.value.target)

        compose.onNodeWithText(understood).performClick()
        compose.waitForIdle()

        assertEquals(DestinationChoiceTarget.DOCUMENT_SLOTS, model.uiState.value.target)
    }

    @Test
    fun withoutADestinationNotNowLeaves() {
        val model = show { null }

        compose.onNodeWithText(notNow).performScrollTo().performClick()

        assertEquals(DestinationChoiceTarget.NOT_NOW, model.uiState.value.target)
        assertTrue(compose.onAllNodesWithText(current).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun theConnectedOptionSaysCurrentAndHidesNotNow() {
        val model = show { BackupDestinationInfo.WebDav("copias", "dav.example.com") }

        compose.onNodeWithText(current).assertIsDisplayed()
        assertTrue(compose.onAllNodesWithText(notNow).fetchSemanticsNodes().isEmpty())

        compose.onNodeWithText(webDavTitle).performClick()
        assertEquals(DestinationChoiceTarget.BACKUP, model.uiState.value.target)
    }

    private companion object {
        const val WAIT_MS = 5_000L
    }
}
