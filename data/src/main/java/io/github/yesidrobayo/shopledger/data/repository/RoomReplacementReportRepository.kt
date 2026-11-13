package io.github.yesidrobayo.shopledger.data.repository

import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.report.ReplacementTotals
import io.github.yesidrobayo.shopledger.domain.model.report.RestockedReturnLine
import io.github.yesidrobayo.shopledger.domain.port.ReplacementReportRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Lo que la ganancia si se repone lee de un período, sobre Room. Solo lee: cada consulta vuelve a
 * emitir con cada cambio confirmado en las tablas que lee, y no repite un resultado igual.
 */
internal class RoomReplacementReportRepository(database: AppDatabase) :
    ReplacementReportRepository {
    private val reports = database.replacementReportDao()

    override fun observeTotals(period: ReportPeriod): Flow<ReplacementTotals> = combine(
        reports.observeSales(period.storedStart, period.storedEnd),
        reports.observeRestockedReturns(period.storedStart, period.storedEnd),
    ) { sales, returns ->
        ReplacementTotals(
            salesReplacementCost = Money(sales.salesReplacementCost),
            linesWithoutReplacement = sales.linesWithoutReplacement,
            restockedReturns = returns.map { row ->
                RestockedReturnLine(
                    lineReplacementCost = Money(row.lineReplacementCost),
                    lineQuantity = QuantityMilli(row.lineQuantityMilli),
                    returnedBefore = QuantityMilli(row.returnedBeforeMilli),
                    returned = QuantityMilli(row.returnedMilli),
                )
            },
        )
    }.distinctUntilChanged()
}
