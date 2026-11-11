package io.github.yesidrobayo.shopledger.ui.sale

import android.graphics.Bitmap
import android.graphics.Color.TRANSPARENT
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.region.NumberSymbols
import io.github.yesidrobayo.shopledger.domain.model.sale.HeldSale
import io.github.yesidrobayo.shopledger.domain.model.settings.ColorPalette
import io.github.yesidrobayo.shopledger.domain.usecase.sale.HoldSaleResult
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.format.LocalDiscreetMode
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.previewFormats
import java.io.File
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Capturas de las ventas en espera para la puerta de calidad visual: la lista con nombre y sin él,
 * vacía, el diálogo al cambiar de venta, y en la venta nueva `En espera: 2` con la hoja de poner en
 * espera. La letra y la ventana son las del sistema. Solo corre con `captureDir`.
 */
@RunWith(AndroidJUnit4::class)
class HeldSalesGalleryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val captureDir: File? =
        InstrumentationRegistry.getArguments().getString("captureDir")?.let { name ->
            File(instrumentation.targetContext.cacheDir, name).apply { mkdirs() }
        }

    private val zone = requireNotNull(BusinessTimeZone.of("Etc/GMT+5"))

    /** 2026-11-02 15:42 UTC: 10:42 en la zona del negocio. */
    private val since = Instant.fromEpochMilliseconds(1_793_634_120_000L)

    private val rows = HeldSalesContent.Sales(
        listOf(
            HeldSaleRow(1L, "Mesa 4", 3L, Money(1_234_500L), since),
            HeldSaleRow(2L, null, 1L, Money(4_200L), since),
            HeldSaleRow(
                3L,
                "Pedido de mostrador para recoger a las cinco",
                12L,
                Money(98_765_400L),
                since,
            ),
        ),
        zone,
    )

    @Test
    fun drawsTheHeldSalesInBothModes() {
        val dir = captureDir
        assumeTrue("Sin captureDir no hay capturas", dir != null)
        val scenes = listOf(
            "list" to HeldSalesUiState(content = rows),
            "empty" to HeldSalesUiState(content = HeldSalesContent.Empty),
            "swap" to HeldSalesUiState(content = rows, dialog = HeldSalesDialog.Swap(1L)),
        )
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            for (dark in listOf(false, true)) {
                for ((name, state) in scenes) {
                    show(scenario, dark, name) { HeldSalesScreen(state, onAction = {}) }
                    save(File(requireNotNull(dir), "${mode(dark)}-held-$name.png"))
                }
                val model = saleModel()
                show(scenario, dark, "sale") {
                    val state by model.uiState.collectAsState()
                    NewSaleScreen(state, model::onAction)
                }
                instrumentation.runOnMainSync {
                    model.onAction(NewSaleAction.AddProduct(2L))
                    model.onAction(NewSaleAction.OpenHold)
                    model.onAction(NewSaleAction.HoldLabelChange("Mesa 4"))
                }
                instrumentation.waitForIdleSync()
                SystemClock.sleep(SETTLE_MILLIS)
                save(File(requireNotNull(dir), "${mode(dark)}-sale-hold.png"))
            }
        }
    }

    private fun mode(dark: Boolean) = if (dark) "dark" else "light"

    private fun saleModel(): NewSaleViewModel {
        lateinit var model: NewSaleViewModel
        val held = (1L..2L).map { HeldSale(it, null, emptyList(), since, since) }
        instrumentation.runOnMainSync {
            model = NewSaleViewModel(
                catalogSource = { flowOf(saleCatalog()) },
                registerSale = { error("No se confirma en las capturas") },
                reactivate = { true },
                savedState = SavedStateHandle(),
                numbers = SaleNumberFormat(SYMBOLS, 0, SYMBOLS, SYMBOLS),
                computation = Dispatchers.Default,
                heldSales = { MutableStateFlow(held) },
                holdSale = { HoldSaleResult.Held(9L) },
            )
        }
        return model
    }

    private fun show(
        scenario: ActivityScenario<ComponentActivity>,
        dark: Boolean,
        name: String,
        content: @Composable () -> Unit,
    ) {
        scenario.onActivity { activity ->
            val bars = SystemBarStyle.auto(TRANSPARENT, TRANSPARENT) { dark }
            activity.enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
            activity.setContent {
                AppTheme(palette = ColorPalette.INDIGO_MUSTARD, darkTheme = dark) {
                    CompositionLocalProvider(
                        LocalAppFormats provides previewFormats(),
                        LocalDiscreetMode provides false,
                    ) {
                        key(name, dark) { content() }
                    }
                }
            }
        }
        instrumentation.waitForIdleSync()
        SystemClock.sleep(SETTLE_MILLIS)
    }

    private fun save(file: File) {
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot()) { "Sin captura" }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private companion object {
        const val SETTLE_MILLIS = 900L
        val SYMBOLS = NumberSymbols(decimalSeparator = ',', minusSign = '-', grouping = null)
    }
}
