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
 * El micrófono de las pestañas: con la navegación, va junto al engranaje y abre `Decir o
 * escribir`; sin ella, en una pantalla suelta, no aparece, y el engranaje queda solo.
 */
@RunWith(AndroidJUnit4::class)
class SayButtonTest {
    @get:Rule
    val compose = createComposeRule()

    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
    private val say = resources.getString(R.string.phrase_title)
    private val settings = resources.getString(R.string.a11y_settings)

    @Test
    fun theTabsShowTheMicrophoneNextToTheGearAndItOpensSayOrType() {
        var opened = 0
        compose.setContent {
            PreviewTheme {
                CompositionLocalProvider(LocalSayOpener provides { opened++ }) { SettingsButton() }
            }
        }

        compose.onNodeWithContentDescription(settings).assertExists()
        compose.onNodeWithContentDescription(say).performClick()

        assertEquals(1, opened)
    }

    @Test
    fun withoutNavigationThereIsNoMicrophone() {
        compose.setContent { PreviewTheme { SettingsButton() } }

        compose.onNodeWithContentDescription(settings).assertExists()
        assertEquals(0, compose.onAllNodesWithContentDescription(say).fetchSemanticsNodes().size)
    }
}
