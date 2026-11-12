package io.github.yesidrobayo.shopledger.domain.usecase.report

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.report.PaymentMethodTotal
import io.github.yesidrobayo.shopledger.domain.model.report.PeriodNoticeCounts
import io.github.yesidrobayo.shopledger.domain.model.report.PeriodVariation
import io.github.yesidrobayo.shopledger.domain.model.valueOrNull
import io.github.yesidrobayo.shopledger.domain.port.PeriodBreakdownRepository
import io.github.yesidrobayo.shopledger.domain.port.PeriodReportRepository
import io.github.yesidrobayo.shopledger.domain.rule.MarginCalculator
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendar
import io.github.yesidrobayo.shopledger.domain.rule.report.ExpenseRow
import io.github.yesidrobayo.shopledger.domain.rule.report.PeriodComparison
import io.github.yesidrobayo.shopledger.domain.rule.report.PeriodMetricsCalculator
import io.github.yesidrobayo.shopledger.domain.rule.report.PeriodRankings
import io.github.yesidrobayo.shopledger.domain.rule.report.TopProducts
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf

/**
 * La comparación de un período con el comparable: el mismo día de la semana anterior, el período
 * anterior o, si está en curso, los mismos días del anterior. Un rango propio no se compara, y
 * emite `null`. Vuelve a emitir con cada cambio confirmado en cualquiera de los dos.
 */
class ObservePeriodComparisonUseCase(private val reports: PeriodReportRepository) {
    operator fun invoke(period: ReportPeriod, today: BusinessDate): Flow<ComparedPeriod?> {
        val comparable = PeriodComparison.comparableOf(period, today) ?: return flowOf(null)
        return combine(reports.observeTotals(period), reports.observeTotals(comparable)) {
                now,
                before,
            ->
            val current = PeriodMetricsCalculator.of(now).valueOrNull()
            val previous = PeriodMetricsCalculator.of(before).valueOrNull()
            if (current == null || previous == null) {
                null
            } else {
                ComparedPeriod(comparable, PeriodComparison.of(current, previous))
            }
        }.distinctUntilChanged()
    }
}

/** Las variaciones de un período frente a [comparedTo]. */
data class ComparedPeriod(val comparedTo: ReportPeriod, val variation: PeriodVariation)

/**
 * Los destacados, los desgloses y los avisos de un período. Vuelve a emitir con cada cambio
 * confirmado en ventas, pagos, devoluciones, gastos, productos o el libro de stock.
 */
class ObservePeriodBreakdownUseCase(
    private val reports: PeriodReportRepository,
    private val breakdowns: PeriodBreakdownRepository,
) {
    operator fun invoke(period: ReportPeriod, calendar: BusinessCalendar): Flow<PeriodBreakdown> =
        combine(
            reports.observeTotals(period),
            breakdowns.observeProductSales(period),
            breakdowns.observePaymentMethods(period),
            breakdowns.observeExpenseCategories(period),
            breakdowns.observeNoticeCounts(period, calendar.endInstantExclusive(period)),
        ) { totals, products, methods, categories, notices ->
            val profit = MarginCalculator.profit(totals.profit).valueOrNull()
            PeriodBreakdown(
                top = PeriodRankings.top(products).valueOrNull(),
                methods = methods,
                expenses = profit?.let { PeriodRankings.expenses(categories, it) },
                notices = notices,
            )
        }.distinctUntilChanged()
}

/**
 * Lo que explica las cifras de un período.
 *
 * @param top los destacados, o `null` si una cifra no cabe en `Long`.
 * @param expenses las filas de gastos, que suman los gastos totales, o `null` si una cifra no cabe.
 */
data class PeriodBreakdown(
    val top: TopProducts?,
    val methods: List<PaymentMethodTotal>,
    val expenses: List<ExpenseRow>?,
    val notices: PeriodNoticeCounts,
)
