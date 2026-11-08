package io.github.yesidrobayo.shopledger.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * El campo de texto del catálogo: guarda lo escrito tal cual, avisa al salir y muestra su error
 * debajo, también para TalkBack. Los textos son inventados.
 */
@RunWith(AndroidJUnit4::class)
class AppTextFieldTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun keepsWhatWasTypedAndReportsLeavingOnlyAfterFocus() {
        var leaves = 0
        var typed = ""
        compose.setContent {
            PreviewTheme {
                Column {
                    var text by remember { mutableStateOf("") }
                    AppTextField(
                        value = text,
                        onValueChange = {
                            text = it
                            typed = it
                        },
                        label = FIELD,
                        onLeave = { leaves++ },
                    )
                    AppTextField(value = "", onValueChange = {}, label = OTHER)
                }
            }
        }
        compose.runOnIdle { assertEquals(0, leaves) }

        compose.onNodeWithText(FIELD).performClick()
        compose.onNodeWithText(FIELD).performTextInput("  Té verde de 9 caracteres o más  ")
        compose.onNodeWithText(OTHER).performClick()

        compose.runOnIdle {
            // Ni recorta ni corta el largo: eso lo revisa quien lo usa, al salir.
            assertEquals("  Té verde de 9 caracteres o más  ", typed)
            assertEquals(1, leaves)
        }
    }

    @Test
    fun showsTheErrorBelowAndMarksTheField() {
        compose.setContent {
            PreviewTheme {
                AppTextField(value = "¤¤¤¤¤¤¤¤¤", onValueChange = {}, label = FIELD, error = ERROR)
            }
        }

        compose.onNodeWithText(ERROR).assertExists()
        compose
            .onNodeWithText(FIELD)
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
    }

    @Test
    fun showsTheHelpWhenThereIsNoError() {
        compose.setContent {
            PreviewTheme {
                AppTextField(value = "", onValueChange = {}, label = FIELD, supportingText = HELP)
            }
        }

        compose.onNodeWithText(HELP).assertExists()
    }

    private companion object {
        const val FIELD = "Campo de prueba"
        const val OTHER = "Otro campo"
        const val ERROR = "Usar entre 1 y 8 caracteres"
        const val HELP = "De 1 a 8 caracteres"
    }
}
