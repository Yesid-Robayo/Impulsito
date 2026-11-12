package io.github.yesidrobayo.shopledger.domain.testing

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.report.DayTotals
import io.github.yesidrobayo.shopledger.domain.model.report.PeriodTotals
import io.github.yesidrobayo.shopledger.domain.port.PeriodReportRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * Las sumas por período en memoria, como las daría la base: cada período lee su entrada de
 * [totals], o cero si no tiene, y cada cambio de un valor vuelve a emitir. Anota los períodos
 * pedidos, en orden.
 */
internal class FakePeriodReportRepository : PeriodReportRepository {
    val totals = MutableStateFlow(emptyMap<ReportPeriod, PeriodTotals>())
    val days = MutableStateFlow(emptyMap<ReportPeriod, List<DayTotals>>())
    val lastActiveDate = MutableStateFlow<BusinessDate?>(null)
    val asked = mutableListOf<ReportPeriod>()

    override fun observeTotals(period: ReportPeriod): Flow<PeriodTotals> {
        asked += period
        return totals.map { it[period] ?: PeriodTotals.ZERO }
    }

    override fun observeDailyTotals(period: ReportPeriod): Flow<List<DayTotals>> {
        asked += period
        return days.map { it[period].orEmpty() }
    }

    override fun observeLastActiveDate(): Flow<BusinessDate?> = lastActiveDate
}
