package io.github.yesidrobayo.shopledger.ui.extra

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.settings.ColorPalette
import io.github.yesidrobayo.shopledger.ui.components.PreviewSamples
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.format.LocalDiscreetMode
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.previewFormats
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Los extras de un producto en el dispositivo: la lista con su marca de archivado, el aviso de un
 * cambio que no se guardó, la variante vacía y las acciones. Con `captureDir`, las capturas de la
 * puerta de calidad en claro y en oscuro. Datos inventados.
 */
@RunWith(AndroidJUnit4::class)
class ExtrasScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val resources = instrumentation.targetContext.resources
    private val actions = mutableListOf<ExtrasAction>()

    private fun text(id: Int) = resources.getString(id)

    @Test
    fun theListShowsEachExtraAndRemovesOne() {
        show(previewExtras().copy(saveFailed = false))

        compose.onNodeWithText(text(R.string.status_archived)).assertIsDisplayed()
        compose.onAllNodesWithText(text(R.string.action_remove))[0].performClick()
        compose.onNodeWithText(text(R.string.extras_add_action)).performClick()

        assertEquals(listOf(ExtrasAction.Remove(11L), ExtrasAction.OpenPicker), actions)
    }

    @Test
    fun aChangeThatWasNotSavedIsToldAboveTheButton() {
        show(previewExtras())

        compose.onNodeWithText(text(R.string.extras_error_save)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.extras_add_action)).assertIsDisplayed()
    }

    @Test
    fun noExtrasExplainWhatTheyAreFor() {
        show(ExtrasUiState(loading = false, productName = PreviewSamples.SHORT_NAME))

        compose.onNodeWithText(text(R.string.extras_empty_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.extras_empty_body)).assertIsDisplayed()
    }

    @Test
    fun drawsTheExtrasInBothModes() {
        val name = InstrumentationRegistry.getArguments().getString("captureDir")
        assumeTrue("Sin captureDir no hay capturas", name != null)
        val dir = File(instrumentation.targetContext.cacheDir, requireNotNull(name)).apply {
            mkdirs()
        }
        for (dark in listOf(false, true)) {
            val mode = if (dark) "dark" else "light"
            show(previewExtras(), dark)
            SystemClock.sleep(SETTLE_MILLIS)
            save(File(dir, "$mode-extras.png"))
            show(ExtrasUiState(loading = false, productName = PreviewSamples.LONG_NAME), dark)
            SystemClock.sleep(SETTLE_MILLIS)
            save(File(dir, "$mode-extras-empty.png"))
        }
    }

    private fun show(state: ExtrasUiState, dark: Boolean = false) {
        compose.runOnUiThread {
            compose.activity.setContent {
                AppTheme(palette = ColorPalette.INDIGO_MUSTARD, darkTheme = dark) {
                    CompositionLocalProvider(
                        LocalAppFormats provides previewFormats(),
                        LocalDiscreetMode provides false,
                    ) { ExtrasScreen(state, { actions += it }) }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun save(file: File) {
        val bitmap =
            requireNotNull(instrumentation.uiAutomation.takeScreenshot()) { "Sin captura" }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private companion object {
        const val SETTLE_MILLIS = 900L
    }
}
