package io.github.yesidrobayo.shopledger.data.repository

import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.port.PlanningReportRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Las sumas del límite de ingresos y del punto de equilibrio sobre Room. Solo lee: cada consulta
 * vuelve a emitir con cada cambio confirmado en las tablas que suma.
 */
internal class RoomPlanningReportRepository(database: AppDatabase) : PlanningReportRepository {
    private val reports = database.planningReportDao()

    override fun observeFirstSaleDate(): Flow<BusinessDate?> = reports
        .observeFirstSaleDate()
        .map { text -> if (text.isEmpty()) null else storedDate(text) }
        .distinctUntilChanged()

    override fun observeFixedExpenses(period: ReportPeriod): Flow<Money> = reports
        .observeFixedExpenses(period.storedStart, period.storedEnd)
        .map(::Money)
        .distinctUntilChanged()
}
