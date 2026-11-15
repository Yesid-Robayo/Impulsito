package io.github.yesidrobayo.shopledger.ui.update

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.update.UpdateCheckOutcome
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.update.ApkCheckResult
import io.github.yesidrobayo.shopledger.update.InstalledApp
import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `Actualizaciones y verificar APK` en el teléfono: la versión y la huella, `Buscar actualización`
 * solo con la búsqueda permitida y cada resultado sin bloquear nada, `Cómo actualizar` con sus
 * pasos, y cada resultado de `Verificar un APK` con su texto. Datos inventados.
 */
@RunWith(AndroidJUnit4::class)
class UpdatesScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
    private val actions = mutableListOf<UpdatesAction>()

    private fun text(id: Int, vararg args: Any) = resources.getString(id, *args)

    private val app get() = text(R.string.app_name)

    private fun show(state: UpdatesUiState) {
        compose.setContent { PreviewTheme { UpdatesScreen(state, { actions += it }) } }
    }

    private fun shown(value: String) {
        compose.onNodeWithText(value, substring = true).performScrollTo().assertIsDisplayed()
    }

    private fun absent(value: String) = assertTrue(
        compose.onAllNodesWithText(value, substring = true).fetchSemanticsNodes().isEmpty(),
    )

    private fun chooseFile() {
        compose.onNodeWithText(text(R.string.verify_choose_action)).performScrollTo()
        compose.onNodeWithText(text(R.string.verify_choose_action)).performClick()
    }

    private fun ready(
        verify: VerifyUi = VerifyUi.Idle,
        searchEnabled: Boolean? = null,
        search: SearchUi = SearchUi.Idle,
        lastSearchAt: Instant? = null,
    ) = UpdatesUiState(
        installedLoading = false,
        installed = InstalledApp("1.0.0", 1042, "3F:9A:21:7C"),
        searchEnabled = searchEnabled,
        lastSearchAt = lastSearchAt,
        search = search,
        now = Instant.parse("2026-11-15T12:00:00Z"),
        verify = verify,
    )

    private fun searched(outcome: UpdateCheckOutcome) =
        ready(searchEnabled = true, search = SearchUi.Done(outcome))

    @Test
    fun itShowsTheVersionAndTheSignatureFingerprint() {
        show(ready())

        shown(text(R.string.update_version, "1.0.0", "1042"))
        shown(text(R.string.update_fingerprint_title))
        // Cada grupo admite un corte de línea tras su `:`, con un espacio de ancho cero.
        shown("3F:\u200B9A:\u200B21:\u200B7C")
        shown(text(R.string.update_fingerprint_help, app))
        shown(text(R.string.verify_body, app))
    }

    @Test
    fun withoutTheInstalledAppItShowsNoValueForTalkBack() {
        show(UpdatesUiState(installedLoading = false))

        // La versión y la huella: dos `—`, cada uno con su descripción para TalkBack.
        val noValue = text(R.string.a11y_no_value)
        val described =
            compose
                .onAllNodesWithContentDescription(noValue, useUnmergedTree = true)
                .fetchSemanticsNodes()
        assertEquals(2, described.size)
        absent(text(R.string.update_version, "1.0.0", "1042"))
    }

    @Test
    fun chooseFileAsksForThePicker() {
        show(ready())

        chooseFile()

        assertEquals(listOf<UpdatesAction>(UpdatesAction.ChooseFile), actions)
    }

    @Test
    fun whileCheckingTheButtonDoesNotAskAgain() {
        show(ready(VerifyUi.Checking))

        shown(text(R.string.verify_checking))
        chooseFile()

        assertTrue(actions.isEmpty())
    }

    @Test
    fun theRightSignatureSaysSoWithItsVersionAndHash() {
        show(ready(VerifyUi.Done(ApkCheckResult.Match("1.0.1", 1043, "abc123", older = false))))

        shown(text(R.string.verify_ok))
        shown(text(R.string.verify_version, "1.0.1"))
        shown("abc123")
        absent(text(R.string.verify_mismatch_title))
        absent(text(R.string.verify_manifest_match))
    }

    @Test
    fun aMatchWithTheVersionNoticeSaysItToo() {
        val match = ApkCheckResult.Match("1.0.1", 1043, "abc123", older = false)
        show(ready(VerifyUi.Done(match, manifestMatch = true)))

        shown(text(R.string.verify_manifest_match))
    }

    @Test
    fun anOlderFileSaysAndroidWillNotInstallItOverTheApp() {
        show(ready(VerifyUi.Done(ApkCheckResult.Match("0.9.0", 900, "abc123", older = true))))

        shown(text(R.string.verify_older_title))
        shown(text(R.string.verify_older_body))
        absent(text(R.string.verify_ok))
    }

    @Test
    fun anotherSignatureSaysNotToInstallNorUninstall() {
        show(ready(VerifyUi.Done(ApkCheckResult.Mismatch("def456"))))

        shown(text(R.string.verify_mismatch_title))
        shown(text(R.string.verify_mismatch_body, app))
        shown("def456")
        absent(text(R.string.verify_ok))
    }

    @Test
    fun aFileThatIsNotAnApkSaysSo() {
        show(ready(VerifyUi.Done(ApkCheckResult.NotApk)))
        shown(text(R.string.verify_not_apk))
    }

    @Test
    fun aFileThatCouldNotBeReadSaysSo() {
        show(ready(VerifyUi.Done(ApkCheckResult.ReadFailed)))
        shown(text(R.string.verify_error_read))
    }

    @Test
    fun howToUpdateOpensItsFourSteps() {
        show(ready())

        compose.onNodeWithText(text(R.string.update_how_title)).performScrollTo().performClick()

        compose.onNodeWithText(text(R.string.update_how_step_backup)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.update_how_step_download, app)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.update_how_step_verify)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.update_how_step_install, app)).assertIsDisplayed()
    }

    @Test
    fun withoutTheSettingsNothingOfTheSearchIsOffered() {
        show(ready())

        absent(text(R.string.update_search_action))
        absent(text(R.string.update_allow_search))
        absent(text(R.string.update_search_off, app))
    }

    @Test
    fun withTheSearchOffTheButtonIsHiddenAndItSaysTheAppDoesNotConnect() {
        show(ready(searchEnabled = false))

        absent(text(R.string.update_search_action))
        shown(text(R.string.update_search_off, app))
        shown(text(R.string.update_allow_search_help))
        compose.onNodeWithText(text(R.string.update_allow_search)).performScrollTo().performClick()

        assertEquals(listOf<UpdatesAction>(UpdatesAction.SetSearchEnabled(true)), actions)
    }

    @Test
    fun withTheSearchOnTheButtonSearchesAndSaysThereWasNoSearchYet() {
        show(ready(searchEnabled = true))

        shown(text(R.string.update_empty))
        compose.onNodeWithText(text(R.string.update_search_action)).performScrollTo()
        compose.onNodeWithText(text(R.string.update_search_action)).performClick()

        assertEquals(listOf<UpdatesAction>(UpdatesAction.Search), actions)
    }

    @Test
    fun theLastSearchShowsItsDate() {
        show(ready(searchEnabled = true, lastSearchAt = Instant.parse("2026-10-28T15:00:00Z")))

        shown(text(R.string.update_last_search, "").trimEnd())
        absent(text(R.string.update_empty))
    }

    @Test
    fun whileSearchingItSaysSo() {
        show(ready(searchEnabled = true, search = SearchUi.Searching))

        shown(text(R.string.update_searching))
    }

    @Test
    fun theLatestVersionSaysSo() {
        show(searched(UpdateCheckOutcome.Latest(null)))

        shown(text(R.string.update_latest))
    }

    @Test
    fun aNewVersionShowsItsNameAndLeadsToHowToUpdate() {
        val published = Instant.parse("2026-11-10T12:00:00Z")
        show(searched(UpdateCheckOutcome.NewVersion("1.0.1", published, "ab", important = false)))

        shown(text(R.string.update_new, "1.0.1"))
        shown(text(R.string.update_new_date, "").trimEnd())
        absent(text(R.string.update_important_title))
        compose.onNodeWithText(text(R.string.update_view_how_action)).performScrollTo()
        compose.onNodeWithText(text(R.string.update_view_how_action)).performClick()

        compose.onNodeWithText(text(R.string.update_how_step_backup)).assertIsDisplayed()
    }

    @Test
    fun anImportantUpdateWarnsAndBlocksNothing() {
        val published = Instant.parse("2026-11-10T12:00:00Z")
        show(searched(UpdateCheckOutcome.NewVersion("1.0.2", published, "ab", important = true)))

        shown(text(R.string.update_important_title))
        shown(text(R.string.update_important_body))
        // Lo demás sigue a mano: verificar un archivo no se apaga.
        shown(text(R.string.verify_choose_action))
    }

    @Test
    fun aBadSignatureIsIgnoredWithoutShowingItsContent() {
        show(searched(UpdateCheckOutcome.BadSignature))

        shown(text(R.string.update_bad_signature))
        absent(text(R.string.update_latest))
    }

    @Test
    fun withoutConnectionItCanBeRetried() {
        show(searched(UpdateCheckOutcome.Offline))

        shown(text(R.string.state_no_connection))
        compose.onNodeWithText(text(R.string.action_retry)).performScrollTo().performClick()

        assertEquals(listOf<UpdatesAction>(UpdatesAction.Search), actions)
    }

    @Test
    fun anUnreadableNoticeCanBeRetried() {
        show(searched(UpdateCheckOutcome.Unreadable))

        shown(text(R.string.update_error_read))
        compose.onNodeWithText(text(R.string.action_retry)).performScrollTo().performClick()

        assertEquals(listOf<UpdatesAction>(UpdatesAction.Search), actions)
    }

    @Test
    fun backIsAnAction() {
        show(ready())

        compose.onNodeWithContentDescription(text(R.string.a11y_back)).performClick()

        assertEquals(listOf<UpdatesAction>(UpdatesAction.Back), actions)
    }
}
