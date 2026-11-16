package io.github.yesidrobayo.shopledger.ui.backup

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupStatus
import io.github.yesidrobayo.shopledger.domain.model.backup.BeforeUpdateCopy
import io.github.yesidrobayo.shopledger.domain.model.backup.UndoRestoreCopy
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Volver a como estaba antes de restaurar, en el teléfono: la acción está en la pantalla del
 * respaldo solo con la copia local de seguridad vigente, su toque pide confirmar, y el diálogo
 * dice que lo registrado después no estará. Datos inventados.
 */
@RunWith(AndroidJUnit4::class)
class BackupUndoRestoreScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
    private val actions = mutableListOf<BackupAction>()
    private val safetyCopy = UndoRestoreCopy(Instant.parse("2026-11-24T21:38:00Z"))
    private val upToDate = previewBackupState(previewOverview(BackupStatus.UP_TO_DATE))

    private fun text(id: Int) = resources.getString(id)

    private fun show(state: BackupUiState) {
        compose.setContent { PreviewTheme { BackupScreen(state, { actions += it }) } }
    }

    /** La lista solo compone lo que está cerca de la vista: primero se desplaza hasta [label]. */
    private fun scrollTo(label: String) {
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(label))
    }

    @Test
    fun withARecentSafetyCopyTheActionIsThereAndAsksToGoBack() {
        show(upToDate.copy(undoRestore = safetyCopy))

        val label = text(R.string.backup_undo_restore_label)
        scrollTo(label)
        compose.onNodeWithText(label).assertIsDisplayed().performClick()

        assertEquals(listOf<BackupAction>(BackupAction.UndoRestore), actions)
    }

    @Test
    fun withoutASafetyCopyTheActionIsNotThere() {
        show(upToDate)

        // Probar restauración va justo antes: si la acción existiera, ya estaría compuesta.
        scrollTo(text(R.string.backup_drill_action))

        compose.onAllNodesWithText(text(R.string.backup_undo_restore_label)).assertCountEquals(0)
    }

    @Test
    fun goingBackBeforeRestoringIsApartFromGoingBackBeforeTheUpdate() {
        show(
            upToDate.copy(
                undoRestore = safetyCopy,
                beforeUpdate = BeforeUpdateCopy(Instant.parse("2026-11-01T10:00:00Z")),
            ),
        )

        val restoreLabel = text(R.string.backup_undo_restore_label)
        val updateLabel = text(R.string.backup_undo_migration_label)
        scrollTo(restoreLabel)
        compose.onNodeWithText(restoreLabel).performClick()
        scrollTo(updateLabel)
        compose.onNodeWithText(updateLabel).performClick()

        assertEquals(listOf(BackupAction.UndoRestore, BackupAction.UndoUpdate), actions)
    }

    @Test
    fun theConfirmationSaysLaterRecordsWillBeMissingAndThenStarts() {
        show(
            upToDate.copy(
                undoRestore = safetyCopy,
                restore = RestoreUi.ConfirmUndoRestore(safetyCopy),
            ),
        )

        compose.onNodeWithText(text(R.string.dialog_undo_restore_title)).assertIsDisplayed()
        // Lo que sigue a la fecha, que depende del idioma y la zona del teléfono.
        val afterDate =
            resources
                .getString(R.string.dialog_undo_restore_body, DATE_MARK)
                .substringAfter(DATE_MARK)
        compose.onNodeWithText(afterDate, substring = true).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.dialog_go_back_action)).performClick()

        assertEquals(listOf<BackupAction>(BackupAction.StartRestore), actions)
    }

    @Test
    fun goingBackShowsTheSameProgressThatOnlyLetsCancel() {
        show(upToDate.copy(restore = RestoreUi.Working(RestoreOrigin.UNDO_RESTORE)))

        compose.onNodeWithText(text(R.string.restore_stage_checking)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.action_cancel)).performClick()

        assertEquals(listOf<BackupAction>(BackupAction.CancelRestore), actions)
    }

    private companion object {
        const val DATE_MARK = "#FECHA#"
    }
}
