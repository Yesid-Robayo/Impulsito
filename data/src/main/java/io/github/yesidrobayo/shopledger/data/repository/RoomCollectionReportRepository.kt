package io.github.yesidrobayo.shopledger.data.repository

import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.report.DayCollection
import io.github.yesidrobayo.shopledger.domain.port.CollectionReportRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Lo vendido y lo cobrado por día, sobre Room. Solo lee, y no repite un resultado igual al
 * anterior.
 */
internal class RoomCollectionReportRepository(database: AppDatabase) : CollectionReportRepository {
    private val reports = database.collectionReportDao()

    override fun observeDaily(period: ReportPeriod): Flow<List<DayCollection>> = reports
        .observeDaily(period.storedStart, period.storedEnd)
        .map { rows ->
            rows.map { row ->
                DayCollection(
                    date = storedDate(row.businessDate),
                    sold = Money(row.sold),
                    collectedAtSale = Money(row.collectedAtSale),
                    collectedOnAccount = Money(row.collectedOnAccount),
                )
            }
        }
        .distinctUntilChanged()

    override fun observeOnCredit(): Flow<Boolean> = reports.observeOnCredit().distinctUntilChanged()
}
