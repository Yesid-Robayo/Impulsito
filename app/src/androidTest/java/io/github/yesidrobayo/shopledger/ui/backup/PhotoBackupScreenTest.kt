package io.github.yesidrobayo.shopledger.ui.backup

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToNodeAction
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
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationKind
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupStatus
import io.github.yesidrobayo.shopledger.domain.model.backup.PhotoBackupFacts
import io.github.yesidrobayo.shopledger.domain.model.backup.PhotoDestination
import io.github.yesidrobayo.shopledger.ui.home.HomeAction
import io.github.yesidrobayo.shopledger.ui.home.HomeScreen
import io.github.yesidrobayo.shopledger.ui.home.previewHomeContent
import io.github.yesidrobayo.shopledger.ui.localcopies.LocalCopiesAction
import io.github.yesidrobayo.shopledger.ui.localcopies.LocalCopiesScreen
import io.github.yesidrobayo.shopledger.ui.localcopies.LocalCopiesUiState
import io.github.yesidrobayo.shopledger.ui.localcopies.PhotosFailure
import io.github.yesidrobayo.shopledger.ui.localcopies.PhotosUi
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Las fotos fuera de la copia, en el teléfono: el bloque `Fotos` del respaldo con WebDAV y con
 * ranuras, la línea de Inicio que nunca cambia el chip, y `Exportar fotos` en `Copias locales`.
 * Datos inventados.
 */
@RunWith(AndroidJUnit4::class)
class PhotoBackupScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
    private val backupActions = mutableListOf<BackupAction>()
    private val homeActions = mutableListOf<HomeAction>()
    private val copyActions = mutableListOf<LocalCopiesAction>()

    private fun text(id: Int) = resources.getString(id)

    /** La lista del respaldo es perezosa: un bloque fuera de la pantalla todavía no existe. */
    private fun shown(value: String) {
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(value))
        compose.onNodeWithText(value).assertIsDisplayed()
    }

    private fun facts(destination: PhotoDestination, pending: Int = 0, over: Boolean = false) =
        PhotoBackupFacts(
            destination = destination,
            pending = pending,
            pendingOverADay = pending,
            stoppedForSpace = over,
            totalBytes = if (over) PhotoBackupFacts.ONE_GIB else 0,
            exportable = pending,
        )

    @Test
    fun withWebDavTheBackupSaysWhatIsMissingWithoutTouchingTheStatus() {
        val state =
            previewBackupState(previewOverview(BackupStatus.UP_TO_DATE))
                .copy(photos = facts(PhotoDestination.WEBDAV, pending = 12, over = true))
        compose.setContent { PreviewTheme { BackupScreen(state, { backupActions += it }) } }

        compose.onNodeWithText(text(R.string.backup_state_up_to_date_title)).assertIsDisplayed()
        shown(resources.getString(R.string.storage_photos_pending, 12))
        shown(text(R.string.backup_photos_space_stopped))
        shown(text(R.string.backup_photos_large))
    }

    @Test
    fun withSlotsThereIsNoCountAndPhotosCanBeExported() {
        val state =
            previewBackupState(
                previewOverview(BackupStatus.HANDED_OFF, BackupDestinationKind.DOCUMENT_SLOTS),
            ).copy(photos = facts(PhotoDestination.SLOTS, pending = 12))
        compose.setContent { PreviewTheme { BackupScreen(state, { backupActions += it }) } }

        shown(resources.getString(R.string.backup_slots_photos_note, "Drive"))
        assertTrue(
            compose.onAllNodesWithText(resources.getString(R.string.storage_photos_pending, 12))
                .fetchSemanticsNodes().isEmpty(),
        )
        shown(text(R.string.offsite_export_photos_action))
        compose.onNodeWithText(text(R.string.offsite_export_photos_action)).performClick()

        assertEquals(listOf<BackupAction>(BackupAction.OpenLocalCopies), backupActions)
    }

    @Test
    fun homeShowsTheLineUnderTheChipAndItOpensTheBackup() {
        val wording = previewBackupState(previewOverview(BackupStatus.UP_TO_DATE)).wording
        compose.setContent {
            PreviewTheme {
                HomeScreen(
                    previewHomeContent(),
                    { homeActions += it },
                    backup = wording,
                    photosPending = 3,
                )
            }
        }

        compose.onNodeWithText(resources.getString(R.string.storage_photos_pending, 3))
            .performClick()

        assertEquals(listOf<HomeAction>(HomeAction.OpenBackup), homeActions)
    }

    @Test
    fun localCopiesExportsPhotosOrSaysThereAreNone() {
        var state by mutableStateOf(LocalCopiesUiState(photos = PhotosUi(exportable = 4)))
        compose.setContent { PreviewTheme { LocalCopiesScreen(state, { copyActions += it }) } }

        compose.onNodeWithText(text(R.string.offsite_export_photos_action)).performScrollTo()
            .performClick()
        assertEquals(listOf<LocalCopiesAction>(LocalCopiesAction.ExportPhotos), copyActions)

        state = LocalCopiesUiState(photos = PhotosUi(exportable = 4, failure = PhotosFailure.OTHER))
        compose.onNodeWithText(text(R.string.offsite_photos_failed)).performScrollTo()
            .assertIsDisplayed()

        state = LocalCopiesUiState(photos = PhotosUi(exportable = 0))
        compose.onNodeWithText(text(R.string.offsite_no_photos)).performScrollTo()
            .assertIsDisplayed()
        assertTrue(
            compose.onAllNodesWithText(text(R.string.offsite_export_photos_action))
                .fetchSemanticsNodes().isEmpty(),
        )
    }
}
