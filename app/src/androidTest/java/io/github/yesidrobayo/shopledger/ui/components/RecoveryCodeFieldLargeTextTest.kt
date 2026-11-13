package io.github.yesidrobayo.shopledger.ui.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Con la letra grande, cada casilla del código de recuperación muestra sus 4 caracteres enteros,
 * también los más anchos: un código que no se lee es un código perdido. Códigos inventados.
 */
@RunWith(AndroidJUnit4::class)
class RecoveryCodeFieldLargeTextTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun everyGroupShowsItsFourCharactersAtAnyLetterSize() {
        var scale by mutableStateOf(1f)
        val groups = List(RECOVERY_CODE_GROUPS) { "WMWM" }
        compose.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, scale)) {
                PreviewTheme { RecoveryCodeField(groups, {}, "Código") }
            }
        }
        for (next in listOf(1f, 1.5f, 2f)) {
            scale = next
            compose.waitForIdle()
            assertEveryGroupFits(next)
        }
    }

    private fun assertEveryGroupFits(scale: Float) {
        val padding = FIELD_PADDING_DP * context.resources.displayMetrics.density
        for (index in 0 until RECOVERY_CODE_GROUPS) {
            val label =
                context.getString(
                    R.string.a11y_recovery_code_group,
                    index + 1,
                    RECOVERY_CODE_GROUPS,
                )
            val node = compose.onNodeWithContentDescription(label).fetchSemanticsNode()
            val layouts = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
            val text = layouts.single().size.width
            val box = node.boundsInRoot.width
            // El texto cabe con el relleno del campo a los dos lados, sin cortarse.
            assertTrue(
                "Letra $scale, grupo ${index + 1}: texto $text, casilla $box",
                text + padding <= box,
            )
        }
    }

    private companion object {
        /** Los 16 dp de relleno a cada lado de un campo con borde, juntos. */
        const val FIELD_PADDING_DP = 32f
    }
}
