package io.github.yesidrobayo.shopledger.ui.stats

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
 * Capturas de `Números` para la puerta de calidad visual: el mes con su gráfica y la barra tocada, la
 * ganancia con la lista, el período sin movimientos, un día con pérdida y un monto de trece cifras,
 * el modo discreto, sin datos, sin zona y con error. Con la paleta predeterminada y otra, en claro y
 * en oscuro. Solo corre con `captureDir`; la letra grande y la ventana ancha se fijan en el emulador
 * antes de correrla.
 */
@RunWith(AndroidJUnit4::class)
class StatisticsGalleryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val captureDir: File? =
        InstrumentationRegistry.getArguments().getString("captureDir")?.let { name ->
            File(instrumentation.targetContext.cacheDir, name).apply { mkdirs() }
        }

    @Test
    fun drawsEveryStateInBothPalettesAndModes() {
        val dir = captureDir
        assumeTrue("Sin captureDir no hay capturas", dir != null)
        val scenes = listOf(
            Triple("month", previewStatisticsContent() as StatisticsUiState, false),
            Triple(
                "profitList",
                previewStatisticsContent(ChartMetric.PROFIT, showList = true),
                false,
            ),
            Triple("emptyPeriod", previewEmptyPeriod(), false),
            Triple("longDay", previewLongDay(), false),
            Triple("discreet", previewStatisticsContent(), true),
            Triple("noData", StatisticsUiState.NoData, false),
            Triple("unknownZone", StatisticsUiState.UnknownZone, false),
            Triple("error", StatisticsUiState.ReadError, false),
        )
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            for (palette in listOf(ColorPalette.INDIGO_MUSTARD, ColorPalette.GARNET_HONEY)) {
                for (dark in listOf(false, true)) {
                    for ((name, state, discreet) in scenes) {
                        scenario.onActivity { activity ->
                            val bars = SystemBarStyle.auto(TRANSPARENT, TRANSPARENT) { dark }
                            activity.enableEdgeToEdge(bars, bars)
                            activity.setContent {
                                AppTheme(palette = palette, darkTheme = dark) {
                                    CompositionLocalProvider(
                                        LocalAppFormats provides previewFormats(),
                                        LocalDiscreetMode provides discreet,
                                    ) {
                                        StatisticsScreen(uiState = state, onAction = {})
                                    }
                                }
                            }
                        }
                        instrumentation.waitForIdleSync()
                        SystemClock.sleep(SETTLE_MILLIS)
                        val mode = if (dark) "dark" else "light"
                        save(
                            File(
                                requireNotNull(dir),
                                "${palette.name.lowercase()}-$mode-$name.png",
                            ),
                        )
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
        /** La gráfica anima su entrada: se espera a que termine antes de capturar. */
        const val SETTLE_MILLIS = 1_200L
    }
}
