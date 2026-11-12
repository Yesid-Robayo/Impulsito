package io.github.yesidrobayo.shopledger.ui.encryption

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * La prueba periódica del código en `Cifrado de copias`: las fechas en el bloque activo,
 * `Probar el código`, y la hoja con su texto, el error sin contador, `Código perdido` y
 * `Más tarde`. El código es el sintético de la especificación.
 */
@RunWith(AndroidJUnit4::class)
class RecoveryCodeCheckScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
    private val actions = mutableListOf<EncryptionAction>()
    private val groups = listOf("G7K2", "M9PQ", "R4ST", "V6WX", "Y8Z3", "ABCD", "EF51")
    private val dates =
        CheckDatesUi(BusinessDate(LocalDate(2026, 11, 8)), BusinessDate(LocalDate(2026, 12, 1)))

    private fun text(id: Int) = resources.getString(id)

    private fun show(state: EncryptionUiState) {
        compose.setContent { PreviewTheme { EncryptionScreen(state, { actions += it }) } }
    }

    private fun count(value: String, substring: Boolean = false) =
        compose.onAllNodesWithText(value, substring = substring).fetchSemanticsNodes().size

    @Test
    fun theOnBlockSaysWhenTheCodeWasCheckedAndWhenItIsNext() {
        show(EncryptionUiState(EncryptionStatusUi.On, checkDates = dates))

        val checked = text(R.string.encryption_code_checked).substringBefore("%")
        val next = text(R.string.encryption_next_check).substringBefore("%")
        assertEquals(1, count(checked, substring = true))
        assertEquals(1, count(next, substring = true))
    }

    @Test
    fun beforeTheFirstCheckOnlyTheNextDateShows() {
        show(EncryptionUiState(EncryptionStatusUi.On, checkDates = dates.copy(lastCheck = null)))

        assertEquals(0, count(text(R.string.encryption_code_checked).substringBefore("%"), true))
        assertEquals(1, count(text(R.string.encryption_next_check).substringBefore("%"), true))
    }

    @Test
    fun testTheCodeOpensTheCheck() {
        show(EncryptionUiState(EncryptionStatusUi.On, checkDates = dates))

        compose.onNodeWithText(
            text(R.string.encryption_test_action),
        ).performScrollTo().performClick()

        assertEquals(listOf<EncryptionAction>(EncryptionAction.TestCode), actions)
    }

    @Test
    fun thereIsNoTestButtonWhenTheCodeDoesNotOpen() {
        show(EncryptionUiState(EncryptionStatusUi.KeyUnavailable))
        assertEquals(0, count(text(R.string.encryption_test_action)))
    }

    @Test
    fun theSheetExplainsThereIsNoLimitAndOffersLostAndLater() {
        show(EncryptionUiState(EncryptionStatusUi.On, codeCheck = CodeCheckUi()))

        compose.onNodeWithText(text(R.string.recovery_check_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.recovery_check_body)).assertIsDisplayed()
        assertEquals(0, count(text(R.string.recovery_check_mismatch)))
        compose.onNodeWithText(text(R.string.recovery_lost_action)).performScrollTo().performClick()
        compose.onNodeWithText(
            text(R.string.recovery_later_action),
        ).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.recovery_check_action)).performClick()

        assertEquals(
            listOf(
                EncryptionAction.LostCode,
                EncryptionAction.LaterCheck,
                EncryptionAction.SubmitCheck,
            ),
            actions,
        )
    }

    @Test
    fun aMismatchSaysNothingWasDeletedAndKeepsWhatWasTyped() {
        show(
            EncryptionUiState(
                EncryptionStatusUi.On,
                codeCheck = CodeCheckUi(groups, CodeCheckProblem.MISMATCH),
            ),
        )

        compose.onNodeWithText(text(R.string.recovery_check_mismatch)).assertIsDisplayed()
        assertTrue(count(groups.first()) >= 1)
    }

    @Test
    fun aCodeThatDoesNotOpenSaysSoInTheSheet() {
        show(
            EncryptionUiState(
                EncryptionStatusUi.On,
                codeCheck = CodeCheckUi(problem = CodeCheckProblem.UNAVAILABLE),
            ),
        )

        compose.onNodeWithText(text(R.string.encryption_key_unavailable_body)).assertIsDisplayed()
    }
}
