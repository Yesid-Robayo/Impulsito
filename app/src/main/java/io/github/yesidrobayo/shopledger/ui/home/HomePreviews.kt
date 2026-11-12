package io.github.yesidrobayo.shopledger.ui.home

import androidx.compose.runtime.Composable
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.Margin
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.report.PeriodVariation
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleHistoryRow
import io.github.yesidrobayo.shopledger.domain.model.sale.SalesSummary
import io.github.yesidrobayo.shopledger.domain.usecase.home.HomeProfit
import io.github.yesidrobayo.shopledger.domain.usecase.report.ComparedPeriod
import io.github.yesidrobayo.shopledger.ui.components.PreviewSamples
import io.github.yesidrobayo.shopledger.ui.stock.previewInventoryItems
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth

// Vistas previas de Inicio, con datos inventados: un día con ventas y un nombre de 80 caracteres,
// una ganancia negativa con un monto de trece cifras, el día sin ventas y el primer uso.

private val PREVIEW_TODAY = BusinessDate(LocalDate(2026, 11, 2))
private val PREVIEW_ZONE = BusinessTimeZone.of("Etc/GMT+5")

/** 2026-11-02 15:00 UTC: lunes 2026-11-02 10:00 en la zona de muestra. */
private const val PREVIEW_NOW = 1_793_631_600_000L
private const val MINUTE = 60_000L

private fun previewRow(id: Long, minutesAgo: Long, name: String, total: Money, lines: Int = 1) =
    SaleHistoryRow(
        id = id,
        occurredAt = Instant.fromEpochMilliseconds(PREVIEW_NOW - minutesAgo * MINUTE),
        businessDate = PREVIEW_TODAY,
        total = total,
        voided = false,
        firstProductName = name,
        lineCount = lines,
        methodName = PreviewSamples.METHOD_CASH,
        paymentCount = 1,
    )

internal fun previewHomeContent(): HomeUiState.Content {
    val s = PreviewSamples
    return HomeUiState.Content(
        today = PREVIEW_TODAY,
        zone = PREVIEW_ZONE,
        hero = TodayHero.Sales(
            SalesSummary(
                netSales = Money(1_284_500L),
                salesCount = 12,
                grossProfit = Money(412_100L),
                grossMargin = Margin.Defined(BasisPoints(3_208L)),
            ),
        ),
        firstUse = false,
        recentSales = listOf(
            previewRow(5L, 8, s.SECOND_NAME, s.SECOND_PRICE),
            previewRow(4L, 19, s.SHORT_NAME, s.PRICE, lines = 3),
            previewRow(3L, 64, s.LONG_NAME, s.LONG_AMOUNT),
        ),
    )
}

/** La ganancia de la semana en positivo y la del mes en pérdida, con un monto largo. */
internal fun previewHomeProfit(): HomeProfit = HomeProfit(
    week = ReportPeriod.Week(BusinessDate(LocalDate(2026, 11, 2))),
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

/** La tarjeta por agotarse de muestra: el stock negativo, el agotado y el bajo del inventario. */
internal fun previewHomeLowStock(): HomeLowStock {
    val items = previewInventoryItems()
    return HomeLowStock(count = items.size, negativeCount = 1, items = items.take(3))
}

@ThemePreviews
@Composable
private fun HomeLowStockPreview() {
    PreviewTheme {
        HomeScreen(previewHomeContent(), onAction = {}, lowStock = previewHomeLowStock())
    }
}

@ThemePreviews
@Composable
private fun HomeSalesPreview() {
    PreviewTheme { HomeScreen(previewHomeContent(), onAction = {}, profit = previewHomeProfit()) }
}

@ThemePreviews
@Composable
private fun HomeLossPreview() {
    val s = PreviewSamples
    PreviewTheme {
        HomeScreen(
            previewHomeContent().copy(
                hero = TodayHero.Sales(
                    SalesSummary(
                        s.LONG_AMOUNT,
                        1,
                        s.NEGATIVE_AMOUNT,
                        Margin.Defined(BasisPoints(-1L)),
                    ),
                ),
            ),
            onAction = {},
        )
    }
}

@ThemePreviews
@Composable
private fun HomeNoSalesYetPreview() {
    PreviewTheme {
        HomeScreen(previewHomeContent().copy(hero = TodayHero.NoSalesYet), onAction = {})
    }
}

@ThemePreviews
@Composable
private fun HomeFirstUsePreview() {
    PreviewTheme {
        HomeScreen(
            previewHomeContent().copy(
                hero = TodayHero.NoSalesYet,
                firstUse = true,
                recentSales = emptyList(),
            ),
            onAction = {},
        )
    }
}

@ThemePreviews
@Composable
private fun HomeDiscreetPreview() {
    PreviewTheme(discreet = true) { HomeScreen(previewHomeContent(), onAction = {}) }
}

@ThemePreviews
@Composable
private fun HomeErrorPreview() {
    PreviewTheme { HomeScreen(HomeUiState.ReadError, onAction = {}) }
}
