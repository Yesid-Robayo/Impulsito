package io.github.yesidrobayo.shopledger.ui.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * El micrófono de la barra de arriba de la venta: con la navegación abre `Decir o escribir`; sin
 * ella, en una pantalla suelta, no aparece.
 */
@RunWith(AndroidJUnit4::class)
class SayButtonTest {
    @get:Rule
    val compose = createComposeRule()

    private val say =
        InstrumentationRegistry.getInstrumentation().targetContext.resources
            .getString(R.string.phrase_title)

    @Test
    fun withNavigationTheMicrophoneOpensSayOrType() {
        var opened = 0
        compose.setContent {
            PreviewTheme {
                CompositionLocalProvider(LocalSayOpener provides { opened++ }) { SayButton() }
            }
        }

        compose.onNodeWithContentDescription(say).performClick()

        assertEquals(1, opened)
    }

    @Test
    fun withoutNavigationThereIsNoMicrophone() {
        compose.setContent { PreviewTheme { SayButton() } }

        assertEquals(0, compose.onAllNodesWithContentDescription(say).fetchSemanticsNodes().size)
    }
}
