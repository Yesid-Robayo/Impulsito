package io.github.yesidrobayo.shopledger.ui.stats

import androidx.compose.runtime.Composable
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.ProfitTotals
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodKind
import io.github.yesidrobayo.shopledger.domain.model.report.DayPoint
import io.github.yesidrobayo.shopledger.domain.model.report.ExpenseCategoryTotal
import io.github.yesidrobayo.shopledger.domain.model.report.PaymentMethodTotal
import io.github.yesidrobayo.shopledger.domain.model.report.PeriodKind
import io.github.yesidrobayo.shopledger.domain.model.report.PeriodNoticeCounts
import io.github.yesidrobayo.shopledger.domain.model.report.PeriodTotals
import io.github.yesidrobayo.shopledger.domain.model.report.PeriodVariation
import io.github.yesidrobayo.shopledger.domain.model.report.ProductSales
import io.github.yesidrobayo.shopledger.domain.model.tax.TaxEstimate
import io.github.yesidrobayo.shopledger.domain.model.valueOrNull
import io.github.yesidrobayo.shopledger.domain.rule.report.ExpenseRow
import io.github.yesidrobayo.shopledger.domain.rule.report.PeriodMetricsCalculator
import io.github.yesidrobayo.shopledger.domain.rule.report.PeriodRankings
import io.github.yesidrobayo.shopledger.domain.usecase.report.ComparedPeriod
import io.github.yesidrobayo.shopledger.domain.usecase.report.PeriodBreakdown
import io.github.yesidrobayo.shopledger.ui.components.PreviewSamples
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews
import kotlinx.datetime.DayOfWeek
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
    comparison =
        ComparedPeriod(
            comparedTo =
                ReportPeriod.Range(
                    BusinessDate(LocalDate(2026, 10, 1)),
                    BusinessDate(LocalDate(2026, 10, 14)),
                ),
            variation = PeriodVariation(
                BasisPoints(800),
                BasisPoints(-150),
                null,
                BasisPoints(-40),
            ),
        ),
    breakdown = previewBreakdown(),
    units = emptyMap(),
    weekStartDay = DayOfWeek.MONDAY,
)

/** Los destacados, los métodos, los gastos y los avisos del mes de muestra. */
private fun previewBreakdown(): PeriodBreakdown {
    val s = PreviewSamples
    val products =
        listOf(
            ProductSales(
                1L,
                s.SHORT_NAME,
                1L,
                QuantityMilli(412_000),
                Money(1_204_000),
                Money(802_000),
            ),
            ProductSales(
                2L,
                s.SECOND_NAME,
                1L,
                QuantityMilli(160_000),
                Money(812_000),
                Money(590_000),
            ),
            ProductSales(
                3L,
                s.LONG_NAME,
                1L,
                QuantityMilli(31_000),
                Money(398_000),
                Money(120_000),
            ),
        )
    return PeriodBreakdown(
        top = PeriodRankings.top(products).valueOrNull(),
        methods =
            listOf(
                PaymentMethodTotal(
                    s.METHOD_CASH,
                    PaymentMethodKind.CASH,
                    240,
                    Money(3_210_000),
                    Money.ZERO,
                ),
                PaymentMethodTotal(
                    s.METHOD_CARD,
                    PaymentMethodKind.CARD,
                    72,
                    Money(1_855_500),
                    Money(60_000),
                ),
            ),
        expenses =
            listOf(
                ExpenseRow.Category(ExpenseCategoryTotal(1L, null, "RENT", Money(600_000))),
                ExpenseRow.Category(ExpenseCategoryTotal(2L, null, "UTILITIES", Money(40_000))),
                ExpenseRow.Shrinkage(Money(25_000)),
                ExpenseRow.Fees(Money(60_000)),
            ),
        notices = PeriodNoticeCounts(3, 0, 1, 0, 2),
    )
}

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

/** Con otros ingresos largos, que van aparte al final y no entran en la ganancia. */
@ThemePreviews
@Composable
private fun StatisticsOtherIncomePreview() {
    PreviewTheme {
        StatisticsScreen(
            previewStatisticsContent().copy(otherIncome = Money(98_765_432_100)),
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

/** Un rango propio de tres meses: sin flechas, sin comparación y sin gráfica. */
internal fun previewRange(): StatisticsUiState.Content = previewStatisticsContent().copy(
    kind = null,
    period = ReportPeriod.Range(
        BusinessDate(LocalDate(2026, 8, 1)),
        BusinessDate(LocalDate(2026, 10, 31)),
    ),
    canGoPrevious = false,
    canGoNext = false,
    series = null,
    selectedDay = null,
    comparison = null,
)

@ThemePreviews
@Composable
private fun StatisticsRangePreview() {
    PreviewTheme { StatisticsScreen(previewRange(), onAction = {}) }
}
