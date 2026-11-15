package io.github.yesidrobayo.shopledger.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.ui.sale.SaleHistoryScreen
import io.github.yesidrobayo.shopledger.ui.sale.SaleHistoryUiState
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * En el teléfono: con datos de ejemplo, la banda fija va encima de la pantalla, dice que son datos
 * de ejemplo y que lo registrado no se guarda, y `Salir` se pide una sola vez. La pantalla de abajo
 * sigue entera y se puede usar. Sin demostración no hay banda.
 */
@RunWith(AndroidJUnit4::class)
class DemoBannerTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val title = context.getString(R.string.demo_banner_title)
    private val notSaved = context.getString(R.string.demo_not_saved)
    private val exit = context.getString(R.string.demo_banner_exit)
    private val settings = context.getString(R.string.a11y_settings)
    private var exits = 0
    private var opened = 0

    private fun show(active: Boolean, exiting: Boolean = false) {
        val screen: @Composable () -> Unit = { SaleHistoryScreen(SaleHistoryUiState(), {}) }
        compose.setContent {
            PreviewTheme {
                CompositionLocalProvider(LocalSettingsOpener provides { opened += 1 }) {
                    DemoFrame(active = active, onExit = { exits += 1 }, exiting = exiting) {
                        screen()
                    }
                }
            }
        }
    }

    @Test
    fun theBannerSaysItIsSampleDataAboveTheScreen() {
        show(active = true)

        compose.onNodeWithText(title).assertIsDisplayed()
        compose.onNodeWithText(notSaved).assertIsDisplayed()
        // La pantalla de abajo sigue entera: su engranaje responde.
        compose.onNodeWithContentDescription(settings).performClick()
        compose.waitForIdle()
        assertEquals(1, opened)
    }

    @Test
    fun exitIsAskedOnce() {
        show(active = true)

        compose.onNodeWithText(exit).performClick()
        compose.waitForIdle()

        assertEquals(1, exits)
    }

    @Test
    fun whileTheAppRestartsExitIsNotRepeated() {
        show(active = true, exiting = true)

        compose.onNodeWithText(exit).performClick()
        compose.waitForIdle()

        assertEquals(0, exits)
    }

    @Test
    fun withoutTheDemoThereIsNoBanner() {
        show(active = false)

        compose.onAllNodesWithText(title).assertCountEquals(0)
        compose.onAllNodesWithText(exit).assertCountEquals(0)
        compose.onNodeWithContentDescription(settings).assertIsDisplayed()
    }
}
