package io.github.yesidrobayo.shopledger.ui.phrase

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
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
 * La voz directa en Ajustes: apagada de inicio, no aparece sin servicio de voz, y encenderla
 * explica antes que el teléfono procesa la voz con su propio servicio.
 */
@RunWith(AndroidJUnit4::class)
class VoiceInputSettingTest {
    @get:Rule
    val compose = createComposeRule()

    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources

    private fun text(id: Int, vararg args: Any) = resources.getString(id, *args)

    @Test
    fun withoutAVoiceServiceTheSettingIsNotShown() {
        compose.setContent {
            PreviewTheme {
                VoiceInputRow(
                    available = false,
                    enabled = false,
                    confirming = false,
                    saveFailed = false,
                    onToggle = {},
                    onConfirm = {},
                    onDismiss = {},
                )
            }
        }

        compose.onNodeWithText(text(R.string.voice_input_setting)).assertDoesNotExist()
    }

    @Test
    fun itStartsOffAndTurningItOnAsksFirst() {
        val toggles = mutableListOf<Boolean>()
        compose.setContent {
            PreviewTheme {
                VoiceInputRow(
                    available = true,
                    enabled = false,
                    confirming = false,
                    saveFailed = false,
                    onToggle = { toggles += it },
                    onConfirm = {},
                    onDismiss = {},
                )
            }
        }

        compose.onNodeWithText(text(R.string.voice_input_setting)).assertIsOff().performClick()

        assertEquals(listOf(true), toggles)
    }

    @Test
    fun theNoticeSaysThePhoneProcessesTheVoice() {
        val confirmed = mutableListOf<Unit>()
        compose.setContent {
            PreviewTheme {
                VoiceInputRow(
                    available = true,
                    enabled = false,
                    confirming = true,
                    saveFailed = false,
                    onToggle = {},
                    onConfirm = { confirmed += Unit },
                    onDismiss = {},
                )
            }
        }
        val body = text(R.string.voice_input_confirm_body, text(R.string.app_name))

        compose.onNodeWithText(body).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.voice_input_confirm_action)).performClick()

        assertEquals(1, confirmed.size)
    }
}
