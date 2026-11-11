package io.github.yesidrobayo.shopledger.data.repository

import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.sale.SalesTotals
import io.github.yesidrobayo.shopledger.domain.port.SalesReportRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Las sumas de ventas y devoluciones de un período, sobre Room. Solo lee: una consulta con las
 * cinco sumas, que vuelve a emitir con cada cambio confirmado en ventas o devoluciones, y no repite
 * un resultado igual al anterior.
 */
internal class RoomSalesReportRepository(database: AppDatabase) : SalesReportRepository {
    private val reports = database.salesReportDao()

    override fun observeTotals(period: ReportPeriod): Flow<SalesTotals> = reports
        .observeTotals(period.storedStart, period.storedEnd)
        .map { row ->
            SalesTotals(
                salesNetTotal = Money(row.salesNetTotal),
                returnsNetRefund = Money(row.returnsNetRefund),
                salesTotalCost = Money(row.salesTotalCost),
                returnsCostReturned = Money(row.returnsCostReturned),
                salesCount = row.salesCount,
            )
        }
        .distinctUntilChanged()
}
