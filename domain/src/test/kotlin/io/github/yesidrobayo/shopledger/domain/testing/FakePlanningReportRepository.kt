package io.github.yesidrobayo.shopledger.domain.testing

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.port.PlanningReportRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * Las sumas de planeación en memoria: cada período lee sus gastos fijos de [fixedExpenses], o cero
 * si no tiene, y cada cambio de un valor vuelve a emitir.
 */
internal class FakePlanningReportRepository : PlanningReportRepository {
    val firstSaleDate = MutableStateFlow<BusinessDate?>(null)
    val fixedExpenses = MutableStateFlow(emptyMap<ReportPeriod, Money>())

    override fun observeFirstSaleDate(): Flow<BusinessDate?> = firstSaleDate

    override fun observeFixedExpenses(period: ReportPeriod): Flow<Money> =
        fixedExpenses.map { it[period] ?: Money.ZERO }
}
