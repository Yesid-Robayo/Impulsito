package io.github.yesidrobayo.shopledger.ui.phrase

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
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
 * `Escuchar al tocar Decir` en Ajustes: no aparece sin servicio de voz, viene encendido, y
 * cambiarlo es inmediato en los dos sentidos, porque el micrófono se explica al usarlo.
 */
@RunWith(AndroidJUnit4::class)
class VoiceInputSettingTest {
    @get:Rule
    val compose = createComposeRule()

    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources

    private fun text(id: Int) = resources.getString(id)

    private fun show(available: Boolean, enabled: Boolean, onToggle: (Boolean) -> Unit = {}) {
        compose.setContent {
            PreviewTheme {
                VoiceInputRow(
                    available = available,
                    enabled = enabled,
                    saveFailed = false,
                    onToggle = onToggle,
                )
            }
        }
    }

    @Test
    fun withoutAVoiceServiceTheSettingIsNotShown() {
        show(available = false, enabled = true)

        compose.onNodeWithText(text(R.string.voice_input_setting)).assertDoesNotExist()
    }

    @Test
    fun itComesOnAndTurningItOffIsImmediate() {
        val toggles = mutableListOf<Boolean>()
        show(available = true, enabled = true) { toggles += it }

        compose.onNodeWithText(text(R.string.voice_input_setting)).assertIsOn().performClick()

        assertEquals(listOf(false), toggles)
        compose.onNodeWithText(text(R.string.voice_input_summary)).assertIsDisplayed()
    }

    @Test
    fun turningItOnAgainDoesNotAskFirst() {
        val toggles = mutableListOf<Boolean>()
        show(available = true, enabled = false) { toggles += it }

        compose.onNodeWithText(text(R.string.voice_input_setting)).assertIsOff().performClick()

        assertEquals(listOf(true), toggles)
        compose.onNodeWithText(text(R.string.permission_mic_title)).assertDoesNotExist()
    }

    @Test
    fun aFailedSaveIsSaid() {
        compose.setContent {
            PreviewTheme {
                VoiceInputRow(available = true, enabled = true, saveFailed = true, onToggle = {})
            }
        }

        compose.onNodeWithText(text(R.string.state_error_save)).assertIsDisplayed()
    }
}
