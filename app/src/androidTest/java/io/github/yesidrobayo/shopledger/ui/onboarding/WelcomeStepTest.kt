package io.github.yesidrobayo.shopledger.ui.onboarding

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.ui.backup.BackupAction
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * La bienvenida y la protección en el teléfono: lo que se puede perder con cada destino a la vista,
 * `Ahora no` y `Traer datos de otro teléfono` siempre, sin red también, las copias encontradas con
 * `Restaurar` y `Empezar de cero`, y `Continuar` al final. Datos inventados.
 */
@RunWith(AndroidJUnit4::class)
class WelcomeStepTest {
    @get:Rule
    val compose = createComposeRule()

    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
    private val actions = mutableListOf<OnboardingAction>()

    private fun text(id: Int) = resources.getString(id)

    private fun show(state: OnboardingUiState) {
        compose.setContent {
            PreviewTheme { WelcomeStep(state, PREVIEW_ONBOARDING_ZONE, { actions += it }) }
        }
    }

    @Test
    fun theWelcomeSaysWhatCanBeLostWithEachDestination() {
        show(PREVIEW_WELCOME)

        compose.onNodeWithText(text(R.string.backup_limits_every_60_min)).performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText(resources.getString(R.string.backup_limits_slots, "Drive"))
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun theFirstUseSaysWhatTheAppIsNotInAnyCountry() {
        show(PREVIEW_WELCOME)
        val app = text(R.string.app_name)

        compose.onNodeWithText(resources.getString(R.string.legal_not_accounting, app))
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText(resources.getString(R.string.legal_universal_notice, app))
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun withoutInternetNotNowStillWorks() {
        show(PREVIEW_WELCOME.copy(offline = true))

        compose.onNodeWithText(text(R.string.permission_destination_no_internet)).performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText(text(R.string.action_not_now)).performClick()
        compose.onNodeWithText(text(R.string.onboarding_restore_action)).performClick()
        compose.onNodeWithText(text(R.string.backup_action_choose_destination)).performClick()

        assertEquals(
            listOf(
                OnboardingAction.NotNow,
                OnboardingAction.OpenBring,
                OnboardingAction.ChooseDestination,
            ),
            actions,
        )
    }

    @Test
    fun theBringSheetOffersAServiceOrAFile() {
        show(PREVIEW_WELCOME.copy(bringSheet = true))

        compose.onNodeWithText(text(R.string.backup_action_find_file)).performClick()
        compose.onNodeWithText(text(R.string.destination_webdav_title)).performClick()

        assertEquals(
            listOf(OnboardingAction.FindFile, OnboardingAction.BringWithWebDav),
            actions,
        )
    }

    @Test
    fun foundCopiesOfferToRestoreOrStartOver() {
        show(PREVIEW_WELCOME.copy(found = FoundCopies.Found(listOf(PREVIEW_FOUND_COPY))))

        compose.onNodeWithText(text(R.string.backup_action_restore)).performClick()
        compose.onNodeWithText(text(R.string.dialog_start_over_action)).performScrollTo()
            .performClick()

        assertEquals(
            listOf(
                OnboardingAction.Restore(BackupAction.ConfirmCopy(PREVIEW_FOUND_COPY.key)),
                OnboardingAction.AskStartOver,
            ),
            actions,
        )
    }

    @Test
    fun protectionContinues() {
        compose.setContent {
            PreviewTheme {
                ProtectionStep(PREVIEW_WELCOME.copy(step = OnboardingStepId.PROTECTION)) {
                    actions += it
                }
            }
        }

        compose.onNodeWithText(text(R.string.onboarding_encrypt_needs_destination))
            .assertIsDisplayed()
        compose.onNodeWithText(text(R.string.action_continue)).performClick()

        assertEquals(listOf<OnboardingAction>(OnboardingAction.Finish), actions)
    }
}
