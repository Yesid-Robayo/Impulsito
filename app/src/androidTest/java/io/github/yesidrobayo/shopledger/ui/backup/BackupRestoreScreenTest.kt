package io.github.yesidrobayo.shopledger.ui.backup

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupCopyList
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupStatus
import io.github.yesidrobayo.shopledger.domain.model.backup.RestoreAnswer
import io.github.yesidrobayo.shopledger.domain.model.backup.RestoreProgress
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Restaurar en la pantalla del respaldo, en el teléfono: tocar una copia abre su hoja, `Buscar un
 * archivo` está también sin destino, el progreso solo deja cancelar, una pregunta se contesta y un
 * archivo que no abre pide probar otro. Datos inventados.
 */
@RunWith(AndroidJUnit4::class)
class BackupRestoreScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
    private val actions = mutableListOf<BackupAction>()

    private fun text(id: Int) = resources.getString(id)

    private fun show(state: BackupUiState) {
        compose.setContent { PreviewTheme { BackupScreen(state, { actions += it }) } }
    }

    private val upToDate = previewBackupState(previewOverview(BackupStatus.UP_TO_DATE))
    private val first = (upToDate.copies as BackupCopyList.Listed).copies.first()

    @Test
    fun tappingACopyOpensItsSheetAndFindFileIsThere() {
        show(upToDate)

        compose.onNodeWithText(text(R.string.backup_most_recent), substring = true)
            .performScrollTo()
            .performClick()
        // La lista solo compone lo que está cerca de la vista: primero se desplaza hasta la fila.
        val findFile = text(R.string.backup_action_find_file)
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(findFile))
        compose.onNodeWithText(findFile).performClick()

        assertEquals(listOf(BackupAction.OpenCopy(first.key), BackupAction.FindFile), actions)
    }

    @Test
    fun withoutADestinationAFileCanStillBeFound() {
        show(previewBackupState(previewOverview(BackupStatus.NOT_CONNECTED, kind = null)))

        compose.onNodeWithText(text(R.string.backup_action_find_file))
            .performScrollTo()
            .performClick()

        assertEquals(listOf<BackupAction>(BackupAction.FindFile), actions)
    }

    @Test
    fun theSheetAsksToRestore() {
        show(upToDate.copy(restore = RestoreUi.CopySheet(first)))

        compose.onNodeWithText(text(R.string.backup_action_restore)).performClick()

        assertEquals(listOf<BackupAction>(BackupAction.ConfirmCopy(first.key)), actions)
    }

    @Test
    fun theDialogConfirmsBeforeStarting() {
        show(upToDate.copy(restore = RestoreUi.ConfirmCopy(first)))

        compose.onNodeWithText(text(R.string.dialog_restore_action)).performClick()

        assertEquals(listOf<BackupAction>(BackupAction.StartRestore), actions)
    }

    @Test
    fun theProgressOnlyLetsCancel() {
        show(upToDate.copy(restore = RestoreUi.Working(RestoreOrigin.FILE)))

        compose.onNodeWithText(text(R.string.restore_stage_checking)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.action_cancel)).performClick()

        assertEquals(listOf<BackupAction>(BackupAction.CancelRestore), actions)
    }

    @Test
    fun anEmptyCopyIsReplacedOnlyWhenConfirmed() {
        show(
            upToDate.copy(
                restore = RestoreUi.Asking(RestoreProgress.AskEmptyCopy, RestoreOrigin.FILE),
            ),
        )

        compose.onNodeWithText(text(R.string.dialog_restore_empty_action)).performClick()

        assertEquals(
            listOf<BackupAction>(BackupAction.Answer(RestoreAnswer.ReplaceWithEmptyCopy)),
            actions,
        )
    }

    @Test
    fun aFileThatDoesNotOpenAsksToTryAnother() {
        show(
            upToDate.copy(
                restore = RestoreUi.Stopped(RestoreNotice.TRY_ANOTHER_FILE, RestoreOrigin.FILE),
            ),
        )

        compose.onNodeWithText(text(R.string.restore_error_slot_try_next)).assertIsDisplayed()
        compose.onAllNodesWithText(text(R.string.backup_action_find_file))[0].performClick()

        assertEquals(listOf<BackupAction>(BackupAction.FindFile), actions)
    }
}
