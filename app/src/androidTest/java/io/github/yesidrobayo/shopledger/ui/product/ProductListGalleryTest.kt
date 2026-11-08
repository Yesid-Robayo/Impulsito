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
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.settings.ColorPalette
import io.github.yesidrobayo.shopledger.ui.components.TonalFab
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.format.LocalDiscreetMode
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.previewFormats
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Capturas de la lista de productos para la puerta de calidad visual: cada estado con la paleta
 * predeterminada y otra, en claro y en oscuro. La escala de la letra y el tamaño de la ventana son
 * los del sistema: se corre una vez por cada combinación que se quiera revisar.
 *
 * Solo corre con el argumento `captureDir`, que nombra la carpeta dentro de la caché de la app. Los
 * productos son inventados.
 */
@RunWith(AndroidJUnit4::class)
class ProductListGalleryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val captureDir: File? =
        InstrumentationRegistry.getArguments().getString("captureDir")?.let { name ->
            File(instrumentation.targetContext.cacheDir, name).apply { mkdirs() }
        }

    @Test
    fun drawsEveryStateInBothPalettesAndModes() {
        val dir = captureDir
        assumeTrue("Sin captureDir no hay capturas", dir != null)
        val fabLabel = instrumentation.targetContext.getString(R.string.product_new_title)
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
                                    ) {
                                        // Cada estado con su propia posición guardada, como
                                        // una pantalla nueva.
                                        key(name) {
                                            ProductListScreen(
                                                uiState = state,
                                                onAction = {},
                                                floatingAction = {
                                                    TonalFab(fabLabel, {})
                                                },
                                            )
                                        }
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

    private fun states(): List<Pair<String, ProductListUiState>> {
        val units = mapOf(SAMPLE_UNIT.id to SAMPLE_UNIT)
        val few = listOf(
            sampleProduct(1L, "Aceite 900 ml", 12_500L, 9_800L, 3_000L),
            sampleProduct(2L, "Arroz 1 kg", 4_200L, 3_000L, 23_000L),
            sampleProduct(
                3L,
                "Café molido de altura en bolsa resellable con válvula, tostión media, 500 gramos",
                1_234_567_890_123L,
                800_000_000_000L,
                -2_000L,
            ),
            sampleProduct(
                4L,
                "Corte de cabello",
                15_000L,
                0L,
                0L,
                0L,
                ProductStockMode.UNTRACKED,
                4_000L,
            ),
            sampleProduct(5L, "Huevos x 30", 16_800L, 17_500L, 11_000L),
            sampleProduct(6L, "Jabón de avena", 0L, 0L, 0L),
            sampleProduct(7L, "Queso campesino", 22_000L, 0L, 6_000L),
        ).sortedBy { it.details.normalizedName }
        val many = (1..80).map(::sampleProduct).sortedBy { it.details.normalizedName }
        return listOf(
            "list" to ProductListUiState(content = ProductListItems.build(few, units, "")),
            DISCREET to ProductListUiState(content = ProductListItems.build(few, units, "")),
            "headers" to ProductListUiState(content = ProductListItems.build(many, units, "")),
            "search" to ProductListUiState("arro", ProductListItems.build(few, units, "arro")),
            "noresults" to ProductListUiState("acite", ProductListItems.build(few, units, "acite")),
            "empty" to ProductListUiState(content = ProductListContent.EmptyCatalog),
            "error" to ProductListUiState(content = ProductListContent.ReadError),
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
    }
}
