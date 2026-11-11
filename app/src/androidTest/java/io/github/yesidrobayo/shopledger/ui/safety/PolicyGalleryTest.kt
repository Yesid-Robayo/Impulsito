package io.github.yesidrobayo.shopledger.ui.safety

import android.graphics.Bitmap
import android.graphics.Color.TRANSPARENT
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.domain.model.settings.ColorPalette
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.format.LocalDiscreetMode
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.previewFormats
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Capturas de la aceptación para la puerta de calidad visual: la hoja `Política y términos`, la misma
 * con el guardado fallido, el diálogo `Cambió el uso de los datos` y los términos completos, en claro
 * y en oscuro con la paleta predeterminada. Solo corre con `captureDir`.
 */
@RunWith(AndroidJUnit4::class)
class PolicyGalleryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val captureDir: File? =
        InstrumentationRegistry.getArguments().getString("captureDir")?.let { name ->
            File(instrumentation.targetContext.cacheDir, name).apply { mkdirs() }
        }

    @Test
    fun drawsEveryQuestionInBothModes() {
        val dir = captureDir
        assumeTrue("Sin captureDir no hay capturas", dir != null)
        val scenes = listOf(
            "accept" to PolicyPromptState(question = PolicyQuestion.ACCEPT_TO_CONNECT),
            "notSaved" to
                PolicyPromptState(question = PolicyQuestion.ACCEPT_TO_CONNECT, notSaved = true),
            "changed" to PolicyPromptState(question = PolicyQuestion.CHANGED),
            "terms" to PolicyPromptState(question = PolicyQuestion.CHANGED, terms = true),
        )
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            for (dark in listOf(false, true)) {
                for ((name, state) in scenes) {
                    scenario.onActivity { activity ->
                        val bars = SystemBarStyle.auto(TRANSPARENT, TRANSPARENT) { dark }
                        activity.enableEdgeToEdge(bars, bars)
                        activity.setContent {
                            AppTheme(palette = ColorPalette.INDIGO_MUSTARD, darkTheme = dark) {
                                CompositionLocalProvider(
                                    LocalAppFormats provides previewFormats(),
                                    LocalDiscreetMode provides false,
                                ) {
                                    Box(
                                        Modifier.fillMaxSize().background(
                                            MaterialTheme.colorScheme.surface,
                                        ),
                                    )
                                    PolicyQuestions(state, {}, {}, {}, {})
                                }
                            }
                        }
                    }
                    instrumentation.waitForIdleSync()
                    SystemClock.sleep(SETTLE_MILLIS)
                    val mode = if (dark) "dark" else "light"
                    save(File(requireNotNull(dir), "$mode-$name.png"))
                }
            }
        }
    }

    private fun save(file: File) {
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot()) { "Sin captura" }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private companion object {
        const val SETTLE_MILLIS = 1_200L
    }
}
