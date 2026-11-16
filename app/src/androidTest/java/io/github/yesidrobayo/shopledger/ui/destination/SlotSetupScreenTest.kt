package io.github.yesidrobayo.shopledger.ui.destination

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `Preparar archivos en la nube` en el teléfono: cada archivo es un solo foco con su número, su
 * nombre y su estado; la acción cambia con los tres archivos; cada aviso lleva su salida; y otro
 * destino guardado se dice. Nada de esto usa la red ni abre el selector.
 */
@RunWith(AndroidJUnit4::class)
class SlotSetupScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
    private val appName = resources.getString(R.string.app_name)
    private val create = resources.getString(R.string.slots_create_action)
    private val continueText = resources.getString(R.string.action_continue)

    private fun show(state: SlotSetupUiState): MutableList<SlotSetupAction> {
        val actions = mutableListOf<SlotSetupAction>()
        compose.setContent {
            PreviewTheme { SlotSetupScreen(state, appName, onAction = { actions += it }) }
        }
        compose.waitForIdle()
        return actions
    }

    @Test
    fun eachFileIsOneFocusWithNumberNameAndStatus() {
        show(SlotSetupSamples.halfway)

        // Con la fila fundida, el número, el nombre y el estado están en un solo nodo.
        compose.onNode(
            hasText(resources.getString(R.string.slots_file_row, 2, 3)) and
                hasText(PENDING_NAME_B) and
                hasText(resources.getString(R.string.slots_file_pending)),
        ).assertIsDisplayed()
        compose.onNode(
            hasText(resources.getString(R.string.slots_file_row, 1, 3)) and
                hasText(resources.getString(R.string.slots_file_created)),
        ).assertIsDisplayed()
    }

    @Test
    fun theMainActionCreatesTheNextFile() {
        val actions = show(SlotSetupSamples.empty)

        compose.onNodeWithText(create).performClick()

        assertEquals(listOf<SlotSetupAction>(SlotSetupAction.CreateFile), actions)
    }

    @Test
    fun withTheFilesReadyTheMainActionContinues() {
        val actions = show(SlotSetupSamples.ready)

        compose.onNodeWithText(resources.getString(R.string.slots_ready_title)).assertIsDisplayed()
        compose.onNodeWithText(continueText).performClick()

        assertEquals(listOf<SlotSetupAction>(SlotSetupAction.Continue), actions)
        assertTrue(compose.onAllNodesWithText(create).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun theMissingFileNoticeLeavesOnceTheNewFileExists() {
        val intro = resources.getString(R.string.slots_conflict_intro, PENDING_NAME_B, "Drive")
        var state by mutableStateOf(SlotSetupSamples.keepPhone)
        compose.setContent {
            PreviewTheme { SlotSetupScreen(state, appName, onAction = {}) }
        }

        compose.onNodeWithText(intro).assertIsDisplayed()

        state = SlotSetupSamples.keptPhone
        compose.waitForIdle()

        // Con los tres archivos, decir que falta uno contradice a `Archivos listos`.
        compose.onNodeWithText(resources.getString(R.string.slots_ready_title)).assertIsDisplayed()
        assertTrue(compose.onAllNodesWithText(intro).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun anotherCloudOffersAServiceAndLocalCopies() {
        val actions = show(SlotSetupSamples.outside)

        compose.onNodeWithText(resources.getString(R.string.slots_error_service_action))
            .performScrollTo()
            .performClick()
        compose.onNodeWithText(resources.getString(R.string.slots_error_local_action))
            .performScrollTo()
            .performClick()

        assertEquals(
            listOf(SlotSetupAction.ConnectService, SlotSetupAction.GoToLocalCopies),
            actions,
        )
    }

    @Test
    fun aFileWithDataOnlyOffersAnotherFile() {
        val actions = show(SlotSetupSamples.notEmpty)

        compose.onNodeWithText(resources.getString(R.string.slots_other_file_action))
            .performScrollTo()
            .performClick()

        assertEquals(listOf<SlotSetupAction>(SlotSetupAction.CreateAnotherFile), actions)
    }

    @Test
    fun anotherDestinationAsksBeforeChanging() {
        val actions = show(SlotSetupSamples.otherDestination)

        compose.onNodeWithText(
            resources.getString(R.string.destination_change_body, "dav.example.com", appName),
        ).assertIsDisplayed()
        compose.onNodeWithText(resources.getString(R.string.destination_change_action))
            .performClick()

        assertEquals(listOf<SlotSetupAction>(SlotSetupAction.ConfirmChange), actions)
    }

    private companion object {
        const val PENDING_NAME_B = "shopledger-backup-b.slcopy"
    }
}
