package io.github.yesidrobayo.shopledger.ui.pricing

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
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceListKind
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
 * Capturas de la pantalla de listas de precios para la puerta de calidad visual: cada estado con la
 * paleta predeterminada y otra, en claro y en oscuro. La escala de la letra y el tamaño de la
 * ventana son los del sistema. Solo corre con el argumento `captureDir`. Sin regla de Compose: su
 * actividad taparía la de las capturas. Los datos son inventados.
 */
@RunWith(AndroidJUnit4::class)
class PriceListsGalleryTest {
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
                    for (shot in shots()) {
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
                                        LocalDiscreetMode provides shot.discreet,
                                    ) {
                                        key(shot.name) {
                                            PriceListsScreen(
                                                uiState = shot.state,
                                                onAction = {},
                                                listEditor = shot.listEditor,
                                                priceEditor = shot.priceEditor,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        instrumentation.waitForIdleSync()
                        SystemClock.sleep(SETTLE_MILLIS)
                        val mode = if (dark) "dark" else "light"
                        val file =
                            File(
                                requireNotNull(dir),
                                "${palette.name.lowercase()}-$mode-${shot.name}.png",
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

    private class Shot(
        val name: String,
        val state: PriceListsUiState,
        val listEditor: PriceListEditor? = null,
        val priceEditor: ListPriceEditor? = null,
        val discreet: Boolean = false,
    )

    private fun shots(): List<Shot> = listOf(
        Shot("lists", PriceListSamples.lists),
        Shot("empty", PriceListSamples.empty),
        Shot("loading", PriceListsUiState()),
        Shot("detail", PriceListSamples.detail),
        Shot("detail-discreet", PriceListSamples.detail, discreet = true),
        Shot(
            "list-sheet",
            PriceListSamples.lists,
            listEditor = PriceListEditor(
                editingId = PriceListSamples.SPECIAL_ID,
                name = PriceListSamples.special.name,
                kind = PriceListKind.RELATIVE,
                baseId = PriceListSamples.WHOLESALE_ID,
                adjustmentText = "-10",
                bases = listOf(PriceListSamples.default, PriceListSamples.wholesale),
                baseProblem = ListBaseProblem.CYCLE,
            ),
        ),
        Shot(
            "price-sheet",
            PriceListSamples.detail.copy(selectedId = PriceListSamples.WHOLESALE_ID),
            priceEditor = ListPriceEditor(
                listId = PriceListSamples.WHOLESALE_ID,
                productId = 7L,
                productName = PriceListSamples.rows.first().name,
                priceText = "4500",
                hasOwnPrice = true,
                pricing = PriceListSamples.pricing,
                typedPrice = Money(4_500L),
            ),
        ),
        Shot(
            "archive-dialog",
            PriceListSamples.detail.copy(
                selectedId = PriceListSamples.WHOLESALE_ID,
                dialog = PriceListsDialog.Archive(PriceListSamples.WHOLESALE_ID, "Mayoristas", 3),
            ),
        ),
    )

    private companion object {
        const val SETTLE_MILLIS = 900L
    }
}
