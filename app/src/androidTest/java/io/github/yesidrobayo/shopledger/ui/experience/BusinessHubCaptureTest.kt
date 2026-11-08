package io.github.yesidrobayo.shopledger.ui.experience

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
import io.github.yesidrobayo.shopledger.domain.model.experience.BusinessTool
import io.github.yesidrobayo.shopledger.domain.model.settings.ColorPalette
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Las capturas del centro `Negocio` para la puerta de calidad visual: un negocio nuevo, una
 * herramienta en uso y una búsqueda sin resultados, con la paleta predeterminada y otra, en claro y
 * en oscuro. La escala de la letra y el ancho de la ventana son los del teléfono.
 *
 * Solo corre con el argumento `captureDir`.
 */
@RunWith(AndroidJUnit4::class)
class BusinessHubCaptureTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val captureDir: File? =
        InstrumentationRegistry.getArguments().getString("captureDir")?.let { name ->
            File(instrumentation.targetContext.cacheDir, name).apply { mkdirs() }
        }

    private val states =
        mapOf(
            "new" to BusinessHubUiState(inUse = emptySet()),
            "in-use" to BusinessHubUiState(inUse = setOf(BusinessTool.UNITS)),
            "search-empty" to BusinessHubUiState(inUse = emptySet(), query = "zzz"),
        )

    @Test
    fun capturesEveryStateInBothPalettesAndModes() {
        val dir = captureDir
        assumeTrue("Sin captureDir no hay capturas", dir != null)
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
                                BusinessHubScreen(uiState, rememberHubTools(), {}, {}, {})
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
