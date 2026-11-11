package io.github.yesidrobayo.shopledger.ui.onboarding

import android.graphics.Bitmap
import android.graphics.Color.TRANSPARENT
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
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
 * Capturas del primer uso para la puerta de calidad visual: la bienvenida, sin red, la hoja `Traer
 * datos de otro teléfono`, las copias encontradas y la protección. Datos inventados. Solo corre con
 * `captureDir`.
 */
@RunWith(AndroidJUnit4::class)
class OnboardingGalleryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val captureDir: File? =
        InstrumentationRegistry.getArguments().getString("captureDir")?.let { name ->
            File(instrumentation.targetContext.cacheDir, name).apply { mkdirs() }
        }

    @Test
    fun drawsTheOnboardingInBothModes() {
        val dir = captureDir
        assumeTrue("Sin captureDir no hay capturas", dir != null)
        val scenes = listOf<Pair<String, @Composable () -> Unit>>(
            "welcome" to { WelcomeStep(PREVIEW_WELCOME, PREVIEW_ONBOARDING_ZONE, {}) },
            "offline" to
                { WelcomeStep(PREVIEW_WELCOME.copy(offline = true), PREVIEW_ONBOARDING_ZONE, {}) },
            "bring" to
                {
                    WelcomeStep(PREVIEW_WELCOME.copy(bringSheet = true), PREVIEW_ONBOARDING_ZONE, {
                    })
                },
            "found" to {
                WelcomeStep(
                    PREVIEW_WELCOME.copy(found = FoundCopies.Found(listOf(PREVIEW_FOUND_COPY))),
                    PREVIEW_ONBOARDING_ZONE,
                    {},
                )
            },
            "protection" to {
                ProtectionStep(PREVIEW_WELCOME.copy(step = OnboardingStepId.PROTECTION), {})
            },
        )
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            for (dark in listOf(false, true)) {
                for ((name, scene) in scenes) {
                    scenario.onActivity { activity ->
                        val bars = SystemBarStyle.auto(TRANSPARENT, TRANSPARENT) { dark }
                        activity.enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
                        activity.setContent {
                            AppTheme(palette = ColorPalette.INDIGO_MUSTARD, darkTheme = dark) {
                                CompositionLocalProvider(
                                    LocalAppFormats provides previewFormats(),
                                    LocalDiscreetMode provides false,
                                ) {
                                    key(name, dark) { scene() }
                                }
                            }
                        }
                    }
                    instrumentation.waitForIdleSync()
                    SystemClock.sleep(SETTLE_MILLIS)
                    val mode = if (dark) "dark" else "light"
                    save(File(requireNotNull(dir), "$mode-onboarding-$name.png"))
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
        const val SETTLE_MILLIS = 900L
    }
}
