package io.github.yesidrobayo.shopledger.domain.usecase.report

import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.report.ManagementSummary
import io.github.yesidrobayo.shopledger.domain.model.valueOrNull
import io.github.yesidrobayo.shopledger.domain.port.ManagementSummaryRepository
import io.github.yesidrobayo.shopledger.domain.port.PeriodBreakdownRepository
import io.github.yesidrobayo.shopledger.domain.port.PeriodReportRepository
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendar
import io.github.yesidrobayo.shopledger.domain.rule.report.ManagementSummaryRules
import io.github.yesidrobayo.shopledger.domain.usecase.tax.ObserveTaxEstimateUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.tax.TaxEstimateState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Las cifras del `Resumen de gestión` de un período, de nuevo con cada venta, devolución, gasto,
 * compra, ajuste o anulación confirmados.
 *
 * Lee las mismas sumas que `Números`: las del período, el impuesto estimado, lo cobrado por método
 * y los avisos. Por eso el resumen cuadra con esa pantalla en el mismo período. Emite `null` solo
 * si una cifra no cabe en `Long`.
 */
class ObserveManagementSummaryUseCase(
    private val reports: PeriodReportRepository,
    private val tax: ObserveTaxEstimateUseCase,
    private val breakdowns: PeriodBreakdownRepository,
    private val summaries: ManagementSummaryRepository,
) {
    operator fun invoke(
        period: ReportPeriod,
        calendar: BusinessCalendar,
    ): Flow<ManagementSummary?> = combine(
        reports.observeTotals(period),
        tax(period),
        breakdowns.observePaymentMethods(period),
        breakdowns.observeNoticeCounts(period, calendar.endInstantExclusive(period)),
        summaries.observeAvailability(period),
    ) { totals, taxState, methods, notices, availability ->
        val estimate = when (taxState) {
            TaxEstimateState.Hidden -> null
            is TaxEstimateState.Estimated -> taxState.estimate
        }
        ManagementSummaryRules.summaryOf(
            period = period,
            totals = totals,
            tax = estimate,
            methods = methods,
            notices = notices,
            availability = availability,
        ).valueOrNull()
    }.distinctUntilChanged()
}
