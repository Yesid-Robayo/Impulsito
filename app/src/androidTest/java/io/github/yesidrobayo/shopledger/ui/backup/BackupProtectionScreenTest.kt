package io.github.yesidrobayo.shopledger.ui.backup

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationKind
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupStatus
import io.github.yesidrobayo.shopledger.domain.model.settings.MeteredBackupPolicy
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `Qué tan protegidos están los datos` en el teléfono: los límites según el destino y la política
 * con datos móviles, y el aviso de compartir la carpeta, que dice si las copias van cifradas o en
 * claro y que la app no sabe si está compartida. Sin destino no aparece. Datos inventados.
 */
@RunWith(AndroidJUnit4::class)
class BackupProtectionScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
    private val app = resources.getString(R.string.app_name)

    private fun text(id: Int) = resources.getString(id)

    private fun show(state: BackupUiState) {
        compose.setContent { PreviewTheme { BackupScreen(state, {}) } }
    }

    // La sección va al final de una lista perezosa: hay que desplazar la lista hasta componerla.
    private fun seen(value: String) {
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(value))
        compose.onNodeWithText(value).assertIsDisplayed()
    }

    private fun absent(value: String) = assertTrue(
        compose.onAllNodesWithText(value).fetchSemanticsNodes().isEmpty(),
    )

    @Test
    fun withWebDavInClearTheSharedFolderExposesEverything() {
        show(previewBackupState(previewOverview(BackupStatus.UP_TO_DATE)))

        seen(text(R.string.backup_limits_title))
        seen(text(R.string.backup_limits_every_60_min))
        seen(resources.getString(R.string.backup_shared_notice, app))
        absent(resources.getString(R.string.backup_shared_notice_encrypted, app))
    }

    @Test
    fun encryptedCopiesSayWhoCouldOpenThem() {
        show(
            previewBackupState(previewOverview(BackupStatus.UP_TO_DATE))
                .copy(copiesEncrypted = true),
        )

        seen(resources.getString(R.string.backup_shared_notice_encrypted, app))
        absent(resources.getString(R.string.backup_shared_notice, app))
    }

    @Test
    fun theLimitsFollowTheMeteredPolicy() {
        val state = previewBackupState(previewOverview(BackupStatus.UP_TO_DATE))
        val wording = requireNotNull(state.wording)
        show(
            state.copy(
                wording =
                    wording.copy(
                        overview = wording.overview.copy(
                            meteredPolicy = MeteredBackupPolicy.WIFI_ONLY,
                        ),
                    ),
            ),
        )

        seen(text(R.string.backup_limits_wifi_only))
        absent(text(R.string.backup_limits_every_60_min))
    }

    @Test
    fun withTheCloudOfThePhoneTheLimitsAreThoseOfThatCloud() {
        show(
            previewBackupState(
                previewOverview(BackupStatus.HANDED_OFF, BackupDestinationKind.DOCUMENT_SLOTS),
            ),
        )

        seen(resources.getString(R.string.backup_limits_slots, "Drive"))
        seen(resources.getString(R.string.backup_shared_notice, app))
    }

    @Test
    fun withoutADestinationThereIsNothingToShare() {
        show(previewBackupState(previewOverview(BackupStatus.NOT_CONNECTED, kind = null)))

        absent(text(R.string.backup_limits_title))
        absent(resources.getString(R.string.backup_shared_notice, app))
    }
}
