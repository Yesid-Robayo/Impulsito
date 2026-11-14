package io.github.yesidrobayo.shopledger.ui.i18n

import android.graphics.Bitmap
import android.graphics.Color.TRANSPARENT
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.domain.model.settings.ColorPalette
import io.github.yesidrobayo.shopledger.localization.InstalledLanguages
import io.github.yesidrobayo.shopledger.localization.SystemLocales
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Las capturas de la pantalla de idioma para la puerta de calidad visual: siguiendo al teléfono y
 * con un idioma elegido, con la paleta predeterminada y otra, en claro y en oscuro. La escala de la
 * letra y el ancho de la ventana son los del teléfono.
 *
 * Solo corre con el argumento `captureDir`.
 */
@RunWith(AndroidJUnit4::class)
class LanguageSettingsCaptureTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val captureDir: File? =
        InstrumentationRegistry.getArguments().getString("captureDir")?.let { name ->
            File(instrumentation.targetContext.cacheDir, name).apply { mkdirs() }
        }

    @Test
    fun capturesBothStatesInBothPalettesAndModes() {
        val dir = captureDir
        assumeTrue("Sin captureDir no hay capturas", dir != null)
        val installed = InstalledLanguages.tags(instrumentation.targetContext.resources)
        val phone = SystemLocales.first(instrumentation.targetContext)
        val facts = languageFacts(installed, "en", phone)
        val states =
            mapOf(
                "system" to LanguageSettingsUiState(selected = null, installed = installed),
                "chosen" to LanguageSettingsUiState(selected = "es", installed = installed),
            )
        for (palette in listOf(ColorPalette.INDIGO_MUSTARD, ColorPalette.GARNET_HONEY)) {
            for (dark in listOf(false, true)) {
                states.forEach { (name, uiState) ->
                    compose.runOnUiThread {
                        val bars = SystemBarStyle.auto(TRANSPARENT, TRANSPARENT) { dark }
                        compose.activity.enableEdgeToEdge(
                            statusBarStyle = bars,
                            navigationBarStyle = bars,
                        )
                        compose.activity.setContent {
                            AppTheme(palette = palette, darkTheme = dark) {
                                LanguageSettingsScreen(
                                    uiState = uiState,
                                    facts = facts,
                                    onAction = {},
                                    onBack = {},
                                    onOpenFormat = {},
                                )
                            }
                        }
                    }
                    compose.waitForIdle()
                    SystemClock.sleep(SETTLE_MILLIS)
                    val shot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                    val file =
                        File(
                            requireNotNull(dir),
                            "${palette.name.lowercase()}-${if (dark) "dark" else "light"}-$name.png",
                        )
                    file.outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    shot.recycle()
                }
            }
        }
    }

    private companion object {
        const val SETTLE_MILLIS = 700L
    }
}
