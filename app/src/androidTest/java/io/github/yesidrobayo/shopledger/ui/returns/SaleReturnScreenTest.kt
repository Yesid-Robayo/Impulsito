package io.github.yesidrobayo.shopledger.ui.returns

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.SemanticsNodeInteractionCollection
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.catalog.UnitKind
import io.github.yesidrobayo.shopledger.domain.model.region.NumberSymbols
import io.github.yesidrobayo.shopledger.domain.model.returns.ReturnResolution
import io.github.yesidrobayo.shopledger.domain.model.settings.ColorPalette
import io.github.yesidrobayo.shopledger.navigation.SaleExchange
import io.github.yesidrobayo.shopledger.navigation.SaleExchangeLine
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.format.LocalDiscreetMode
import io.github.yesidrobayo.shopledger.ui.sale.NewSaleAction
import io.github.yesidrobayo.shopledger.ui.sale.NewSaleScreen
import io.github.yesidrobayo.shopledger.ui.sale.NewSaleViewModel
import io.github.yesidrobayo.shopledger.ui.sale.SaleDetailScreen
import io.github.yesidrobayo.shopledger.ui.sale.SaleDetailUiState
import io.github.yesidrobayo.shopledger.ui.sale.SaleNumberFormat
import io.github.yesidrobayo.shopledger.ui.sale.SaleReturnRow
import io.github.yesidrobayo.shopledger.ui.sale.saleCatalog
import io.github.yesidrobayo.shopledger.ui.sale.saleDetailView
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.previewFormats
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * La devolución de una venta en pantalla: el modo discreto no oculta nada, porque no hay costo y el
 * total lo ve quien compra; cada estado se dibuja y sus acciones llegan; y, con `captureDir`, las
 * capturas de la puerta de calidad en claro y en oscuro, también del detalle con devoluciones y de
 * la venta nueva de un cambio. Datos y cifras inventados.
 */
@RunWith(AndroidJUnit4::class)
class SaleReturnScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val unit = MeasureUnit(1L, "UNIT", null, null, UnitKind.COUNT, false, null)
    private val kilogram = MeasureUnit(2L, "KILOGRAM", null, null, UnitKind.MASS, true, null)

    private val withLines = SaleReturnUiState(
        loading = false,
        saleId = 1_204L,
        saleDate = BusinessDate(LocalDate(2026, 10, 28)),
        lines = listOf(
            SaleReturnLineUi(
                lineId = 1L,
                name = "Jabón de avena",
                unit = unit,
                sold = QuantityMilli(3_000L),
                returnedBefore = QuantityMilli(1_000L),
                remaining = QuantityMilli(2_000L),
                quantity = QuantityMilli(1_000L),
                restock = true,
                archived = false,
            ),
            SaleReturnLineUi(
                lineId = 2L,
                name = LONG_NAME,
                unit = unit,
                sold = QuantityMilli(2_000L),
                returnedBefore = QuantityMilli.ZERO,
                remaining = QuantityMilli(2_000L),
                quantity = QuantityMilli(1_000L),
                restock = false,
                archived = true,
            ),
            SaleReturnLineUi(
                lineId = 3L,
                name = "Queso fresco",
                unit = kilogram,
                sold = QuantityMilli(1_250L),
                returnedBefore = QuantityMilli.ZERO,
                remaining = QuantityMilli(1_250L),
                quantity = QuantityMilli.ZERO,
                restock = true,
                archived = false,
            ),
        ),
        countsOn = BusinessDate(LocalDate(2026, 11, 13)),
        withPoints = true,
        itemCount = 2,
        total = Money(TOTAL),
        net = Money(TOTAL),
        showsTax = true,
    )

    private val withCredit = withLines.copy(
        resolution = ReturnResolution.STORE_CREDIT,
        customer = ReturnCustomerUi(7L, "Ana Ríos", Money(15_000L), Money(15_000L - TOTAL)),
        customerLocked = true,
        creditSale = true,
    )

    @Test
    fun discreetModeKeepsTheTotalInView() {
        for (discreet in listOf(false, true)) {
            show(withLines, discreet)
            assertTrue(figures(FIGURE).fetchSemanticsNodes().isNotEmpty())
        }
    }

    @Test
    fun eachStateDrawsAndItsActionsArrive() {
        val actions = mutableListOf<SaleReturnAction>()
        val context = instrumentation.targetContext

        show(SaleReturnUiState(loading = false, nothingLeft = true, saleId = 1L)) { actions += it }
        compose.onNodeWithText(context.getString(R.string.sale_return_empty_title)).assertExists()
        compose.onNodeWithText(context.getString(R.string.action_back)).performClick()

        show(withLines) { actions += it }
        compose.onNodeWithText(context.getString(R.string.sale_return_no_restock_help))
            .assertExists()
        compose.onNodeWithText(context.getString(R.string.sale_return_points_notice))
            .assertExists()
        compose.onNodeWithText(context.getString(R.string.sale_return_resolution_exchange))
            .performScrollTo()
            .performClick()
        compose.onNodeWithText(context.getString(R.string.sale_return_register_action))
            .performClick()

        show(withLines.copy(resolution = ReturnResolution.EXCHANGE)) { actions += it }
        compose.onNodeWithText(context.getString(R.string.sale_return_continue_exchange_action))
            .assertExists()

        show(
            withLines.copy(
                resolution = ReturnResolution.STORE_CREDIT,
                needsCustomer = true,
                noneSelected = true,
                notice = SaleReturnNotice.RegisterFailed(outOfSpace = false),
            ),
        ) { actions += it }
        compose.onNodeWithText(context.getString(R.string.sale_return_error_none_selected))
            .assertExists()
        compose.onNodeWithText(context.getString(R.string.action_retry)).performClick()

        assertEquals(
            listOf(
                SaleReturnAction.Close,
                SaleReturnAction.ResolutionChange(ReturnResolution.EXCHANGE),
                SaleReturnAction.Register,
                SaleReturnAction.Retry,
            ),
            actions,
        )
    }

    @Test
    fun drawsTheReturnInBothModes() {
        val name = InstrumentationRegistry.getArguments().getString("captureDir")
        assumeTrue("Sin captureDir no hay capturas", name != null)
        val dir = File(instrumentation.targetContext.cacheDir, requireNotNull(name)).apply {
            mkdirs()
        }
        val scenes = listOf(
            "lines" to withLines,
            "credit" to withCredit,
            "exchange" to withLines.copy(resolution = ReturnResolution.EXCHANGE),
            "empty" to SaleReturnUiState(loading = false, nothingLeft = true, saleId = 1_204L),
            "failed" to withLines.copy(
                resolution = ReturnResolution.STORE_CREDIT,
                needsCustomer = true,
                noneSelected = true,
                notice = SaleReturnNotice.RegisterFailed(outOfSpace = false),
            ),
        )
        for (dark in listOf(false, true)) {
            val mode = if (dark) "dark" else "light"
            for ((scene, state) in scenes) {
                show(state, dark = dark)
                SystemClock.sleep(SETTLE_MILLIS)
                save(File(dir, "$mode-sale-return-$scene.png"))
            }
            show(withLines, discreet = true, dark = dark)
            SystemClock.sleep(SETTLE_MILLIS)
            save(File(dir, "$mode-sale-return-lines-discreet.png"))

            render(dark) { SaleDetailScreen(detailWithReturns(), {}) }
            SystemClock.sleep(SETTLE_MILLIS)
            save(File(dir, "$mode-sale-detail-returns.png"))

            val sale = exchangeSale()
            render(dark) {
                val state by sale.uiState.collectAsStateWithLifecycle()
                NewSaleScreen(state, sale::onAction)
            }
            compose.runOnUiThread { sale.onAction(NewSaleAction.AddProduct(1L)) }
            compose.waitForIdle()
            SystemClock.sleep(SETTLE_MILLIS)
            save(File(dir, "$mode-new-sale-exchange.png"))
        }
    }

    /** El detalle de la venta de las capturas, con una devolución activa y otra anulada. */
    private fun detailWithReturns(): SaleDetailUiState {
        val view = saleDetailView()
        return SaleDetailUiState.Ready(
            view.copy(
                hasActiveReturns = true,
                canReturn = true,
                lines = view.lines.mapIndexed { index, line ->
                    if (index == 0) {
                        line.copy(returned = QuantityMilli(1_000L), left = QuantityMilli(1_000L))
                    } else {
                        line
                    }
                },
                returns = listOf(
                    SaleReturnRow(
                        41L,
                        DETAIL_RETURN_DATE,
                        ReturnResolution.REFUND,
                        Money(3_500L),
                        false,
                    ),
                    SaleReturnRow(
                        40L,
                        DETAIL_RETURN_DATE,
                        ReturnResolution.STORE_CREDIT,
                        Money(6_000L),
                        true,
                    ),
                ),
            ),
        )
    }

    /** La venta nueva de un cambio con crédito de 12500, sobre el catálogo de muestra. */
    private fun exchangeSale(): NewSaleViewModel {
        val symbols = NumberSymbols(decimalSeparator = ',', minusSign = '-', grouping = null)
        return NewSaleViewModel(
            catalogSource = { MutableStateFlow(saleCatalog()) },
            registerSale = { error("Un cambio no registra una venta sola") },
            reactivate = { true },
            savedState = SavedStateHandle(),
            numbers = SaleNumberFormat(symbols, 0, symbols, symbols),
            computation = Dispatchers.Default,
            exchange = SaleExchange(
                saleId = 1_204L,
                lines = listOf(SaleExchangeLine(1L, 1_000L, restock = true)),
                credit = 12_500L,
                creditMethodId = 1L,
            ),
        )
    }

    /** Los nodos que dicen [figure], en el texto o en lo que lee TalkBack. */
    private fun figures(figure: String): SemanticsNodeInteractionCollection = compose.onAllNodes(
        hasText(figure, substring = true) or hasContentDescription(figure, substring = true),
    )

    private fun show(
        state: SaleReturnUiState,
        discreet: Boolean = false,
        dark: Boolean = false,
        onAction: (SaleReturnAction) -> Unit = {},
    ) = render(dark, discreet) { SaleReturnScreen(state, onAction) }

    private fun render(dark: Boolean, discreet: Boolean = false, content: @Composable () -> Unit) {
        compose.runOnUiThread {
            compose.activity.setContent {
                AppTheme(palette = ColorPalette.INDIGO_MUSTARD, darkTheme = dark) {
                    CompositionLocalProvider(
                        LocalAppFormats provides previewFormats(),
                        LocalDiscreetMode provides discreet,
                    ) { content() }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun save(file: File) {
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot()) { "Sin captura" }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private companion object {
        const val TOTAL = 53_197L
        const val LONG_NAME =
            "Café molido de altura en bolsa resellable con válvula, tostión media, 500 g"

        /** Las primeras cifras del total: ninguna sale en otro dato de la pantalla. */
        const val FIGURE = "53"
        const val SETTLE_MILLIS = 900L
        val DETAIL_RETURN_DATE = BusinessDate(LocalDate(2026, 11, 3))
    }
}
