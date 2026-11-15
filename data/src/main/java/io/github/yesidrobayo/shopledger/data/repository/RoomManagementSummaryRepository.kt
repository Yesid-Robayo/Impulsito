package io.github.yesidrobayo.shopledger.data.repository

import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.report.SummaryAvailability
import io.github.yesidrobayo.shopledger.domain.port.ManagementSummaryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Lo que hay en la base para el resumen de gestión, sobre Room. Solo lee, y no repite un resultado
 * igual al anterior.
 */
internal class RoomManagementSummaryRepository(database: AppDatabase) :
    ManagementSummaryRepository {
    private val summaries = database.managementSummaryDao()

    override fun observeAvailability(period: ReportPeriod): Flow<SummaryAvailability> = summaries
        .observeAvailability(period.storedStart, period.storedEnd)
        .map { row ->
            SummaryAvailability(
                hasPurchases = row.hasPurchases != 0L,
                hasInventory = row.hasInventory != 0L,
                hasParties = row.hasParties != 0L,
            )
        }
        .distinctUntilChanged()
}
