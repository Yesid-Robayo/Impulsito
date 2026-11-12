package io.github.yesidrobayo.shopledger.ui.product

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
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.region.NumberSymbols
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
 * Capturas del detalle de un producto para la puerta de calidad visual: cada estado con la paleta
 * predeterminada y otra, en claro y en oscuro. La escala de la letra es la del sistema: se corre una
 * vez por cada una.
 *
 * Solo corre con el argumento `captureDir`, que nombra la carpeta dentro de la caché de la app. El
 * producto es inventado.
 */
@RunWith(AndroidJUnit4::class)
class ProductDetailGalleryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val captureDir: File? =
        InstrumentationRegistry.getArguments().getString("captureDir")?.let { name ->
            File(instrumentation.targetContext.cacheDir, name).apply { mkdirs() }
        }

    @Test
    fun drawsEveryStateInBothPalettesAndModes() {
        val dir = captureDir
        assumeTrue("Sin captureDir no hay capturas", dir != null)
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            for (palette in listOf(ColorPalette.INDIGO_MUSTARD, ColorPalette.GARNET_HONEY)) {
                for (dark in listOf(false, true)) {
                    for ((name, state) in states()) {
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
                                        LocalDiscreetMode provides (name == DISCREET),
                                    ) { key(name) { ProductDetailScreen(state, {}) } }
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

    private fun states(): List<Pair<String, ProductDetailUiState>> {
        val s = ProductDetailSamples
        fun ready(overview: io.github.yesidrobayo.shopledger.domain.model.catalog.ProductOverview) =
            ProductDetailUiState(
                ProductDetailContent.Ready(overview, ProductDetailSamples.pricing(overview)),
            )
        return listOf(
            "low" to ready(s.overview()),
            DISCREET to ready(s.overview()),
            "prices" to ready(s.overview()).copy(tab = ProductDetailTab.PRICES),
            "negative" to ready(s.overview(s.product(onHand = -2_000L, cost = 0L))),
            "nocost" to ready(s.overview(s.product(cost = 0L), withHistory = false)),
            "loss" to ready(s.overview(s.product(price = 3_000_00L), withHistory = false)),
            "service" to
                ready(
                    s.overview(s.product(mode = ProductStockMode.UNTRACKED), withHistory = false),
                ),
            "archived" to ready(s.overview(s.product(archived = true))),
            "costsheet" to
                ready(s.overview()).copy(costSheet = CostSheet("3800,5", "Factura perdida")),
            "pricesheet" to ready(s.overview()).copy(
                priceSheet = PriceSheet(
                    "520000",
                    suggestion = PriceSuggestionDraft.open(s.pricing(s.overview()), SYMBOLS),
                ),
            ),
            "pricesheet-nocost" to s.overview(s.product(cost = 0L), withHistory = false).let {
                ready(it).copy(
                    priceSheet = PriceSheet(
                        "520000",
                        suggestion = PriceSuggestionDraft.open(s.pricing(it), SYMBOLS),
                        canFixCost = true,
                    ),
                )
            },
            "loading" to ProductDetailUiState(),
        )
    }

    private fun save(file: File) {
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot()) { "Sin captura" }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private companion object {
        const val SETTLE_MILLIS = 900L
        const val DISCREET = "discreet"
        val SYMBOLS = NumberSymbols(decimalSeparator = ',', minusSign = '-', grouping = null)
    }
}
