package io.github.yesidrobayo.shopledger.ui.lot

import android.graphics.Bitmap
import android.graphics.Color.TRANSPARENT
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.settings.ColorPalette
import io.github.yesidrobayo.shopledger.domain.model.stock.LotChoice
import io.github.yesidrobayo.shopledger.domain.model.stock.LotChoices
import io.github.yesidrobayo.shopledger.domain.model.stock.LotDue
import io.github.yesidrobayo.shopledger.domain.model.stock.LotFilter
import io.github.yesidrobayo.shopledger.domain.model.stock.LotStock
import io.github.yesidrobayo.shopledger.domain.model.stock.StockAdjustmentReason
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.format.LocalDiscreetMode
import io.github.yesidrobayo.shopledger.ui.sale.NewSaleScreen
import io.github.yesidrobayo.shopledger.ui.sale.lotLineState
import io.github.yesidrobayo.shopledger.ui.stock.AdjustmentMode
import io.github.yesidrobayo.shopledger.ui.stock.StockAdjustmentContent
import io.github.yesidrobayo.shopledger.ui.stock.StockAdjustmentScreen
import io.github.yesidrobayo.shopledger.ui.stock.StockAdjustmentUiState
import io.github.yesidrobayo.shopledger.ui.stock.previewSubject
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.previewFormats
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Capturas de los lotes para la puerta de calidad visual: la pantalla de lotes en cada filtro, vacía
 * y en modo discreto, la hoja de un lote, la salida por vencido con su selector y la línea de una
 * venta con lote vencido. Con la paleta predeterminada y otra, en claro y en oscuro. Solo corre con
 * `captureDir`; la letra grande y el ancho de 360 dp se fijan en el emulador antes de correrla.
 * Lotes y productos inventados.
 */
@RunWith(AndroidJUnit4::class)
class LotsGalleryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val captureDir: File? =
        InstrumentationRegistry.getArguments().getString("captureDir")?.let { name ->
            File(instrumentation.targetContext.cacheDir, name).apply { mkdirs() }
        }

    @Test
    fun drawsEveryStateInBothPalettesAndModes() {
        val dir = captureDir
        assumeTrue("Sin captureDir no hay capturas", dir != null)
        val detail = LotSheet.Detail(
            LotSamples.SOON.lot.id,
            LotSamples.MOVEMENTS,
            LotSamples.MOVEMENTS.last().date,
        )
        val scenes: List<Triple<String, Boolean, @Composable () -> Unit>> = listOf(
            Triple("expiring", false) { LotsScreen(LotSamples.ready(), {}) },
            Triple("expired", false) { LotsScreen(LotSamples.ready(LotFilter.EXPIRED), {}) },
            Triple("unassigned", false) {
                LotsScreen(LotSamples.ready(LotFilter.UNASSIGNED), {})
            },
            Triple("empty", false) {
                LotsScreen(LotSamples.ready(overview = LotSamples.EMPTY), {})
            },
            Triple("discreet", true) { LotsScreen(LotSamples.ready(LotFilter.ALL), {}) },
            Triple("detail", false) { LotsScreen(LotSamples.ready(sheet = detail), {}) },
            Triple("takeOut", false) { StockAdjustmentScreen(takeOutState(), {}) },
            Triple("saleLine", false) { NewSaleScreen(lotLineState(), {}) },
        )
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            for (palette in listOf(ColorPalette.INDIGO_MUSTARD, ColorPalette.GARNET_HONEY)) {
                for (dark in listOf(false, true)) {
                    for ((name, discreet, content) in scenes) {
                        scenario.onActivity { activity ->
                            val bars = SystemBarStyle.auto(TRANSPARENT, TRANSPARENT) { dark }
                            activity.enableEdgeToEdge(bars, bars)
                            activity.setContent {
                                AppTheme(palette = palette, darkTheme = dark) {
                                    CompositionLocalProvider(
                                        LocalAppFormats provides previewFormats(),
                                        LocalDiscreetMode provides discreet,
                                    ) { content() }
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

    /** `Dar salida` desde un lote: la salida por vencido, con el lote vencido elegido. */
    private fun takeOutState(): StockAdjustmentUiState {
        val expired = LotSamples.EXPIRED.lot
        val date = requireNotNull(expired.expiryDate)
        val later = requireNotNull(LotSamples.LATER.lot.expiryDate)
        return StockAdjustmentUiState(
            content = StockAdjustmentContent.Ready(previewSubject()),
            mode = AdjustmentMode.CHANGE,
            reason = StockAdjustmentReason.EXPIRED,
            quantityText = "2",
            lots = LotChoices(
                listOf(
                    LotChoice(
                        LotStock(expired.id, expired.code, date, false, QuantityMilli(2_000L)),
                        LotDue(date, -4, soon = true),
                    ),
                    LotChoice(
                        LotStock(2L, "A-121", later, false, QuantityMilli(2_000L)),
                        LotDue(later, 14, soon = true),
                    ),
                ),
                null,
            ),
            lotId = expired.id,
        )
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
