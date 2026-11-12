package io.github.yesidrobayo.shopledger.ui.stock

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
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.settings.ColorPalette
import io.github.yesidrobayo.shopledger.domain.model.stock.StockAdjustmentReason
import io.github.yesidrobayo.shopledger.ui.components.PreviewSamples
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.format.LocalDiscreetMode
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.previewFormats
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Capturas del ajuste de stock para la puerta de calidad visual: el conteo del ejemplo de las
 * reglas, una salida que deja el stock negativo con nombre de 80 caracteres, otro motivo con
 * entrada y su nota pedida, el inventario inicial y un producto sin costo en modo discreto. Con la
 * paleta predeterminada y otra, en claro y en oscuro. Solo corre con `captureDir`; la letra grande
 * se fija en el emulador antes de correrla. Productos y montos inventados.
 */
@RunWith(AndroidJUnit4::class)
class StockAdjustmentGalleryTest {
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
            Triple(
                "count",
                StockAdjustmentUiState(
                    content = StockAdjustmentContent.Ready(previewSubject()),
                    quantityText = "10",
                    preview = preview(10_000L, -2_000L, -2_710L),
                ),
                false,
            ),
            Triple(
                "negative",
                StockAdjustmentUiState(
                    content = StockAdjustmentContent.Ready(
                        previewSubject(
                            PreviewSamples.LONG_NAME,
                            1_000L,
                            999_999_999L,
                            999_999_999L,
                        ),
                    ),
                    mode = AdjustmentMode.CHANGE,
                    reason = StockAdjustmentReason.EXPIRED,
                    quantityText = "3",
                    preview = preview(-2_000L, -3_000L, -2_999_999_997L),
                ),
                false,
            ),
            Triple(
                "otherIn",
                StockAdjustmentUiState(
                    content = StockAdjustmentContent.Ready(previewSubject()),
                    mode = AdjustmentMode.CHANGE,
                    reason = StockAdjustmentReason.OTHER,
                    increase = true,
                    quantityText = "1",
                    errors = mapOf(AdjustmentField.NOTE to AdjustmentProblem.NoteRequired),
                    preview = preview(13_000L, 1_000L, 1_355L),
                ),
                false,
            ),
            Triple(
                "opening",
                StockAdjustmentUiState(
                    content = StockAdjustmentContent.Ready(
                        previewSubject(onHand = 0L, value = 0L, acceptsOpening = true),
                    ),
                    mode = AdjustmentMode.OPENING,
                    quantityText = "24",
                    unitCostText = "1.200",
                    preview = preview(24_000L, 24_000L, 28_800L),
                ),
                false,
            ),
            Triple(
                "noCostDiscreet",
                StockAdjustmentUiState(
                    content = StockAdjustmentContent.Ready(
                        previewSubject(average = 0L, value = 0L),
                    ),
                    mode = AdjustmentMode.CHANGE,
                    quantityText = "2",
                    saveFailed = true,
                    preview = preview(10_000L, -2_000L, 0L),
                ),
                true,
            ),
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
                                        StockAdjustmentScreen(uiState = state, onAction = {})
                                    }
                                }
                            }
                        }
                        instrumentation.waitForIdleSync()
                        SystemClock.sleep(SETTLE_MILLIS)
                        val mode = if (dark) "dark" else "light"
                        val file = "${palette.name.lowercase()}-$mode-$name.png"
                        save(File(requireNotNull(dir), file))
                    }
                }
            }
        }
    }

    private fun preview(left: Long, difference: Long, value: Long) =
        AdjustmentPreview(QuantityMilli(left), QuantityMilli(difference), Money(value))

    private fun save(file: File) {
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot()) { "Sin captura" }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private companion object {
        const val SETTLE_MILLIS = 900L
    }
}
