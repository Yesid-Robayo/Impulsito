package io.github.yesidrobayo.shopledger.data.repository

import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.data.local.report.CashFlowRow
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.report.CashFlowSums
import io.github.yesidrobayo.shopledger.domain.model.report.CashFlowWindow
import io.github.yesidrobayo.shopledger.domain.port.CashFlowSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Las sumas del flujo de caja proyectado, sobre una sola consulta agregada. Solo lee. */
internal class RoomCashFlowSource(database: AppDatabase) : CashFlowSource {
    private val cashFlow = database.cashFlowDao()

    override fun observe(
        today: BusinessDate,
        shortUntil: BusinessDate,
        longUntil: BusinessDate,
    ): Flow<CashFlowSums> = cashFlow
        .observe(today.toStored(), shortUntil.toStored(), longUntil.toStored())
        .map(::toSums)
        .distinctUntilChanged()

    private fun toSums(row: CashFlowRow): CashFlowSums = CashFlowSums(
        accountCount = row.accountCount.toStoredInt(),
        balance = Money(row.balance),
        notYetAvailable = Money(row.notYetAvailable),
        short = CashFlowWindow(
            settlements = Money(row.shortSettlements),
            receivables = Money(row.shortReceivables),
            payables = Money(row.shortPayables),
        ),
        long = CashFlowWindow(
            settlements = Money(row.longSettlements),
            receivables = Money(row.longReceivables),
            payables = Money(row.longPayables),
        ),
    )
}
