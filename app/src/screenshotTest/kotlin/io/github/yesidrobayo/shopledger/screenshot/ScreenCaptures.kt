package io.github.yesidrobayo.shopledger.screenshot

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.android.tools.screenshot.PreviewTest
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.Margin
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductUsage
import io.github.yesidrobayo.shopledger.domain.model.report.PeriodVariation
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleHistoryRow
import io.github.yesidrobayo.shopledger.domain.model.sale.SalesSummary
import io.github.yesidrobayo.shopledger.domain.model.settings.ColorPalette
import io.github.yesidrobayo.shopledger.domain.usecase.home.HomeProfit
import io.github.yesidrobayo.shopledger.domain.usecase.report.ComparedPeriod
import io.github.yesidrobayo.shopledger.ui.components.ProductRowTrailing
import io.github.yesidrobayo.shopledger.ui.components.TonalFab
import io.github.yesidrobayo.shopledger.ui.home.HomeScreen
import io.github.yesidrobayo.shopledger.ui.home.HomeUiState
import io.github.yesidrobayo.shopledger.ui.home.TodayHero
import io.github.yesidrobayo.shopledger.ui.product.ProductListContent
import io.github.yesidrobayo.shopledger.ui.product.ProductListItem
import io.github.yesidrobayo.shopledger.ui.product.ProductListScreen
import io.github.yesidrobayo.shopledger.ui.product.ProductListSection
import io.github.yesidrobayo.shopledger.ui.product.ProductListStock
import io.github.yesidrobayo.shopledger.ui.product.ProductListUiState
import io.github.yesidrobayo.shopledger.ui.sale.CartLine
import io.github.yesidrobayo.shopledger.ui.sale.NewSaleScreen
import io.github.yesidrobayo.shopledger.ui.sale.NewSaleUiState
import io.github.yesidrobayo.shopledger.ui.sale.SaleCartState
import io.github.yesidrobayo.shopledger.ui.sale.SaleContent
import io.github.yesidrobayo.shopledger.ui.sale.SaleMethod
import io.github.yesidrobayo.shopledger.ui.sale.SalePaymentState
import io.github.yesidrobayo.shopledger.ui.sale.SaleTile
import io.github.yesidrobayo.shopledger.ui.sale.TileLine
import io.github.yesidrobayo.shopledger.ui.sale.TileStock
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth

/** Los datos inventados, con un nombre corto para que las filas quepan en el ancho. */
private val S = CaptureSamples

// Las pantallas de todos los días: Inicio con las cifras de hoy, la venta con un carrito y la
// lista de productos. Cada una sale con la paleta inicial y sin decimales, con la paleta de
// contraste más justo y con tres decimales, en las nueve combinaciones de idioma, modo y letra.

@PreviewTest
@ScreenMatrix
@Composable
fun HomeDefault() {
    CaptureTheme { HomeCapture() }
}

@PreviewTest
@ScreenMatrix
@Composable
fun HomeVivid() {
    CaptureTheme(palette = ColorPalette.VIVID_RED_CREAM) { HomeCapture() }
}

@PreviewTest
@ScreenMatrix
@Composable
fun HomeThreeDecimals() {
    CaptureTheme(format = CaptureFormat.THREE_DECIMALS) { HomeCapture() }
}

@PreviewTest
@ScreenMatrix
@Composable
fun NewSaleDefault() {
    CaptureTheme { NewSaleScreen(newSaleState(), {}) }
}

@PreviewTest
@ScreenMatrix
@Composable
fun NewSaleVivid() {
    CaptureTheme(palette = ColorPalette.VIVID_RED_CREAM) { NewSaleScreen(newSaleState(), {}) }
}

@PreviewTest
@ScreenMatrix
@Composable
fun NewSaleThreeDecimals() {
    CaptureTheme(format = CaptureFormat.THREE_DECIMALS) { NewSaleScreen(newSaleState(), {}) }
}

@PreviewTest
@ScreenMatrix
@Composable
fun ProductListDefault() {
    CaptureTheme { ProductListCapture() }
}

@PreviewTest
@ScreenMatrix
@Composable
fun ProductListVivid() {
    CaptureTheme(palette = ColorPalette.VIVID_RED_CREAM) { ProductListCapture() }
}

@PreviewTest
@ScreenMatrix
@Composable
fun ProductListThreeDecimals() {
    CaptureTheme(format = CaptureFormat.THREE_DECIMALS) { ProductListCapture() }
}

// ---------------------------------------------------------------- Inicio

/** 2026-11-02 15:00 UTC: lunes 2026-11-02 10:00 en la zona de muestra. */
private const val NOW = 1_793_631_600_000L
private const val MINUTE = 60_000L

@Composable
private fun HomeCapture() {
    HomeScreen(homeContent(), onAction = {}, profit = homeProfit())
}

private fun saleRow(id: Long, minutesAgo: Long, name: String, total: Money, lines: Int = 1) =
    SaleHistoryRow(
        id = id,
        occurredAt = Instant.fromEpochMilliseconds(NOW - minutesAgo * MINUTE),
        businessDate = S.TODAY,
        total = total,
        voided = false,
        firstProductName = name,
        lineCount = lines,
        methodName = S.METHOD_WALLET,
        paymentCount = 1,
    )

/** Un día con ventas: la cifra de hoy, su ganancia y las ventas recientes, una de nombre largo. */
private fun homeContent(): HomeUiState.Content = HomeUiState.Content(
    today = S.TODAY,
    zone = BusinessTimeZone.of("Etc/GMT+5"),
    hero =
        TodayHero.Sales(
            SalesSummary(
                netSales = Money(1_284_500L),
                salesCount = 12,
                grossProfit = Money(412_100L),
                grossMargin = Margin.Defined(BasisPoints(3_208L)),
            ),
        ),
    firstUse = false,
    recentSales =
        listOf(
            saleRow(5L, 8, S.SECOND_NAME, S.SECOND_PRICE),
            saleRow(4L, 19, S.SHORT_NAME, S.PRICE, lines = 3),
            saleRow(3L, 64, S.LONG_NAME, S.LONG_AMOUNT),
        ),
)

/** La ganancia de la semana en positivo y la del mes en pérdida, con un monto largo. */
private fun homeProfit(): HomeProfit = HomeProfit(
    week = ReportPeriod.Week(S.TODAY),
    weekProfit = Money(1_902_300L),
    month = ReportPeriod.Month(YearMonth(2026, 11)),
    monthProfit = Money(-1_234_567_890L),
    weekChange =
        ComparedPeriod(
            ReportPeriod.Range(
                BusinessDate(LocalDate(2026, 10, 26)),
                BusinessDate(LocalDate(2026, 10, 27)),
            ),
            PeriodVariation(
                netSales = null,
                grossProfit = null,
                netProfit = BasisPoints(1_250),
                grossMargin = null,
            ),
        ),
)

// ---------------------------------------------------------------- Venta

/** La venta con cuatro productos, uno ya en el carrito y otro vendido de más, y tres métodos. */
private fun newSaleState(): NewSaleUiState {
    val tiles =
        listOf(
            SaleTile(1L, S.SHORT_NAME, S.PRICE, TileStock.Normal(S.STOCK), S.UNIT, line = null),
            SaleTile(
                2L,
                S.SECOND_NAME,
                S.SECOND_PRICE,
                TileStock.Low(S.LOW_STOCK),
                S.UNIT,
                TileLine(S.TWO, removesLine = false, canIncrement = true, oversell = null),
            ),
            SaleTile(
                3L,
                S.LONG_NAME,
                S.LONG_AMOUNT,
                TileStock.Negative(S.NEGATIVE_STOCK),
                S.UNIT,
                line = null,
            ),
            SaleTile(
                4L,
                S.THIRD_NAME,
                null,
                TileStock.Out,
                S.UNIT,
                TileLine(
                    S.ONE,
                    removesLine = true,
                    canIncrement = true,
                    oversell = S.NEGATIVE_STOCK,
                ),
            ),
        )
    val lines =
        listOf(
            CartLine(
                productId = 2L,
                name = S.SECOND_NAME,
                unit = S.UNIT,
                quantity = S.TWO,
                unitPrice = S.SECOND_PRICE,
                discount = Money.ZERO,
                total = Money(25_000L),
                issue = null,
                removesLine = false,
                canIncrement = true,
            ),
        )
    return NewSaleUiState(
        content = SaleContent.Products(tiles, searching = false),
        cart = SaleCartState(lines = lines, itemCount = 2L, total = Money(25_000L)),
        payment =
            SalePaymentState(
                methods =
                    listOf(
                        SaleMethod(1L, "CASH", null, hasQr = false),
                        SaleMethod(2L, "CARD", null, hasQr = false),
                        SaleMethod(3L, null, S.METHOD_WALLET, hasQr = true),
                    ),
                selectedId = 1L,
            ),
    )
}

// ---------------------------------------------------------------- Productos

@Composable
private fun ProductListCapture() {
    ProductListScreen(
        uiState =
            ProductListUiState(
                content =
                    ProductListContent.Products(listOf(ProductListSection(null, productItems()))),
            ),
        onAction = {},
        floatingAction = { TonalFab(stringResource(R.string.product_new_title), {}) },
    )
}

/** Stock bajo, normal, negativo y agotado, un margen negativo, un insumo y uno sin costo. */
private fun productItems(): List<ProductListItem> {
    fun item(
        id: Long,
        name: String,
        stock: ProductListStock,
        trailing: ProductRowTrailing.Catalog,
    ) = ProductListItem(id, name, stock, S.UNIT, trailing, archived = false)
    return listOf(
        item(
            1L,
            S.SECOND_NAME,
            ProductListStock.Low(S.LOW_STOCK),
            ProductRowTrailing.Catalog(S.SECOND_PRICE, Margin.Defined(BasisPoints(2_400L)), true),
        ),
        item(
            2L,
            S.SHORT_NAME,
            ProductListStock.Normal(S.STOCK),
            ProductRowTrailing.Catalog(S.PRICE, Margin.Defined(BasisPoints(-450L)), true),
        ),
        item(
            3L,
            S.LONG_NAME,
            ProductListStock.Negative(S.NEGATIVE_STOCK),
            ProductRowTrailing.Catalog(S.LONG_AMOUNT, Margin.Defined(BasisPoints(3_120L)), true),
        ),
        item(
            4L,
            S.THIRD_NAME,
            ProductListStock.Out,
            ProductRowTrailing.Catalog(null, Margin.NoData, hasCost = false),
        ),
        ProductListItem(
            5L,
            S.SECOND_NAME,
            ProductListStock.Normal(S.STOCK),
            S.UNIT,
            ProductRowTrailing.Catalog(null, Margin.NoData, hasCost = true),
            archived = false,
            usage = ProductUsage.INPUT,
            cost = S.PRICE,
        ),
    )
}
