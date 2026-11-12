package io.github.yesidrobayo.shopledger.ui.stats

import androidx.compose.runtime.Composable
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.ProfitTotals
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.report.DayPoint
import io.github.yesidrobayo.shopledger.domain.model.report.PeriodKind
import io.github.yesidrobayo.shopledger.domain.model.report.PeriodTotals
import io.github.yesidrobayo.shopledger.domain.model.tax.TaxEstimate
import io.github.yesidrobayo.shopledger.domain.model.valueOrNull
import io.github.yesidrobayo.shopledger.domain.rule.report.PeriodMetricsCalculator
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth

// Vistas previas de `Números`, con datos inventados: el mes del ejemplo de las reglas, con una
// semana de pérdida, el mes sin movimientos, el día sin gráfica, un monto de trece cifras y el
// estado sin datos.

private val PREVIEW_TODAY = BusinessDate(LocalDate(2026, 11, 14))
private val PREVIEW_MONTH = ReportPeriod.Month(YearMonth(2026, 11))

/** Ventas por día del mes de muestra, en unidad menor: días sin ventas valen cero. */
private val PREVIEW_DAILY_SALES =
    listOf(
        142_000L, 98_000L, 45_000L, 0L, 167_000L, 210_000L, 301_000L, 120_000L, 133_000L, 0L,
        176_000L, 154_000L, 199_000L, 210_000L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L,
        0L, 0L, 0L,
    )

private fun previewSeries(): List<DayPoint> = PREVIEW_DAILY_SALES.mapIndexed { index, sales ->
    // La ganancia de muestra es un tercio de lo vendido, salvo un día con más devoluciones.
    val profit = if (index == 3) -12_500L else sales / 3
    DayPoint(PREVIEW_MONTH.start.plusDays(index), Money(sales), Money(profit))
}

private fun previewTotals(
    net: Long,
    cost: Long,
    count: Long,
    expenses: Long,
    shrinkage: Long,
    fees: Long,
) = PeriodTotals(
    ProfitTotals(
        salesNetTotal = Money(net),
        returnsNetRefund = Money.ZERO,
        salesTotalCost = Money(cost),
        returnsCostReturned = Money.ZERO,
        recordedExpenses = Money(expenses),
        netShrinkage = Money(shrinkage),
        paymentFees = Money(fees),
        cashRoundingTotal = Money.ZERO,
    ),
    salesCount = count,
)

/** El mes de muestra: el ejemplo de las reglas, con comisiones y el impuesto estimado. */
internal fun previewStatisticsContent(
    metric: ChartMetric = ChartMetric.SALES,
    showList: Boolean = false,
): StatisticsUiState.Content = StatisticsUiState.Content(
    kind = PeriodKind.MONTH,
    period = PREVIEW_MONTH,
    today = PREVIEW_TODAY,
    canGoPrevious = true,
    canGoNext = false,
    metric = metric,
    metrics =
        PeriodMetricsCalculator.of(
            previewTotals(4_250_000, 2_720_000, 312, 640_000, 25_000, 60_000),
        ).valueOrNull(),
    series = previewSeries(),
    selectedDay = PREVIEW_TODAY,
    showList = showList,
    tax = TaxEstimate(collected = Money(807_500), paid = Money(516_800)),
)

/** Un mes sin movimientos: cifras en cero, sin margen ni venta promedio. */
internal fun previewEmptyPeriod(): StatisticsUiState.Content = previewStatisticsContent().copy(
    period = ReportPeriod.Month(YearMonth(2026, 9)),
    canGoNext = true,
    metrics = PeriodMetricsCalculator.of(PeriodTotals.ZERO).valueOrNull(),
    series = previewSeries().map { DayPoint(it.date, Money.ZERO, Money.ZERO) }.take(30),
    selectedDay = null,
    tax = null,
)

/** Un día con un monto de trece cifras y solo gastos: sin gráfica y con pérdida. */
internal fun previewLongDay(): StatisticsUiState.Content = previewStatisticsContent().copy(
    kind = PeriodKind.DAY,
    period = ReportPeriod.Day(PREVIEW_TODAY),
    metrics =
        PeriodMetricsCalculator.of(
            previewTotals(1_234_567_890_123, 999_999_999_999, 4_812, 2_000_000_000_000, -1_355, 0),
        ).valueOrNull(),
    series = null,
    selectedDay = null,
)

@ThemePreviews
@Composable
private fun StatisticsMonthPreview() {
    PreviewTheme { StatisticsScreen(previewStatisticsContent(), onAction = {}) }
}

@ThemePreviews
@Composable
private fun StatisticsProfitListPreview() {
    PreviewTheme {
        StatisticsScreen(
            previewStatisticsContent(ChartMetric.PROFIT, showList = true),
            onAction = {},
        )
    }
}

@ThemePreviews
@Composable
private fun StatisticsEmptyPeriodPreview() {
    PreviewTheme { StatisticsScreen(previewEmptyPeriod(), onAction = {}) }
}

@ThemePreviews
@Composable
private fun StatisticsLongDayPreview() {
    PreviewTheme { StatisticsScreen(previewLongDay(), onAction = {}) }
}

@ThemePreviews
@Composable
private fun StatisticsDiscreetPreview() {
    PreviewTheme(discreet = true) { StatisticsScreen(previewStatisticsContent(), onAction = {}) }
}

@ThemePreviews
@Composable
private fun StatisticsNoDataPreview() {
    PreviewTheme { StatisticsScreen(StatisticsUiState.NoData, onAction = {}) }
}
