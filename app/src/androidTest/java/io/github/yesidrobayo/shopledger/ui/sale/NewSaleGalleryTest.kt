package io.github.yesidrobayo.shopledger.ui.sale

import android.graphics.Bitmap
import android.graphics.Color.TRANSPARENT
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.region.NumberSymbols
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleCatalog
import io.github.yesidrobayo.shopledger.domain.model.settings.ColorPalette
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleResult
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.format.LocalDiscreetMode
import io.github.yesidrobayo.shopledger.ui.product.sampleProduct
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.previewFormats
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Capturas de la venta nueva para la puerta de calidad visual: cada estado, con sus hojas y sus
 * diálogos, con la paleta predeterminada y otra, en claro y en oscuro. La escala de la letra y el
 * tamaño de la ventana son los del sistema: se corre una vez por cada combinación que se revisa.
 *
 * Cada estado se arma con las acciones de la persona sobre el ViewModel real, con un catálogo
 * inventado. Solo corre con el argumento `captureDir`, la carpeta dentro de la caché de la app.
 */
@RunWith(AndroidJUnit4::class)
class NewSaleGalleryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val captureDir: File? =
        InstrumentationRegistry.getArguments().getString("captureDir")?.let { name ->
            File(instrumentation.targetContext.cacheDir, name).apply { mkdirs() }
        }

    /** Un estado de la galería: el catálogo, lo que devuelve confirmar y lo que hace la persona. */
    private class Scene(
        val name: String,
        val catalog: () -> Flow<SaleCatalog> = { flowOfCatalog() },
        val result: RegisterSaleResult = RegisterSaleResult.Registered(1_204L),
        val actions: List<NewSaleAction> = emptyList(),
    )

    @Test
    fun drawsEveryStateInBothPalettesAndModes() {
        val dir = captureDir
        assumeTrue("Sin captureDir no hay capturas", dir != null)
        val only = InstrumentationRegistry.getArguments().getString("scenes")?.split(',')?.toSet()
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            for (palette in listOf(ColorPalette.INDIGO_MUSTARD, ColorPalette.GARNET_HONEY)) {
                for (dark in listOf(false, true)) {
                    for (scene in scenes().filter { only == null || it.name in only }) {
                        val model = modelFor(scene)
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
                                        LocalDiscreetMode provides false,
                                    ) {
                                        key(scene.name) {
                                            val state by model.uiState.collectAsState()
                                            NewSaleScreen(state, model::onAction)
                                        }
                                    }
                                }
                            }
                        }
                        instrumentation.waitForIdleSync()
                        SystemClock.sleep(SETTLE_MILLIS)
                        for (action in scene.actions) {
                            instrumentation.runOnMainSync { model.onAction(action) }
                            instrumentation.waitForIdleSync()
                            SystemClock.sleep(STEP_MILLIS)
                        }
                        SystemClock.sleep(SETTLE_MILLIS)
                        val mode = if (dark) "dark" else "light"
                        val file = "${palette.name.lowercase()}-$mode-${scene.name}.png"
                        save(File(requireNotNull(dir), file))
                    }
                }
            }
        }
    }

    private fun modelFor(scene: Scene): NewSaleViewModel {
        lateinit var model: NewSaleViewModel
        instrumentation.runOnMainSync {
            model = NewSaleViewModel(
                catalogSource = scene.catalog,
                registerSale = { scene.result },
                reactivate = { true },
                savedState = SavedStateHandle(),
                numbers = SaleNumberFormat(SYMBOLS, 0, SYMBOLS, SYMBOLS),
                computation = Dispatchers.Default,
            )
        }
        return model
    }

    private fun scenes(): List<Scene> {
        val selling = listOf(
            NewSaleAction.AddProduct(1L),
            NewSaleAction.Increment(1L),
            NewSaleAction.Increment(1L),
            NewSaleAction.Increment(1L),
            NewSaleAction.AddProduct(2L),
            NewSaleAction.AddProduct(4L),
            NewSaleAction.QuantityShortcut(QuantityMilli(500L)),
        )
        val archived = listOf(sampleProduct(20L, "Aceite de coco", 9_000L, 0L, 0L))
        return listOf(
            Scene("frequent"),
            Scene("selling", actions = selling),
            Scene("cart", actions = selling + NewSaleAction.OpenCart),
            Scene(
                "line",
                actions = selling + listOf(
                    NewSaleAction.OpenLine(2L),
                    NewSaleAction.LineDiscountModeChange(DiscountMode.PERCENT),
                    NewSaleAction.LineDiscountChange("10"),
                ),
            ),
            Scene("quantity", actions = listOf(NewSaleAction.AddProduct(4L))),
            Scene("noprice", actions = listOf(NewSaleAction.AddProduct(8L))),
            Scene(
                "search",
                catalog = { flowOfCatalog(archived) },
                actions = listOf(NewSaleAction.QueryChange("ace")),
            ),
            Scene(
                "noresults",
                catalog = { flowOfCatalog(archived) },
                actions = listOf(NewSaleAction.QueryChange("aceite de c")),
            ),
            Scene("empty", catalog = { flowOfCatalog(products = emptyList()) }),
            Scene("error", catalog = { flow { error("Falla inventada") } }),
            Scene(
                "failed",
                result = RegisterSaleResult.StorageFailed(StorageFailure.DISK_FULL),
                actions = selling + NewSaleAction.Register,
            ),
            Scene(
                "zero",
                actions = listOf(
                    NewSaleAction.AddProduct(8L),
                    NewSaleAction.CloseSheet,
                    NewSaleAction.Register,
                ),
            ),
            Scene("leave", actions = selling + NewSaleAction.Close),
            Scene(
                "currency",
                result = RegisterSaleResult.CurrencyNotConfirmed,
                actions = listOf(NewSaleAction.AddProduct(2L), NewSaleAction.Register),
            ),
        )
    }

    private fun save(file: File) {
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot()) { "Sin captura" }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private companion object {
        const val SETTLE_MILLIS = 900L
        const val STEP_MILLIS = 250L
        val SYMBOLS = NumberSymbols(decimalSeparator = ',', minusSign = '-', grouping = null)

        fun flowOfCatalog(
            archived: List<Product> = emptyList(),
            products: List<Product> = saleProducts(),
        ): Flow<SaleCatalog> = flowOf(saleCatalog(products, archived))
    }
}
