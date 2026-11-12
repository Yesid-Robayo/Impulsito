package io.github.yesidrobayo.shopledger.ui.backup

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupCopyList
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationKind
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupStatus
import io.github.yesidrobayo.shopledger.domain.model.settings.MeteredBackupPolicy
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Los ajustes de la pantalla del respaldo en el teléfono: `Copias con datos móviles` y `Subido este
 * mes` solo con WebDAV, la hoja con la opción elegida, la advertencia de `Solo con Wi-Fi`, y
 * `Liberar copia` solo en la hoja de una copia fijada, con su pregunta. Datos inventados.
 */
@RunWith(AndroidJUnit4::class)
class BackupSettingsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
    private val actions = mutableListOf<BackupAction>()

    private fun text(id: Int) = resources.getString(id)

    private fun show(state: BackupUiState) {
        compose.setContent { PreviewTheme { BackupScreen(state, { actions += it }) } }
    }

    private fun absent(id: Int) = assertTrue(
        compose.onAllNodesWithText(text(id)).fetchSemanticsNodes().isEmpty(),
    )

    @Test
    fun withWebDavTheMeteredPolicyAndTheMonthUsageAreShown() {
        show(previewBackupState(previewOverview(BackupStatus.UP_TO_DATE)))

        compose.onNodeWithText(text(R.string.backup_metered_row)).performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText(text(R.string.backup_uploaded_month_label)).performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText(text(R.string.backup_metered_every_60)).performScrollTo()
            .performClick()

        assertEquals(listOf<BackupAction>(BackupAction.OpenMeteredPolicy), actions)
    }

    @Test
    fun withTheCloudOfThePhoneNoneOfTheSettingsAppear() {
        show(
            previewBackupState(
                previewOverview(BackupStatus.HANDED_OFF, BackupDestinationKind.DOCUMENT_SLOTS),
            ),
        )

        absent(R.string.backup_metered_row)
        absent(R.string.backup_uploaded_month_label)
        absent(R.string.backup_metered_every_60)
    }

    @Test
    fun theSheetMarksTheCurrentPolicyAndPicksAnother() {
        show(
            previewBackupState(previewOverview(BackupStatus.UP_TO_DATE))
                .copy(metered = MeteredUi.Choosing),
        )

        compose.onNodeWithText(text(R.string.backup_metered_help)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.backup_metered_wifi_only)).performClick()

        assertEquals(
            listOf<BackupAction>(BackupAction.PickMeteredPolicy(MeteredBackupPolicy.WIFI_ONLY)),
            actions,
        )
    }

    @Test
    fun wifiOnlyAsksToConfirmWithItsWarning() {
        show(
            previewBackupState(previewOverview(BackupStatus.UP_TO_DATE))
                .copy(metered = MeteredUi.ConfirmWifiOnly),
        )

        compose.onNodeWithText(text(R.string.dialog_wifi_only_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.dialog_wifi_only_body)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.dialog_wifi_only_action)).performClick()

        assertEquals(listOf<BackupAction>(BackupAction.ConfirmWifiOnly), actions)
    }

    @Test
    fun thePinnedCopySheetOffersToReleaseIt() {
        val state = previewBackupState(previewOverview(BackupStatus.UP_TO_DATE))
        val pinned =
            (state.copies as BackupCopyList.Listed).copies.single { it.pinned }
        show(state.copy(restore = RestoreUi.CopySheet(pinned)))

        val app = text(R.string.app_name)
        compose.onNodeWithText(resources.getString(R.string.backup_pinned_help, app))
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.backup_action_release)).performScrollTo()
            .performClick()

        assertEquals(listOf<BackupAction>(BackupAction.ReleaseCopy(pinned.key)), actions)
    }

    @Test
    fun aCopyThatIsNotPinnedHasNoRelease() {
        val state = previewBackupState(previewOverview(BackupStatus.UP_TO_DATE))
        val loose = (state.copies as BackupCopyList.Listed).copies.first { !it.pinned }
        show(state.copy(restore = RestoreUi.CopySheet(loose)))

        compose.onNodeWithText(text(R.string.backup_action_restore)).assertIsDisplayed()
        absent(R.string.backup_action_release)
    }

    @Test
    fun releasingAsksFirst() {
        val state = previewBackupState(previewOverview(BackupStatus.UP_TO_DATE))
        val pinned = (state.copies as BackupCopyList.Listed).copies.single { it.pinned }
        show(state.copy(release = ReleaseUi.Confirm(pinned)))

        compose.onNodeWithText(text(R.string.dialog_release_copy_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.backup_action_release)).performClick()
        compose.onNodeWithText(text(R.string.action_cancel)).performClick()

        assertEquals(
            listOf<BackupAction>(BackupAction.ConfirmRelease, BackupAction.CloseRelease),
            actions,
        )
    }

    @Test
    fun whileReleasingTheQuestionCannotBeConfirmedTwice() {
        val state = previewBackupState(previewOverview(BackupStatus.UP_TO_DATE))
        val pinned = (state.copies as BackupCopyList.Listed).copies.single { it.pinned }
        show(state.copy(release = ReleaseUi.Working(pinned)))

        compose.onNodeWithText(text(R.string.backup_action_release)).performClick()

        assertEquals(emptyList<BackupAction>(), actions)
    }

    @Test
    fun theSelectedOptionIsReadAsSelected() {
        show(
            previewBackupState(previewOverview(BackupStatus.UP_TO_DATE))
                .copy(metered = MeteredUi.Choosing),
        )

        compose.onNode(hasText(text(R.string.backup_metered_every_60)) and isSelectable())
            .assertIsSelected()
        compose.onNode(hasText(text(R.string.backup_metered_every_30)) and isSelectable())
            .assertIsNotSelected()
        compose.onNode(hasText(text(R.string.backup_metered_wifi_only)) and isSelectable())
            .assertIsNotSelected()
    }
}
