package io.github.yesidrobayo.shopledger.ui.encryption

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
 * `Ver mi código` en `Cifrado de copias`: la fila con el cifrado activo y tras apagarlo a propósito,
 * nunca con el código detenido; el código en sus grupos, sin ninguna acción de copiar; el aviso de
 * un teléfono sin bloqueo de pantalla; y cerrar. El código es el sintético de la especificación.
 */
@RunWith(AndroidJUnit4::class)
class RecoveryCodeViewScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
    private val actions = mutableListOf<EncryptionAction>()
    private val groups = listOf("G7K2", "M9PQ", "R4ST", "V6WX", "Y8Z3", "ABCD", "EF51")

    private fun text(id: Int) = resources.getString(id)

    private fun show(state: EncryptionUiState) {
        compose.setContent { PreviewTheme { EncryptionScreen(state, { actions += it }) } }
    }

    private fun count(value: String) = compose.onAllNodesWithText(value).fetchSemanticsNodes().size

    @Test
    fun theRowAsksToViewTheCodeWhenEncryptionIsOn() {
        show(EncryptionUiState(EncryptionStatusUi.On))

        compose.onNodeWithText(text(R.string.encryption_view_code_action)).performClick()

        assertEquals(listOf<EncryptionAction>(EncryptionAction.ViewCode), actions)
    }

    @Test
    fun theRowStaysAfterTurningItOffOnPurposeOnly() {
        show(EncryptionUiState(EncryptionStatusUi.Off(wasOn = true)))
        assertEquals(1, count(text(R.string.encryption_view_code_action)))
    }

    @Test
    fun thereIsNoRowWhenItWasNeverOn() {
        show(EncryptionUiState(EncryptionStatusUi.Off(wasOn = false)))
        assertEquals(0, count(text(R.string.encryption_view_code_action)))
    }

    @Test
    fun thereIsNoRowWhenTheCodeDoesNotOpen() {
        show(EncryptionUiState(EncryptionStatusUi.KeyUnavailable))
        assertEquals(0, count(text(R.string.encryption_view_code_action)))
    }

    @Test
    fun theCodeIsShownInGroupsWithNothingToCopy() {
        show(EncryptionUiState(EncryptionStatusUi.On, codeView = CodeViewUi.Shown(groups)))

        compose.onNodeWithText(text(R.string.recovery_code_no_copy)).assertIsDisplayed()
        for (group in groups) {
            val node = compose.onNodeWithText(group).assertIsDisplayed().fetchSemanticsNode()
            assertEquals(null, node.config.getOrNull(SemanticsActions.CopyText))
            assertTrue(node.config.getOrNull(SemanticsProperties.IsSensitiveData) == true)
        }
        // Ningún nodo de la pantalla ofrece copiar.
        val copyable =
            compose
                .onAllNodesWithText(groups.first(), substring = true)
                .fetchSemanticsNodes()
                .count { it.config.getOrNull(SemanticsActions.CopyText) != null }
        assertEquals(0, copyable)

        compose.onNodeWithText(text(R.string.action_close)).performClick()
        assertEquals(listOf<EncryptionAction>(EncryptionAction.CloseCode), actions)
    }

    @Test
    fun aCodeThatDoesNotOpenSaysSo() {
        show(EncryptionUiState(EncryptionStatusUi.On, codeView = CodeViewUi.Unavailable))

        compose.onNodeWithText(text(R.string.encryption_key_unavailable_body)).assertIsDisplayed()
        groups.forEach { assertEquals(0, count(it)) }
    }

    @Test
    fun withoutAScreenLockItWarnsAndLetsThePersonGoOn() {
        show(EncryptionUiState(EncryptionStatusUi.On, confirmViewWithoutScreenLock = true))

        compose.onNodeWithText(text(R.string.recovery_view_no_screen_lock)).assertIsDisplayed()
        // La fila, el título y el botón del diálogo dicen lo mismo: se toca el botón del diálogo.
        compose
            .onNode(
                hasText(text(R.string.encryption_view_code_action)) and
                    hasAnyAncestor(isDialog()) and
                    hasClickAction(),
            )
            .performClick()

        assertEquals(listOf<EncryptionAction>(EncryptionAction.ViewCodeAuthorized), actions)
    }
}
