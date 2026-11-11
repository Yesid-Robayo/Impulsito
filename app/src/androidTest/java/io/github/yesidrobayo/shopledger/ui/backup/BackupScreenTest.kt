package io.github.yesidrobayo.shopledger.ui.backup

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
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
import io.github.yesidrobayo.shopledger.ui.home.HomeAction
import io.github.yesidrobayo.shopledger.ui.home.HomeScreen
import io.github.yesidrobayo.shopledger.ui.home.previewHomeContent
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * La pantalla del respaldo y su estado en Inicio, en el teléfono: `Respaldar ahora`, la acción sin
 * destino, la lista que falla y se reintenta, el botón apagado mientras sube, y el chip y el banner
 * de Inicio. Con ranuras nunca aparece `Todo guardado`. Datos inventados.
 */
@RunWith(AndroidJUnit4::class)
class BackupScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
    private val actions = mutableListOf<BackupAction>()
    private val homeActions = mutableListOf<HomeAction>()

    private fun text(id: Int) = resources.getString(id)

    private fun show(state: BackupUiState) {
        compose.setContent { PreviewTheme { BackupScreen(state, { actions += it }) } }
    }

    @Test
    fun upToDateShowsItsCopiesAndBacksUpNow() {
        show(previewBackupState(previewOverview(BackupStatus.UP_TO_DATE)))

        compose.onNodeWithText(text(R.string.backup_state_up_to_date_title)).assertIsDisplayed()
        compose.onNodeWithText("negocio@example.com").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.backup_action_now)).performClick()

        assertEquals(listOf<BackupAction>(BackupAction.BackUpNow), actions)
    }

    @Test
    fun withTheCloudOfThePhoneAllSavedNeverAppears() {
        show(
            previewBackupState(
                previewOverview(BackupStatus.HANDED_OFF, BackupDestinationKind.DOCUMENT_SLOTS),
            ),
        )

        compose.onNodeWithText(resources.getString(R.string.backup_state_handed_off, "Drive"))
            .assertIsDisplayed()
        assertTrue(
            compose.onAllNodesWithText(text(R.string.backup_state_up_to_date_title))
                .fetchSemanticsNodes().isEmpty(),
        )
    }

    @Test
    fun withoutADestinationTheActionIsToChooseOne() {
        show(previewBackupState(previewOverview(BackupStatus.NOT_CONNECTED, kind = null)))

        compose.onNodeWithText(text(R.string.backup_action_choose_destination)).performClick()

        assertEquals(listOf<BackupAction>(BackupAction.ChooseDestination), actions)
    }

    @Test
    fun aFailedListIsAnErrorThatCanBeRetried() {
        show(
            previewBackupState(
                previewOverview(BackupStatus.UP_TO_DATE),
                copies = BackupCopyList.Failed,
            ),
        )

        compose.onNodeWithText(
            text(R.string.backup_list_error),
        ).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.action_retry)).performScrollTo().performClick()

        assertEquals(listOf<BackupAction>(BackupAction.RetryCopies), actions)
    }

    @Test
    fun whileUploadingBackUpNowIsOff() {
        show(previewBackupState(previewOverview(BackupStatus.UPLOADING)))

        compose.onNodeWithText(text(R.string.backup_action_now)).assertIsNotEnabled()
    }

    @Test
    fun homeShowsTheChipAndTheBannerThatLeadToTheBackup() {
        val wording = previewBackupState(previewOverview(BackupStatus.STALE, pending = 3)).wording
        compose.setContent {
            PreviewTheme {
                HomeScreen(previewHomeContent(), { homeActions += it }, backup = wording)
            }
        }

        compose.onNodeWithText(text(R.string.backup_action_review)).performClick()

        assertEquals(listOf<HomeAction>(HomeAction.OpenBackup), homeActions)
    }

    @Test
    fun homeWithoutADestinationLeadsToChooseOne() {
        val wording = previewBackupState(
            previewOverview(BackupStatus.NOT_CONNECTED, kind = null),
        ).wording
        compose.setContent {
            PreviewTheme {
                HomeScreen(previewHomeContent(), { homeActions += it }, backup = wording)
            }
        }

        compose.onNodeWithText(text(R.string.backup_action_choose_destination)).performClick()

        assertEquals(listOf<HomeAction>(HomeAction.ChooseDestination), homeActions)
    }
}
