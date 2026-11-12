package io.github.yesidrobayo.shopledger.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.text.TextRange
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * En el teléfono: el código de recuperación y la contraseña nunca llegan al portapapeles, ni con la
 * contraseña a la vista; un campo común sí copia, así que la prueba mide de verdad. Pegar sigue
 * funcionando, y el teclado recibe la marca de no aprender lo escrito. Códigos inventados.
 */
@RunWith(AndroidJUnit4::class)
class PrivateInputTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val clipboard: ClipboardManager
        get() = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    private fun clipboardText(): String? {
        var text: String? = null
        compose.runOnUiThread { text = clipboard.primaryClip?.getItemAt(0)?.text?.toString() }
        return text
    }

    private fun putInClipboard(text: String) {
        compose.runOnUiThread { clipboard.setPrimaryClip(ClipData.newPlainText(LABEL, text)) }
    }

    /** Selecciona todo el texto del campo [index] y pide copiarlo, como el menú del sistema. */
    private fun copyField(index: Int, length: Int) {
        val field = compose.onAllNodes(hasSetTextAction())[index]
        field.performClick()
        field.performTextInputSelection(TextRange(0, length))
        field.performSemanticsAction(SemanticsActions.CopyText)
        compose.waitForIdle()
    }

    @Test
    fun theRecoveryCodeNeverReachesTheClipboard() {
        putInClipboard(BEFORE)
        compose.setContent {
            PreviewTheme {
                RecoveryCodeField(groups = CODE, onGroupsChange = {}, label = LABEL)
            }
        }

        copyField(0, CODE[0].length)

        assertEquals(BEFORE, clipboardText())
    }

    @Test
    fun aShownPasswordIsNotCopiedEither() {
        putInClipboard(BEFORE)
        compose.setContent {
            PreviewTheme {
                PasswordField(
                    value = PASSWORD,
                    onValueChange = {},
                    label = LABEL,
                    visible = true,
                    onToggleVisible = {},
                    showLabel = LABEL,
                    hideLabel = LABEL,
                )
            }
        }

        copyField(0, PASSWORD.length)

        assertEquals(BEFORE, clipboardText())
    }

    @Test
    fun aCommonFieldDoesCopySoTheCheckIsReal() {
        putInClipboard(BEFORE)
        compose.setContent {
            PreviewTheme { AppTextField(PASSWORD, {}, LABEL) }
        }

        copyField(0, PASSWORD.length)

        assertNotEquals(BEFORE, clipboardText())
    }

    @Test
    fun pastingStillWorksAndSpreadsTheCode() {
        putInClipboard(CODE.joinToString("-"))
        var groups by mutableStateOf(List(RECOVERY_CODE_GROUPS) { "" })
        compose.setContent {
            PreviewTheme {
                Column {
                    RecoveryCodeField(groups = groups, onGroupsChange = {
                        groups = it
                    }, label = LABEL)
                }
            }
        }

        val first = compose.onAllNodes(hasSetTextAction())[0]
        first.performClick()
        first.performSemanticsAction(SemanticsActions.PasteText)
        compose.waitForIdle()

        assertEquals(CODE, groups)
    }

    @Test
    fun pastingIntoACommonFieldWorksTheSameWay() {
        putInClipboard(PASSWORD)
        var typed by mutableStateOf("")
        compose.setContent {
            PreviewTheme { AppTextField(typed, { typed = it }, LABEL) }
        }

        val field = compose.onAllNodes(hasSetTextAction())[0]
        field.performClick()
        field.performSemanticsAction(SemanticsActions.PasteText)
        compose.waitForIdle()

        assertEquals(PASSWORD, typed)
    }

    @Test
    fun aCommonFieldInsidePrivateInputStillPastes() {
        putInClipboard(PASSWORD)
        var typed by mutableStateOf("")
        compose.setContent {
            PreviewTheme { PrivateInput { AppTextField(typed, { typed = it }, LABEL) } }
        }

        val field = compose.onAllNodes(hasSetTextAction())[0]
        field.performClick()
        field.performSemanticsAction(SemanticsActions.PasteText)
        compose.waitForIdle()

        assertEquals(PASSWORD, typed)
    }

    @Test
    fun aPasswordFieldStillPastesForPasswordManagers() {
        putInClipboard(PASSWORD)
        var typed by mutableStateOf("")
        compose.setContent {
            PreviewTheme {
                PasswordField(
                    value = typed,
                    onValueChange = { typed = it },
                    label = LABEL,
                    visible = false,
                    onToggleVisible = {},
                    showLabel = LABEL,
                    hideLabel = LABEL,
                )
            }
        }

        val field = compose.onAllNodes(hasSetTextAction())[0]
        field.performClick()
        field.performSemanticsAction(SemanticsActions.PasteText)
        compose.waitForIdle()

        assertEquals(PASSWORD, typed)
    }

    @Test
    fun theKeyboardIsAskedNotToLearnTheCode() {
        compose.setContent {
            PreviewTheme {
                RecoveryCodeField(groups = CODE, onGroupsChange = {}, label = LABEL)
            }
        }
        compose.onAllNodes(hasSetTextAction())[0].performClick()
        compose.waitForIdle()

        val info = EditorInfo()
        compose.runOnUiThread {
            val root = compose.activity.window.decorView as ViewGroup
            requireNotNull(root.findFocus() ?: firstComposeView(root)) { "Sin vista con foco" }
                .onCreateInputConnection(info)
        }

        assertTrue(info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0)
    }

    private fun firstComposeView(group: ViewGroup): View? {
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            if (child.javaClass.simpleName == "AndroidComposeView") return child
            if (child is ViewGroup) firstComposeView(child)?.let { return it }
        }
        return null
    }

    private companion object {
        const val LABEL = "Campo de prueba"
        const val BEFORE = "texto anterior de prueba"
        const val PASSWORD = "inventada-123"
        val CODE = listOf("7KQ2", "M9XA", "R4TB", "W8ZC", "1PQ0", "HJ5N", "D3EF")
    }
}
