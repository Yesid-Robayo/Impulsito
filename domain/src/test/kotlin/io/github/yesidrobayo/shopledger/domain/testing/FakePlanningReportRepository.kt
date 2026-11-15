package io.github.yesidrobayo.shopledger.domain.testing

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.port.PlanningReportRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** Las sumas de planeación en memoria: cada cambio de un valor vuelve a emitir. */
internal class FakePlanningReportRepository : PlanningReportRepository {
    val firstSaleDate = MutableStateFlow<BusinessDate?>(null)

    override fun observeFirstSaleDate(): Flow<BusinessDate?> = firstSaleDate
}
