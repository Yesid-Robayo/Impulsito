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
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.region.NumberSymbols
import io.github.yesidrobayo.shopledger.domain.model.sale.LineDiscount
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleCatalog
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.model.settings.ColorPalette
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleResult
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.format.LocalDiscreetMode
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.previewFormats
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Capturas del cobro para la puerta de calidad visual: cada estado, con sus hojas, con la paleta
 * predeterminada y otra, en claro y en oscuro. La escala de la letra y el tamaño de la ventana son
 * los del sistema: se corre una vez por cada combinación que se revisa.
 *
 * Cada estado se arma con las acciones de la persona sobre el ViewModel real, con un catálogo
 * inventado. Solo corre con el argumento `captureDir`, la carpeta dentro de la caché de la app.
 */
@RunWith(AndroidJUnit4::class)
class CheckoutGalleryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val captureDir: File? =
        InstrumentationRegistry.getArguments().getString("captureDir")?.let { name ->
            File(instrumentation.targetContext.cacheDir, name).apply { mkdirs() }
        }

    private class Scene(
        val name: String,
        val methodId: Long = 1L,
        val catalog: SaleCatalog = checkoutCatalog(),
        val result: RegisterSaleResult = RegisterSaleResult.Registered(1_204L),
        val discreet: Boolean = false,
        val qrOnly: Boolean = false,
        val actions: List<CheckoutAction> = emptyList(),
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
                                        LocalDiscreetMode provides scene.discreet,
                                    ) {
                                        key(scene.name) {
                                            val state by model.uiState.collectAsState()
                                            CheckoutScreen(state, model::onAction, onBack = {})
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
                        val file = "${palette.name.lowercase()}-$mode-checkout-${scene.name}.png"
                        save(File(requireNotNull(dir), file))
                    }
                }
            }
        }
    }

    private fun modelFor(scene: Scene): CheckoutViewModel {
        lateinit var model: CheckoutViewModel
        val draft = SaleDraft(lines = CHECKOUT_LINES, methodId = scene.methodId)
        instrumentation.runOnMainSync {
            model = CheckoutViewModel(
                catalogSource = { flowOf(scene.catalog) },
                registerSale = { scene.result },
                findImage = { null },
                savedState = SavedStateHandle(),
                numbers = SaleNumberFormat(SYMBOLS, 0, SYMBOLS, SYMBOLS),
                offered = { CheckoutHandoff.Offered(draft, scene.qrOnly) },
                computation = Dispatchers.Default,
            )
        }
        return model
    }

    private fun scenes(): List<Scene> {
        val split = listOf(
            CheckoutAction.AmountChange(0, "15000"),
            CheckoutAction.AddPayment(2L),
        )
        return listOf(
            Scene("open"),
            Scene("split", actions = split),
            Scene("discreet", discreet = true, actions = split),
            Scene(
                "missing",
                actions = listOf(CheckoutAction.AmountChange(0, "20000"), CheckoutAction.Register),
            ),
            Scene(
                "adjustment",
                actions = listOf(
                    CheckoutAction.OpenAdjustment,
                    CheckoutAction.AdjustmentTextChange("1000"),
                ),
            ),
            Scene(
                "adjusted",
                actions = listOf(
                    CheckoutAction.OpenAdjustment,
                    CheckoutAction.AdjustmentTextChange("1000"),
                    CheckoutAction.ApplyAdjustment,
                ) + split,
            ),
            Scene("addpayment", actions = listOf(CheckoutAction.OpenAddPayment)),
            // Efectivo con paso de 50: 38410 se cobra 38400, y 50000 recibidos dan 11600 de cambio.
            Scene(
                "cash",
                catalog = checkoutCatalog().copy(cashRounding = Money(50L)),
                actions = listOf(CheckoutAction.TenderedChange(0, "50000")),
            ),
            Scene(
                "short",
                catalog = checkoutCatalog().copy(cashRounding = Money(50L)),
                actions = listOf(CheckoutAction.TenderedChange(0, "30000")),
            ),
            Scene("qr", methodId = 3L, qrOnly = true),
            Scene(
                "failed",
                result = RegisterSaleResult.StorageFailed(StorageFailure.DISK_FULL),
                actions = listOf(CheckoutAction.Register),
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
    }
}

/** Dos aceites y un café con descuento: una venta que vale la pena cobrar con varios métodos. */
internal val CHECKOUT_LINES = listOf(
    SaleLineRequest(1L, QuantityMilli(2_000L)),
    SaleLineRequest(
        6L,
        QuantityMilli(1_000L),
        discount = LineDiscount.Percent(BasisPoints(1_000L)),
    ),
)

/**
 * El catálogo de la venta con una tarjeta que cobra comisión y recargo, y tarda en acreditar. Sin
 * [withCard], la tarjeta ya no se ofrece, como si se hubiera archivado.
 */
internal fun checkoutCatalog(withCard: Boolean = true): SaleCatalog {
    val base = saleCatalog()
    val methods = base.methods.map { method ->
        if (method.id != 2L) return@map method
        method.copy(
            details = method.details.copy(
                feeRate = BasisPoints(299L),
                priceAdjustment = BasisPoints(350L),
                settlementDays = 2,
            ),
        )
    }
    return base.copy(methods = if (withCard) methods else methods.filter { it.id != 2L })
}
