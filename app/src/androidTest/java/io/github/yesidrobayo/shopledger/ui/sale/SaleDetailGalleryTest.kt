package io.github.yesidrobayo.shopledger.ui.sale

import android.graphics.Bitmap
import android.graphics.Color.TRANSPARENT
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
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
 * Capturas del detalle de una venta para la puerta de calidad visual: activa, anulada, en modo discreto y con la hoja de anular, con la
 * paleta predeterminada y otra, en claro y en oscuro. Solo corre con `captureDir`.
 */
@RunWith(AndroidJUnit4::class)
class SaleDetailGalleryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val captureDir: File? =
        InstrumentationRegistry.getArguments().getString("captureDir")?.let { name ->
            File(instrumentation.targetContext.cacheDir, name).apply { mkdirs() }
        }

    @Test
    fun drawsEveryStateInBothStylesPalettesAndModes() {
        val dir = captureDir
        assumeTrue("Sin captureDir no hay capturas", dir != null)
        val scenes = listOf(
            "active" to SaleDetailUiState.Ready(saleDetailView()),
            "voided" to SaleDetailUiState.Ready(saleDetailView(voided = true)),
            DISCREET to SaleDetailUiState.Ready(saleDetailView()),
            "charges" to SaleDetailUiState.Ready(saleDetailView(withCharges = true)),
            "$DISCREET-charges" to SaleDetailUiState.Ready(saleDetailView(withCharges = true)),
            "void" to SaleDetailUiState.Ready(saleDetailView(), sheet = VoidSheet()),
            "correct" to SaleDetailUiState.Ready(saleDetailView(), correcting = true),
            "voidfailed" to SaleDetailUiState.Ready(
                saleDetailView(),
                sheet = VoidSheet(note = "Devolvió todo"),
                status = VoidStatus.FAILED,
            ),
        )
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            for (palette in listOf(ColorPalette.INDIGO_MUSTARD, ColorPalette.GARNET_HONEY)) {
                for (dark in listOf(false, true)) {
                    for ((name, state) in scenes) {
                        scenario.onActivity { activity ->
                            val bars = SystemBarStyle.auto(TRANSPARENT, TRANSPARENT) { dark }
                            activity.enableEdgeToEdge(bars, bars)
                            activity.setContent {
                                AppTheme(palette = palette, darkTheme = dark) {
                                    CompositionLocalProvider(
                                        LocalAppFormats provides previewFormats(),
                                        LocalDiscreetMode provides name.startsWith(DISCREET),
                                    ) { SaleDetailScreen(state, {}) }
                                }
                            }
                        }
                        instrumentation.waitForIdleSync()
                        SystemClock.sleep(SETTLE_MILLIS)
                        val mode = if (dark) "dark" else "light"
                        val prefix = palette.name.lowercase()
                        save(File(requireNotNull(dir), "$prefix-$mode-$name.png"))
                    }
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
        const val DISCREET = "discreet"
    }
}
