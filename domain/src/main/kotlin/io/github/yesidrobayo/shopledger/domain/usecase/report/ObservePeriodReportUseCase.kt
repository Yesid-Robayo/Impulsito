package io.github.yesidrobayo.shopledger.domain.usecase.report

import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.report.PeriodMetrics
import io.github.yesidrobayo.shopledger.domain.model.valueOrNull
import io.github.yesidrobayo.shopledger.domain.port.PeriodReportRepository
import io.github.yesidrobayo.shopledger.domain.rule.report.PeriodMetricsCalculator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Las métricas de un período para las estadísticas: vuelven a emitir con cada venta, devolución,
 * gasto, ajuste o anulación confirmados, sin recargar la pantalla.
 *
 * Es la misma cuenta para un día, una semana, un mes o un rango: cambia solo el rango de fechas.
 * Inicio usa la misma regla, así que la ganancia final del mes coincide en las dos pantallas.
 */
class ObservePeriodReportUseCase(private val reports: PeriodReportRepository) {
    operator fun invoke(period: ReportPeriod): Flow<PeriodReport> = reports
        .observeTotals(period)
        .map { totals -> PeriodReport(period, PeriodMetricsCalculator.of(totals).valueOrNull()) }
        .distinctUntilChanged()
}

/**
 * Las métricas de [period]. [metrics] es `null` solo si una cifra no cabe en `Long`, y entonces la
 * pantalla muestra «sin valor» en vez de caerse.
 */
data class PeriodReport(val period: ReportPeriod, val metrics: PeriodMetrics?)
