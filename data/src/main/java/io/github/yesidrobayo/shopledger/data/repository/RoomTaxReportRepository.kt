package io.github.yesidrobayo.shopledger.data.repository

import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.tax.TaxTotals
import io.github.yesidrobayo.shopledger.domain.port.TaxReportRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Las sumas de impuesto de un período, sobre Room. Solo lee: una consulta con las tres sumas, que
 * vuelve a emitir con cada cambio confirmado en ventas, devoluciones o compras, y no repite un
 * resultado igual al anterior.
 */
internal class RoomTaxReportRepository(database: AppDatabase) : TaxReportRepository {
    private val sales = database.saleDao()

    override fun observeTotals(period: ReportPeriod): Flow<TaxTotals> = sales
        .observeTaxTotals(period.storedStart, period.storedEnd)
        .map { row ->
            TaxTotals(
                salesTax = Money(row.salesTax),
                returnsTaxRefund = Money(row.returnsTaxRefund),
                purchasesTax = Money(row.purchasesTax),
            )
        }
        .distinctUntilChanged()
}
