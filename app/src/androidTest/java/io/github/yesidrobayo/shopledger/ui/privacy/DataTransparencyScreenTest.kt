package io.github.yesidrobayo.shopledger.ui.privacy

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationInfo
import io.github.yesidrobayo.shopledger.domain.model.safety.PolicyConsentStatus
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `Qué datos hay y adónde van` en el teléfono: el estado real en `Ahora mismo`, exactamente sus
 * destinos de red (el servicio elegido, el aviso de versión y el APK que baja `Actualizar`), lo que
 * no se pudo leer dicho como tal, y el aviso de una política nueva con sus dos salidas. Datos
 * inventados.
 */
@RunWith(AndroidJUnit4::class)
class DataTransparencyScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
    private val actions = mutableListOf<DataTransparencyAction>()

    private fun text(id: Int, vararg args: Any) = resources.getString(id, *args)

    private val app get() = text(R.string.app_name)

    private fun show(state: DataTransparencyUiState) {
        compose.setContent { PreviewTheme { DataTransparencyScreen(state, { actions += it }) } }
    }

    private fun shown(value: String) {
        compose.onNodeWithText(value).performScrollTo().assertIsDisplayed()
    }

    private fun absent(value: String) = assertTrue(
        compose.onAllNodesWithText(value).fetchSemanticsNodes().isEmpty(),
    )

    private fun ready(
        consent: PolicyConsentStatus = PolicyConsentStatus.ACCEPTED,
        destination: BackupDestinationInfo? = WEBDAV,
    ) = DataTransparencyUiState(
        loading = false,
        consent = Fact.Known(consent),
        destination = Fact.Known(destination),
        encryptionOn = Fact.Known(false),
        second = Fact.Known(SecondFact.Folder("Copias")),
        appLock = Fact.Known(true),
        updateCheck = Fact.Known(false),
    )

    @Test
    fun withWebDavItShowsTheRealStateAndOnlyItsDestinations() {
        show(ready())

        shown(text(R.string.transparency_now_backups_webdav, "example.com"))
        shown("negocio")
        shown(text(R.string.transparency_now_encryption_off))
        shown(text(R.string.transparency_backup_plain))
        shown(text(R.string.transparency_now_second_folder, "Copias"))
        shown(text(R.string.transparency_now_lock_on))
        shown(text(R.string.transparency_now_update_off))
        shown(text(R.string.transparency_dest_webdav_title, "example.com"))
        val host = text(R.string.transparency_manifest_host)
        shown(text(R.string.transparency_dest_manifest_title, host))
        shown(text(R.string.transparency_dest_manifest_ip, host))
        val releases = text(R.string.transparency_apk_host)
        shown(text(R.string.transparency_dest_apk_title, releases))
        shown(text(R.string.transparency_dest_apk_purpose))
        shown(text(R.string.transparency_dest_apk_ip, releases))
        shown(text(R.string.transparency_dest_apk_check, app))
        shown(text(R.string.transparency_no_other, app))
        absent(text(R.string.transparency_accept_action))
        absent(text(R.string.transparency_now_backups_paused))
    }

    @Test
    fun withTheCloudOnThePhoneItSaysTheAppDoesNotUseTheNetwork() {
        show(ready(destination = BackupDestinationInfo.Slots(providerLabel = "Drive")))

        shown(text(R.string.transparency_now_backups_slots, "Drive"))
        shown(text(R.string.transparency_dest_slots_title, "Drive"))
        shown(text(R.string.transparency_dest_slots_sends))
        absent(text(R.string.transparency_dest_webdav_sends))
    }

    @Test
    fun withoutADestinationOnlyTheVersionFileAndItsApkAreDestinations() {
        show(ready(destination = null))

        shown(text(R.string.transparency_now_backups_off))
        absent(text(R.string.transparency_dest_webdav_purpose))
        absent(text(R.string.transparency_dest_slots_purpose))
        shown(text(R.string.transparency_dest_manifest_purpose))
        shown(text(R.string.transparency_dest_apk_purpose))
    }

    @Test
    fun aFactThatCouldNotBeReadSaysSoAndNeverGuesses() {
        show(ready().copy(encryptionOn = Fact.Unavailable, appLock = Fact.Unavailable))

        val unavailable = text(R.string.transparency_unavailable)
        assertEquals(2, compose.onAllNodesWithText(unavailable).fetchSemanticsNodes().size)
        absent(text(R.string.transparency_now_encryption_off))
        absent(text(R.string.transparency_now_lock_on))
    }

    @Test
    fun aNewPolicyShowsTheNoticeWithAcceptAndDecideLater() {
        show(ready(consent = PolicyConsentStatus.OUTDATED))

        shown(text(R.string.transparency_now_backups_paused))
        compose.onNodeWithText(text(R.string.transparency_accept_action)).performClick()
        compose.onNodeWithText(text(R.string.transparency_decide_later_action)).performClick()

        assertEquals(
            listOf(DataTransparencyAction.AcceptPolicy, DataTransparencyAction.DecideLater),
            actions,
        )
    }

    @Test
    fun whileAcceptingAnotherTapDoesNothing() {
        show(ready(consent = PolicyConsentStatus.OUTDATED).copy(accepting = true))

        compose.onNodeWithText(text(R.string.transparency_accept_action)).performClick()

        assertEquals(emptyList<DataTransparencyAction>(), actions)
    }

    @Test
    fun thePolicyAndTheTermsOpenFromTheLastSection() {
        show(ready())

        compose.onNodeWithText(text(R.string.settings_privacy)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.legal_terms_title)).performScrollTo().performClick()

        assertEquals(
            listOf(DataTransparencyAction.OpenPrivacy, DataTransparencyAction.OpenTerms),
            actions,
        )
    }

    @Test
    fun withoutTheDatabaseTheFixedTextsStayAndEveryFactIsUnavailable() {
        show(DataTransparencyUiState(loading = false))

        shown(text(R.string.transparency_intro))
        shown(text(R.string.transparency_data_customers_body))
        val unavailable = text(R.string.transparency_unavailable)
        assertTrue(compose.onAllNodesWithText(unavailable).fetchSemanticsNodes().size >= 5)
    }

    private companion object {
        val WEBDAV = BackupDestinationInfo.WebDav(account = "negocio", host = "example.com")
    }
}
