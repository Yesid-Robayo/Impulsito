package io.github.yesidrobayo.shopledger.domain.usecase.report

import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.report.BreakEven
import io.github.yesidrobayo.shopledger.domain.port.PlanningReportRepository
import io.github.yesidrobayo.shopledger.domain.rule.report.BreakEvenRules
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * El punto de equilibrio de un período, para `Herramientas` de las estadísticas: los gastos de las
 * categorías fijas del período frente a su margen real, de nuevo con cada venta, gasto, anulación
 * o categoría marcada. `null` si el período no tiene ventas ni gastos fijos: la herramienta no
 * aparece.
 *
 * El margen sale de la misma cuenta que la cascada de `Cómo se calculó`. No escribe nada.
 */
class ObserveBreakEvenUseCase(
    private val planning: PlanningReportRepository,
    private val reports: ObservePeriodReportUseCase,
) {
    operator fun invoke(period: ReportPeriod): Flow<BreakEven?> = combine(
        planning.observeFixedExpenses(period),
        reports(period),
    ) { fixed, report -> BreakEvenRules.of(fixed, report.metrics?.profit) }
        .distinctUntilChanged()
}
