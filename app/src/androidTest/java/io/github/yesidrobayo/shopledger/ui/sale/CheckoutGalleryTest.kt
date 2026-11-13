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
import io.github.yesidrobayo.shopledger.domain.model.customer.Customer
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerContact
import io.github.yesidrobayo.shopledger.domain.model.region.NumberSymbols
import io.github.yesidrobayo.shopledger.domain.model.sale.LineDiscount
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleCatalog
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.model.settings.ColorPalette
import io.github.yesidrobayo.shopledger.domain.model.settings.LoyaltySettings
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleResult
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.format.LocalDiscreetMode
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.previewFormats
import java.io.File
import kotlin.time.Instant
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
        val customers: List<Customer> = emptyList(),
        val scrolled: Boolean = false,
        val discreet: Boolean = false,
        val qrOnly: Boolean = false,
        val actions: List<CheckoutAction> = emptyList(),
        val recent: List<Customer> = emptyList(),
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
                        if (scene.scrolled) {
                            // La sección de abajo: se desliza como lo haría la persona.
                            instrumentation.uiAutomation.executeShellCommand(SWIPE_UP).close()
                            SystemClock.sleep(SETTLE_MILLIS)
                        }
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
                customersSource = { flowOf(scene.customers) },
                recentCustomersSource = { flowOf(scene.recent) },
            )
        }
        return model
    }

    private fun scenes(): List<Scene> {
        val credit = checkoutCatalog().copy(creditMethods = listOf(saleMethod(9L, "ON_ACCOUNT")))
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
                "points",
                catalog = checkoutCatalog().copy(loyalty = PROGRAM),
                customers = listOf(MEMBER),
                scrolled = true,
                actions = listOf(
                    CheckoutAction.PickCustomer(MEMBER.id),
                    CheckoutAction.PointsChange("100"),
                    CheckoutAction.RedeemPoints,
                ),
            ),
            Scene(
                "pointstotal",
                catalog = checkoutCatalog().copy(loyalty = PROGRAM),
                customers = listOf(MEMBER),
                actions = listOf(
                    CheckoutAction.PickCustomer(MEMBER.id),
                    CheckoutAction.PointsChange("100"),
                    CheckoutAction.RedeemPoints,
                ),
            ),
            Scene(
                "pointserror",
                catalog = checkoutCatalog().copy(loyalty = PROGRAM),
                customers = listOf(MEMBER),
                scrolled = true,
                actions = listOf(
                    CheckoutAction.PickCustomer(MEMBER.id),
                    CheckoutAction.PointsChange("151"),
                    CheckoutAction.RedeemPoints,
                ),
            ),
            // Un cliente con 20.000 a favor en una venta de 38.410: lo disponible y, ya usado, el
            // pago a crédito que lo consume junto al efectivo que queda.
            Scene(
                "credit",
                catalog = credit,
                customers = listOf(PREPAID),
                scrolled = true,
                actions = listOf(CheckoutAction.PickCustomer(PREPAID.id)),
            ),
            Scene(
                "creditused",
                catalog = credit,
                customers = listOf(PREPAID),
                scrolled = true,
                actions = listOf(
                    CheckoutAction.PickCustomer(PREPAID.id),
                    CheckoutAction.UseCreditBalance,
                ),
            ),
            Scene(
                "creditdiscreet",
                catalog = credit,
                customers = listOf(PREPAID),
                scrolled = true,
                discreet = true,
                actions = listOf(CheckoutAction.PickCustomer(PREPAID.id)),
            ),
            // La lista de clientes sin búsqueda: `Recientes`, la lista completa y `Ver clientes`.
            Scene(
                "recent",
                catalog = credit,
                customers = listOf(MEMBER, PREPAID),
                recent = listOf(PREPAID),
                actions = listOf(CheckoutAction.OpenCustomers),
            ),
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
        const val SWIPE_UP = "input swipe 540 1600 540 300 400"
        val PROGRAM = LoyaltySettings(true, amountPerPoint = Money(1_000L), pointValue = Money(5L))
        val MEMBER = Customer(
            id = 41L,
            contact = CustomerContact("Cliente con puntos", "cliente con puntos", null, null, null),
            priceListId = null,
            balance = Money.ZERO,
            pointsBalance = 150L,
            anonymizedAt = null,
            archivedAt = null,
            createdAt = Instant.fromEpochMilliseconds(1_700_000_000_000L),
            updatedAt = Instant.fromEpochMilliseconds(1_700_000_000_000L),
        )
        val PREPAID = Customer(
            id = 42L,
            contact = CustomerContact(
                "Cliente con anticipo",
                "cliente con anticipo",
                null,
                null,
                null,
            ),
            priceListId = null,
            balance = Money(-20_000L),
            pointsBalance = 0L,
            anonymizedAt = null,
            archivedAt = null,
            createdAt = Instant.fromEpochMilliseconds(1_700_000_000_000L),
            updatedAt = Instant.fromEpochMilliseconds(1_700_000_000_000L),
        )
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
