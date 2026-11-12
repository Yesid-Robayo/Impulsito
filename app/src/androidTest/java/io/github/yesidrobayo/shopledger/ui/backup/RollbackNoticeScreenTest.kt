package io.github.yesidrobayo.shopledger.ui.backup

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupCopyList
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupStatus
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Los avisos del antirretroceso en la pantalla del respaldo: faltan copias más recientes sobre la
 * lista, y en la hoja de cada copia si se comprueba con el código, si es una copia en claro más
 * nueva que la última cifrada y si este teléfono no tiene referencia. Solo avisan: `Restaurar`
 * sigue ahí. Datos inventados.
 */
@RunWith(AndroidJUnit4::class)
class RollbackNoticeScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources

    private fun text(id: Int) = resources.getString(id)

    private val upToDate = previewBackupState(previewOverview(BackupStatus.UP_TO_DATE))
    private val listed = upToDate.copies as BackupCopyList.Listed
    private val first = listed.copies.first()

    private fun show(state: BackupUiState) {
        compose.setContent { PreviewTheme { BackupScreen(state, {}) } }
    }

    private fun count(id: Int) = compose.onAllNodesWithText(text(id)).fetchSemanticsNodes().size

    @Test
    fun missingNewerCopiesAreWarnedAboveTheList() {
        show(upToDate.copy(copies = listed.copy(missingNewer = true)))

        val notice = text(R.string.recovery_missing_newer)
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(notice, substring = true))
        compose.onNodeWithText(notice, substring = true).assertIsDisplayed()
    }

    @Test
    fun withoutMissingCopiesThereIsNoNotice() {
        show(upToDate)
        assertEquals(
            0,
            compose
                .onAllNodesWithText(text(R.string.recovery_missing_newer), substring = true)
                .fetchSemanticsNodes()
                .size,
        )
    }

    @Test
    fun anEncryptedCopyIsCheckedWithTheCode() {
        show(upToDate.copy(restore = RestoreUi.CopySheet(first.copy(encrypted = true))))

        compose.onNodeWithText(text(R.string.recovery_origin_verified)).assertIsDisplayed()
        assertEquals(0, count(R.string.recovery_origin_unverified))
        assertEquals(0, count(R.string.recovery_plain_newer_warning))
        compose.onNodeWithText(text(R.string.backup_action_restore)).assertIsDisplayed()
    }

    @Test
    fun aPlainCopyNewerThanTheLastEncryptedOneIsWarnedButCanBeRestored() {
        val plain = first.copy(encrypted = false, plainNewerThanEncrypted = true)
        show(upToDate.copy(restore = RestoreUi.CopySheet(plain)))

        compose.onNodeWithText(text(R.string.recovery_origin_unverified)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.recovery_plain_newer_warning)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.backup_action_restore)).assertIsDisplayed()
    }

    @Test
    fun anEncryptedCopyIsTaggedInTheList() {
        val encrypted = listed.copy(copies = listOf(first.copy(encrypted = true)))
        show(upToDate.copy(copies = encrypted))

        val tag = text(R.string.backup_copy_encrypted)
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(tag, substring = true))
        compose.onNodeWithText(tag, substring = true).assertIsDisplayed()
    }

    @Test
    fun aPhoneWithoutAnAnchorSaysSo() {
        show(upToDate.copy(restore = RestoreUi.CopySheet(first.copy(anchorKnown = false))))

        compose.onNodeWithText(text(R.string.recovery_no_anchor)).assertIsDisplayed()
    }
}
