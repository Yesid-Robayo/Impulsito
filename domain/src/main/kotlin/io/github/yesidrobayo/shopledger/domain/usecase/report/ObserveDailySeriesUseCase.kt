package io.github.yesidrobayo.shopledger.domain.usecase.report

import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.report.DayPoint
import io.github.yesidrobayo.shopledger.domain.model.valueOrNull
import io.github.yesidrobayo.shopledger.domain.port.PeriodReportRepository
import io.github.yesidrobayo.shopledger.domain.rule.report.DailySeries
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * La serie por día de un período para la gráfica de las estadísticas, con un punto por día y cero
 * en los días sin ventas. Vuelve a emitir con cada cambio confirmado.
 *
 * Emite `null` si una cifra no cabe en `Long`: la pantalla no dibuja la gráfica, en vez de caerse.
 */
class ObserveDailySeriesUseCase(private val reports: PeriodReportRepository) {
    operator fun invoke(period: ReportPeriod): Flow<List<DayPoint>?> = reports
        .observeDailyTotals(period)
        .map { days -> DailySeries.of(period, days).valueOrNull() }
        .distinctUntilChanged()
}
