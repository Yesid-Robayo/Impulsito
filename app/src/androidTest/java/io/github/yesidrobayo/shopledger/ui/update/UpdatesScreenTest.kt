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
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.update.ApkCheckResult
import io.github.yesidrobayo.shopledger.update.InstalledApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `Actualizaciones y verificar APK` en el teléfono: la versión y la huella, `Cómo actualizar` con
 * sus pasos, y cada resultado de `Verificar un APK` con su texto. Datos inventados.
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

    private fun ready(verify: VerifyUi = VerifyUi.Idle) = UpdatesUiState(
        installedLoading = false,
        installed = InstalledApp("1.0.0", 1042, "3F:9A:21:7C"),
        verify = verify,
    )

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
    fun backIsAnAction() {
        show(ready())

        compose.onNodeWithContentDescription(text(R.string.a11y_back)).performClick()

        assertEquals(listOf<UpdatesAction>(UpdatesAction.Back), actions)
    }
}
