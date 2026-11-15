package io.github.yesidrobayo.shopledger.ui.privacy

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationInfo
import io.github.yesidrobayo.shopledger.domain.model.safety.CloudAccountCheck
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `Seguridad de la cuenta de la nube` en el teléfono: la frase contra la suplantación siempre
 * visible, la lista que la persona marca según su destino, la contraseña para apps solo con
 * WebDAV, las guías y `Abrir {provider}` solo con la nube del teléfono, y sin cuenta, nada que
 * marcar. Datos inventados.
 */
@RunWith(AndroidJUnit4::class)
class CloudAccountScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
    private val actions = mutableListOf<CloudAccountAction>()

    private fun text(id: Int, vararg args: Any) = resources.getString(id, *args)

    private val app get() = text(R.string.app_name)

    private fun show(state: CloudAccountUiState) {
        compose.setContent { PreviewTheme { CloudAccountScreen(state, { actions += it }) } }
    }

    private fun shown(value: String) {
        compose.onNodeWithText(value, substring = true).performScrollTo().assertIsDisplayed()
    }

    private fun absent(value: String) = assertTrue(
        compose.onAllNodesWithText(value, substring = true).fetchSemanticsNodes().isEmpty(),
    )

    private fun ready(
        destination: BackupDestinationInfo? = WEBDAV,
        checked: Set<CloudAccountCheck> = emptySet(),
    ) = CloudAccountUiState(
        loading = false,
        destination = Fact.Known(destination),
        checked = checked,
    )

    @Test
    fun withWebDavItShowsTheAccountTheListAndTheAppPassword() {
        show(ready(checked = setOf(CloudAccountCheck.TWO_STEP)))

        shown(text(R.string.account_password_notice, app))
        shown(text(R.string.account_connected, "negocio"))
        shown(text(R.string.account_list_progress, "1", "3"))
        compose.onNodeWithText(text(R.string.account_item_two_step), substring = true)
            .assertIsOn()
        compose.onNodeWithText(text(R.string.account_item_email), substring = true)
            .assertIsOff()
        shown(text(R.string.account_self_marked, app))
        shown(text(R.string.account_app_password_title))
        absent(text(R.string.account_guides_title))
        absent(text(R.string.backup_check_open_action, "Drive"))
    }

    @Test
    fun tappingAPointMarksIt() {
        show(ready())

        compose.onNodeWithText(text(R.string.account_item_phone), substring = true)
            .performScrollTo()
            .performClick()

        assertEquals(
            listOf(CloudAccountAction.Mark(CloudAccountCheck.RECOVERY_PHONE, true)),
            actions,
        )
    }

    @Test
    fun withTheCloudOnThePhoneItOffersToOpenItAndTheGuides() {
        show(ready(destination = BackupDestinationInfo.Slots(providerLabel = "Drive")))

        shown(text(R.string.account_connected, "Drive"))
        absent(text(R.string.account_app_password_title))
        compose.onNodeWithText(text(R.string.backup_check_open_action, "Drive"))
            .performScrollTo()
            .performClick()
        compose.onNodeWithText(text(R.string.account_guide_quota))
            .performScrollTo()
            .performClick()

        assertEquals(
            listOf(
                CloudAccountAction.OpenProvider,
                CloudAccountAction.OpenGuide(AccountGuide.QUOTA),
            ),
            actions,
        )
    }

    @Test
    fun withoutAnAccountNothingCanBeMarkedAndTheGuideStays() {
        show(ready(destination = null))

        shown(text(R.string.account_empty_title))
        compose.onNodeWithText(text(R.string.account_item_two_step), substring = true)
            .assertIsNotEnabled()
        shown(text(R.string.account_inactivity_body))
        compose.onNodeWithText(text(R.string.backup_action_choose_destination)).performClick()

        assertEquals(listOf<CloudAccountAction>(CloudAccountAction.ChooseDestination), actions)
    }

    @Test
    fun lostAccessShowsReconnectAboveTheList() {
        show(ready().copy(accessLost = true))

        compose.onNodeWithText(text(R.string.backup_state_permission_lost)).performClick()

        assertEquals(listOf<CloudAccountAction>(CloudAccountAction.Reconnect), actions)
    }

    @Test
    fun aDestinationThatCouldNotBeReadOffersToRetry() {
        show(CloudAccountUiState(loading = false, destination = Fact.Unavailable))

        shown(text(R.string.account_password_notice, app))
        compose.onNodeWithText(text(R.string.action_retry)).performClick()

        assertEquals(listOf<CloudAccountAction>(CloudAccountAction.Refresh), actions)
    }

    private companion object {
        val WEBDAV = BackupDestinationInfo.WebDav(account = "negocio", host = "example.com")
    }
}
