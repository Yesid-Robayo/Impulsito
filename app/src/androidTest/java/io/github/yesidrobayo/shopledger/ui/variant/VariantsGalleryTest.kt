package io.github.yesidrobayo.shopledger.ui.variant

import android.graphics.Bitmap
import android.graphics.Color.TRANSPARENT
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.domain.model.settings.ColorPalette
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.previewFormats
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Capturas de la pantalla de variantes para la puerta de calidad visual: cada estado con la paleta
 * predeterminada y otra, en claro y en oscuro. La escala de la letra es la del sistema. Solo corre
 * con el argumento `captureDir`, y sin regla de Compose, que taparía la actividad de las capturas.
 */
@RunWith(AndroidJUnit4::class)
class VariantsGalleryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun drawsEveryStateInBothPalettesAndModes() {
        val dir =
            InstrumentationRegistry.getArguments().getString("captureDir")?.let { name ->
                File(instrumentation.targetContext.cacheDir, name).apply { mkdirs() }
            }
        assumeTrue("Sin captureDir no hay capturas", dir != null)
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            for (palette in listOf(ColorPalette.INDIGO_MUSTARD, ColorPalette.GARNET_HONEY)) {
                for (dark in listOf(false, true)) {
                    for ((name, state) in shots()) {
                        scenario.onActivity { activity ->
                            val bars = SystemBarStyle.auto(TRANSPARENT, TRANSPARENT) { dark }
                            activity.enableEdgeToEdge(
                                statusBarStyle = bars,
                                navigationBarStyle = bars,
                            )
                            activity.setContent {
                                AppTheme(palette = palette, darkTheme = dark) {
                                    CompositionLocalProvider(
                                        LocalAppFormats provides previewFormats(),
                                    ) { key(name) { VariantsScreen(state, {}) } }
                                }
                            }
                        }
                        instrumentation.waitForIdleSync()
                        SystemClock.sleep(SETTLE_MILLIS)
                        val mode = if (dark) "dark" else "light"
                        val file = File(
                            requireNotNull(dir),
                            "${palette.name.lowercase()}-$mode-$name.png",
                        )
                        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                        file.outputStream().use {
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                        }
                        bitmap.recycle()
                    }
                }
            }
        }
    }

    private fun shots() = with(VariantsScreenDeviceTest) {
        listOf(
            "two-axes" to TWO_AXES,
            "editing" to EDITING,
            "empty" to VariantsUiState(loading = false, parentName = "Camiseta", unit = COUNT),
            "notice" to TWO_AXES.copy(notice = VariantsNotice.ParentHasStock, expanded = null),
        )
    }

    private companion object {
        const val SETTLE_MILLIS = 900L
    }
}
