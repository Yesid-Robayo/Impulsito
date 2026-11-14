package io.github.yesidrobayo.shopledger.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Un lector de códigos externo funciona como un teclado: escribe el código en la búsqueda y
 * termina con Enter. El Enter llega una sola vez como búsqueda, sin permisos ni saltos de línea.
 * El código es inventado.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class SearchFieldKeyboardTest {
    @get:Rule
    val compose = createComposeRule()

    private var query = ""
    private val submitted = mutableListOf<String>()

    private fun show() {
        compose.setContent {
            PreviewTheme {
                var text by remember { mutableStateOf("") }
                SearchField(
                    query = text,
                    onQueryChange = {
                        text = it
                        query = it
                    },
                    placeholder = HINT,
                    onSubmit = { submitted += text },
                )
            }
        }
    }

    @Test
    fun aReaderTypesTheCodeAndEnterSubmitsItOnce() {
        show()
        val field = compose.onNode(hasSetTextAction())
        field.performClick()

        field.performTextInput(CODE)
        field.performKeyInput { pressKey(Key.Enter) }
        compose.waitForIdle()

        assertEquals(CODE, query)
        assertEquals(listOf(CODE), submitted)
    }

    @Test
    fun theNumericPadEnterWorksToo() {
        show()
        val field = compose.onNode(hasSetTextAction())
        field.performClick()

        field.performTextInput(CODE)
        field.performKeyInput { pressKey(Key.NumPadEnter) }
        compose.waitForIdle()

        assertEquals(listOf(CODE), submitted)
        assertEquals(CODE, query)
    }

    private companion object {
        const val HINT = "Buscar producto"
        const val CODE = "2900000123458"
    }
}
