package io.github.yesidrobayo.shopledger.ui.rescue

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupCopyList
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationKind
import io.github.yesidrobayo.shopledger.domain.model.backup.BeforeUpdateCopy
import io.github.yesidrobayo.shopledger.ui.backup.BackupAction
import io.github.yesidrobayo.shopledger.ui.backup.RestoreUi
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * La pantalla de rescate en el teléfono: `No desinstalar` siempre, la acción principal según la
 * causa y el destino guardado, el texto de cada causa, `Información para soporte` sin datos del
 * negocio y la pantalla de `Actualizando los datos`. Datos inventados.
 */
@RunWith(AndroidJUnit4::class)
class RescueScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val app = context.getString(R.string.app_name)
    private val actions = mutableListOf<RescueAction>()

    private fun text(id: Int) = context.getString(id)

    private fun show(
        state: RescueUiState,
        support: List<SupportLine> = emptyList(),
        crashLog: String? = null,
    ) {
        compose.setContent {
            PreviewTheme {
                RescueScreen(state, support, onAction = { actions += it }, crashLog = crashLog)
            }
        }
    }

    @Test
    fun aDamagedDatabaseSaysDoNotUninstallAndRestoreLeads() {
        show(RescueUiState(RescueReason.OpenFailed, BackupDestinationKind.WEBDAV))

        compose.onNodeWithText(text(R.string.rescue_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.rescue_detail_missing)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.rescue_warning, app)).assertIsDisplayed()
        compose.onNodeWithText(
            text(R.string.backup_action_restore),
        ).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.backup_action_find_file)).performScrollTo()
            .performClick()
        compose.onNodeWithText(text(R.string.settings_support)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.language_title)).performScrollTo().performClick()

        assertEquals(
            listOf(
                RescueAction.ShowCopies,
                RescueAction.Restore(BackupAction.FindFile),
                RescueAction.ShowSupport,
                RescueAction.ShowLanguage,
            ),
            actions,
        )
    }

    @Test
    fun withoutASavedDestinationFindingAFileIsTheOnlyWayToRestore() {
        show(RescueUiState(RescueReason.DatabaseMissing, destinationKind = null))

        compose.onNodeWithText(text(R.string.backup_action_find_file)).performScrollTo()
            .performClick()
        compose.onNodeWithText(text(R.string.backup_action_restore)).assertDoesNotExist()
        assertEquals(listOf<RescueAction>(RescueAction.Restore(BackupAction.FindFile)), actions)
    }

    @Test
    fun aFailedMigrationSaysTheDataIsAsItWasAndToWaitForAFix() {
        show(RescueUiState(RescueReason.MigrationFailed, BackupDestinationKind.DOCUMENT_SLOTS))

        compose.onNodeWithText(text(R.string.rescue_detail_migration)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.rescue_no_premigration, app))
            .assertIsDisplayed()
    }

    @Test
    fun withoutSpaceItSaysHowMuchToFreeAndRetryLeads() {
        show(RescueUiState(RescueReason.NotEnoughSpace(50_000_000), BackupDestinationKind.WEBDAV))

        compose.onNodeWithText(text(R.string.rescue_no_space_title)).assertIsDisplayed()
        compose.onNodeWithText("50", substring = true).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.action_retry)).performScrollTo().performClick()
        assertEquals(listOf<RescueAction>(RescueAction.Retry), actions)
    }

    @Test
    fun anUnreadableJournalStillOffersToRestore() {
        show(RescueUiState(RescueReason.RestoreJournalUnreadable, BackupDestinationKind.WEBDAV))

        compose.onNodeWithText(text(R.string.rescue_detail_journal)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.backup_action_restore)).performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun theDamagedOriginalIsSharedOnlyAfterTheWarning() {
        show(
            RescueUiState(
                RescueReason.OpenFailed,
                BackupDestinationKind.WEBDAV,
                damagedAvailable = true,
                sharing = true,
            ),
        )

        compose.onNodeWithText(text(R.string.dialog_share_damaged_body)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.action_share)).performClick()
        assertEquals(listOf<RescueAction>(RescueAction.ConfirmShareDamaged), actions)
    }

    @Test
    fun withoutInternetTheSheetOffersToFindAFile() {
        show(
            RescueUiState(
                RescueReason.OpenFailed,
                BackupDestinationKind.WEBDAV,
                copiesOpen = true,
                copies = BackupCopyList.NoInternet,
            ),
        )

        compose.onNodeWithText(text(R.string.rescue_no_internet)).assertIsDisplayed()
    }

    @Test
    fun supportShowsOnlyTechnicalFactsAndCopies() {
        val facts =
            SupportFacts(
                appVersion = "1.0.0 (1)",
                android = "14 (34)",
                device = "Fabricante Modelo",
                lastError = RescueReason.OpenFailed.code,
            )
        val lines = facts.lines(context)
        show(RescueUiState(RescueReason.OpenFailed, supportOpen = true), lines)

        compose.onNodeWithText(text(R.string.support_help)).assertIsDisplayed()
        compose.onNodeWithText("DATABASE_OPEN_FAILED").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.support_unavailable)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.action_copy)).performClick()
        assertEquals(listOf<RescueAction>(RescueAction.CopySupport), actions)
        // Cinco datos técnicos, y ninguno sale de la base.
        assertEquals(5, lines.size)
        val copied =
            supportText(lines) { label, value ->
                context.getString(R.string.support_line, label, value)
            }
        assertFalse(copied.contains("cuenta"))
        assertTrue(copied.contains("Fabricante Modelo"))
    }

    @Test
    fun goingBackBeforeTheUpdateIsConfirmedAndThenStartsLikeAnyRestore() {
        // El botón lleva a la misma preparación que restaurar, y la ruta pide antes la credencial.
        val copyAt = Instant.parse("2026-11-01T10:00:00Z")
        show(
            RescueUiState(
                RescueReason.MigrationFailed,
                BackupDestinationKind.WEBDAV,
                beforeUpdate = BeforeUpdateCopy(copyAt),
                restore = RestoreUi.ConfirmBeforeUpdate(copyAt),
            ),
        )

        compose.onNodeWithText(text(R.string.dialog_undo_migration_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.dialog_go_back_action)).performClick()
        assertEquals(
            listOf<RescueAction>(RescueAction.Restore(BackupAction.StartRestore)),
            actions,
        )
    }

    @Test
    fun aFailedMigrationWithTheCopyBeforeTheUpdateLeadsToGoingBack() {
        show(
            RescueUiState(
                RescueReason.MigrationFailed,
                BackupDestinationKind.WEBDAV,
                beforeUpdate = BeforeUpdateCopy(Instant.parse("2026-11-01T10:00:00Z")),
            ),
        )

        compose.onNodeWithText(text(R.string.rescue_detail_migration)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.backup_undo_migration_label))
            .performScrollTo()
            .performClick()
        assertEquals(listOf<RescueAction>(RescueAction.BeforeUpdate), actions)
    }

    @Test
    fun safeModeLeadsWithRetryOpeningAndKeepsTheOtherWays() {
        show(RescueUiState(RescueReason.SafeMode, BackupDestinationKind.WEBDAV))

        compose.onNodeWithText(text(R.string.rescue_safe_mode_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.rescue_safe_mode_body)).assertIsDisplayed()
        compose.onNodeWithText(
            text(R.string.rescue_safe_mode_retry),
        ).performScrollTo().performClick()
        compose.onNodeWithText(
            text(R.string.backup_action_restore),
        ).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(
            text(R.string.backup_action_find_file),
        ).performScrollTo().assertIsDisplayed()
        assertEquals(listOf<RescueAction>(RescueAction.RetryOpen), actions)
    }

    @Test
    fun safeModeWithAReadableJournalOnlyOffersRetryAndSupport() {
        show(
            RescueUiState(
                RescueReason.SafeMode,
                BackupDestinationKind.WEBDAV,
                damagedAvailable = true,
                restorePending = true,
            ),
        )

        compose.onNodeWithText(text(R.string.rescue_detail_journal)).assertIsDisplayed()
        compose.onNodeWithText(
            text(R.string.rescue_safe_mode_retry),
        ).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(
            text(R.string.settings_support),
        ).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.backup_action_restore)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.backup_action_find_file)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.backup_damaged_share_action)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.language_title)).assertDoesNotExist()
    }

    @Test
    fun theCrashLogIsShownWholeBeforeItCanBeShared() {
        val log =
            "format=1\nkind=java_crash\njava.lang.IllegalStateException\n" +
                "\tat a.b.C.d(Unknown Source)"
        show(RescueUiState(RescueReason.SafeMode, supportOpen = true), crashLog = log)

        compose.onNodeWithText(
            text(R.string.rescue_crash_log_help),
        ).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(
            "kind=java_crash",
            substring = true,
        ).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.action_share)).performScrollTo().performClick()
        assertEquals(listOf<RescueAction>(RescueAction.ShareCrashLog), actions)
    }

    @Test
    fun theUpdateScreenShowsWhileTheGateCopiesAndMigrates() {
        compose.setContent { PreviewTheme { OpeningScreen(OpeningStage.MIGRATING) } }

        compose.onNodeWithText(text(R.string.migration_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.migration_body)).assertIsDisplayed()
    }

    @Test
    fun theRestoreScreenShowsWhileTheGateFinishesARestore() {
        compose.setContent { PreviewTheme { OpeningScreen(OpeningStage.RESTORING) } }

        compose.onNodeWithText(text(R.string.restore_after_restart)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.migration_title)).assertDoesNotExist()
    }
}
