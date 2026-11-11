package io.github.yesidrobayo.shopledger.ui.sale

import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.ui.components.AppSnackbarHost
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * El mensaje de venta registrada en el teléfono, con el reloj de la prueba: `Deshacer` se ve 10
 * segundos y luego se va solo, y tocarlo pide deshacer. Corre sin TalkBack, que alarga el mensaje
 * según la preferencia del sistema.
 */
@RunWith(AndroidJUnit4::class)
class SaleUndoMessageTest {
    @get:Rule
    val compose = createComposeRule()

    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
    private val registered = resources.getString(R.string.confirm_sale_registered, "4.200")
    private val undo = resources.getString(R.string.action_undo)

    private var result: SnackbarResult? = null

    private fun showRegistered() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            PreviewTheme {
                val host = remember { SnackbarHostState() }
                LaunchedEffect(Unit) {
                    result = host.showSnackbar(SaleUndo.registeredMessage(registered, undo))
                }
                AppSnackbarHost(host)
            }
        }
        compose.mainClock.advanceTimeByFrame()
    }

    @Test
    fun undoIsOfferedForTenSecondsAndThenLeaves() {
        showRegistered()

        compose.mainClock.advanceTimeBy(BEFORE_MS)
        compose.onNodeWithText(undo).assertIsDisplayed()
        compose.onNodeWithText(registered).assertIsDisplayed()
        assertNull(result)

        compose.mainClock.advanceTimeBy(AFTER_MS - BEFORE_MS)
        compose.waitForIdle()

        compose.onNodeWithText(undo).assertDoesNotExist()
        assertEquals(SnackbarResult.Dismissed, result)
    }

    @Test
    fun tappingUndoAsksToUndo() {
        showRegistered()
        compose.mainClock.advanceTimeBy(BEFORE_MS)

        compose.onNodeWithText(undo).performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()

        assertEquals(SnackbarResult.ActionPerformed, result)
    }

    private companion object {
        /** Poco antes de los 10 segundos: el mensaje sigue ahí. */
        const val BEFORE_MS = 9_500L

        /** Pasados los 10 segundos y la salida del mensaje. */
        const val AFTER_MS = 11_000L
    }
}
