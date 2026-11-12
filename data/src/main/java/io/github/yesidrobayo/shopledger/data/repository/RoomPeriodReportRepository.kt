package io.github.yesidrobayo.shopledger.data.repository

import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.data.local.report.DailyTotalsRow
import io.github.yesidrobayo.shopledger.data.local.report.PeriodTotalsRow
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.ProfitTotals
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.report.DayTotals
import io.github.yesidrobayo.shopledger.domain.model.report.PeriodTotals
import io.github.yesidrobayo.shopledger.domain.port.PeriodReportRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Las sumas de ganancia de un período, sobre Room. Solo lee: cada consulta vuelve a emitir con
 * cada cambio confirmado en las tablas que suma, y no repite un resultado igual al anterior.
 */
internal class RoomPeriodReportRepository(database: AppDatabase) : PeriodReportRepository {
    private val reports = database.periodReportDao()

    override fun observeTotals(period: ReportPeriod): Flow<PeriodTotals> = reports
        .observeTotals(period.storedStart, period.storedEnd)
        .map { row -> row.toTotals() }
        .distinctUntilChanged()

    override fun observeDailyTotals(period: ReportPeriod): Flow<List<DayTotals>> = reports
        .observeDailyTotals(period.storedStart, period.storedEnd)
        .map { rows -> rows.map { row -> row.toDay() } }
        .distinctUntilChanged()

    override fun observeLastActiveDate(): Flow<BusinessDate?> = reports
        .observeLastActiveDate()
        .map { text -> if (text.isEmpty()) null else storedDate(text) }
        .distinctUntilChanged()
}

private fun PeriodTotalsRow.toTotals(): PeriodTotals = PeriodTotals(
    profit =
        ProfitTotals(
            salesNetTotal = Money(salesNetTotal),
            returnsNetRefund = Money(returnsNetRefund),
            salesTotalCost = Money(salesTotalCost),
            returnsCostReturned = Money(returnsCostReturned),
            recordedExpenses = Money(recordedExpenses),
            netShrinkage = Money(netShrinkage),
            paymentFees = Money(paymentFees),
            cashRoundingTotal = Money(cashRoundingTotal),
        ),
    salesCount = salesCount,
)

/** Un día leído como las sumas de un período de un solo día, con su fecha guardada. */
private fun DailyTotalsRow.toDay(): DayTotals = DayTotals(
    date = storedDate(businessDate),
    totals =
        PeriodTotalsRow(
            salesNetTotal = salesNetTotal,
            salesTotalCost = salesTotalCost,
            salesCount = salesCount,
            cashRoundingTotal = cashRoundingTotal,
            returnsNetRefund = returnsNetRefund,
            returnsCostReturned = returnsCostReturned,
            recordedExpenses = recordedExpenses,
            netShrinkage = netShrinkage,
            paymentFees = paymentFees,
        ).toTotals(),
)
