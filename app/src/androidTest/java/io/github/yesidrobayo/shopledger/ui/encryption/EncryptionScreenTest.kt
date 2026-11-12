package io.github.yesidrobayo.shopledger.ui.encryption

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `Cifrado de copias` en el teléfono: apagado con su riesgo y `Activar cifrado`, activo sin acción
 * principal, detenido sin nada en claro, y cada paso de la activación con lo que exige. El código es
 * el sintético de la especificación.
 */
@RunWith(AndroidJUnit4::class)
class EncryptionScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
    private val actions = mutableListOf<EncryptionAction>()
    private val groups = listOf("G7K2", "M9PQ", "R4ST", "V6WX", "Y8Z3", "ABCD", "EF51")

    private fun text(id: Int) = resources.getString(id)

    private fun show(state: EncryptionUiState) {
        compose.setContent { PreviewTheme { EncryptionScreen(state, { actions += it }) } }
    }

    private fun absent(value: String) = assertTrue(
        compose.onAllNodesWithText(value).fetchSemanticsNodes().isEmpty(),
    )

    @Test
    fun offExplainsTheRiskAndOffersToActivate() {
        show(EncryptionUiState(EncryptionStatusUi.Off(wasOn = false)))

        compose.onNodeWithText(text(R.string.encryption_off_title)).assertIsDisplayed()
        compose.onNodeWithText(
            text(R.string.encryption_risk_title),
        ).performScrollTo().assertIsDisplayed()
        absent(text(R.string.encryption_leftover_title))
        compose.onNodeWithText(text(R.string.encryption_activate_action)).performClick()

        assertEquals(listOf<EncryptionAction>(EncryptionAction.Start), actions)
    }

    @Test
    fun offOnPurposeSaysThatEncryptedCopiesRemain() {
        show(EncryptionUiState(EncryptionStatusUi.Off(wasOn = true)))

        compose.onNodeWithText(text(R.string.encryption_leftover_title)).assertIsDisplayed()
        compose.onNodeWithText(
            text(R.string.encryption_reactivate_note),
        ).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun onHasNoMainAction() {
        show(EncryptionUiState(EncryptionStatusUi.On))

        compose.onNodeWithText(text(R.string.encryption_on_title)).assertIsDisplayed()
        absent(text(R.string.encryption_activate_action))
    }

    @Test
    fun stoppedSaysThatNothingGoesOutUnencrypted() {
        show(EncryptionUiState(EncryptionStatusUi.KeyUnavailable))

        compose.onNodeWithText(text(R.string.encryption_key_unavailable_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.encryption_key_unavailable_body)).assertIsDisplayed()
        absent(text(R.string.encryption_activate_action))
    }

    @Test
    fun theFirstStepNeedsTheRiskAccepted() {
        show(
            EncryptionUiState(
                EncryptionStatusUi.Off(false),
                ActivationStepUi.Risk(accepted = false),
            ),
        )

        compose.onNodeWithText(text(R.string.action_continue)).assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.legal_encryption_ack)).performClick()

        assertEquals(listOf<EncryptionAction>(EncryptionAction.Accept(true)), actions)
    }

    @Test
    fun theCodeIsShownInGroupsMarkedSensitiveAndCannotContinueWithoutKitOrPaper() {
        show(EncryptionUiState(EncryptionStatusUi.Off(false), ActivationStepUi.ShowCode(groups)))

        groups.forEach { group ->
            val node = compose.onNodeWithText(group, useUnmergedTree = true).fetchSemanticsNode()
            assertEquals(
                true,
                node.config.getOrElseNullable(SemanticsProperties.IsSensitiveData) {
                    null
                },
            )
        }
        compose.onNodeWithText(text(R.string.action_continue)).assertIsNotEnabled()
        compose.onNodeWithText(
            text(R.string.recovery_kit_save_action),
        ).performScrollTo().performClick()
        compose.onNodeWithText(
            text(R.string.recovery_paper_action),
        ).performScrollTo().performClick()

        assertEquals(listOf(EncryptionAction.SaveKit, EncryptionAction.OnPaper), actions)
    }

    @Test
    fun aTypoIsMarkedOnItsGroup() {
        val typed = groups.toMutableList().also { it[1] = "M9PX" }
        show(
            EncryptionUiState(
                EncryptionStatusUi.Off(false),
                ActivationStepUi.Retype(typed, errorGroup = 1),
            ),
        )

        compose.onNodeWithText(
            resources.getString(R.string.recovery_error_typo, 2),
        ).assertIsDisplayed()
    }

    @Test
    fun aFailedTestSaysEncryptionStaysOffAndOffersRetry() {
        show(
            EncryptionUiState(
                EncryptionStatusUi.Off(false),
                ActivationStepUi.Testing(ActivationFailure.ROUND_TRIP),
            ),
        )

        compose.onNodeWithText(text(R.string.encryption_error_roundtrip)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.action_retry)).performClick()

        assertEquals(listOf<EncryptionAction>(EncryptionAction.Retry), actions)
    }

    @Test
    fun doneExplainsOlderUnencryptedCopies() {
        show(EncryptionUiState(EncryptionStatusUi.On, ActivationStepUi.Done))

        compose.onNodeWithText(text(R.string.encryption_done_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.encryption_old_copies_body)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.encryption_keep_plain_action)).performClick()

        assertEquals(listOf<EncryptionAction>(EncryptionAction.Finish), actions)
    }
}
