package io.github.yesidrobayo.shopledger.domain.usecase.money

import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.experience.BusinessTool
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccount
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyDocument
import io.github.yesidrobayo.shopledger.domain.port.ModuleUsageSource
import io.github.yesidrobayo.shopledger.domain.port.MoneyAccountRepository
import io.github.yesidrobayo.shopledger.domain.port.MoneyLedgerRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Las cuentas archivadas, en el orden en que se crearon: dejaron de ofrecerse, pero su libro sigue
 * entero y se pueden reactivar.
 */
class ObserveArchivedMoneyAccountsUseCase(private val accounts: MoneyAccountRepository) {
    operator fun invoke(): Flow<List<MoneyAccount>> = accounts.observe(ArchiveFilter.ARCHIVED)
}

/**
 * Lo que todavía no llega a cada cuenta a la fecha de negocio `today`, como un cobro con tarjeta
 * que se acredita después: `Por acreditar`. Ya está en el saldo, porque el libro lo cuenta al
 * cobrar; esto solo dice cuánto de ese saldo falta que llegue.
 */
class ObservePendingSettlementUseCase(private val ledger: MoneyLedgerRepository) {
    operator fun invoke(today: BusinessDate): Flow<Map<Long, Money>> =
        ledger.observePendingSettlement(today)
}

/**
 * Si la persona ya lleva cuentas: más de una vigente, o algún movimiento. Mientras no, la pantalla
 * explica para qué sirven y los formularios no preguntan por cuentas.
 */
class ObserveMoneyAccountsInUseUseCase(private val usage: ModuleUsageSource) {
    operator fun invoke(): Flow<Boolean> =
        usage.observe().map { BusinessTool.MONEY_ACCOUNTS in it }.distinctUntilChanged()
}

/** El documento al que lleva un movimiento que no se registró a mano, para verlo o anularlo allí. */
class FindMoneyDocumentUseCase(private val ledger: MoneyLedgerRepository) {
    suspend operator fun invoke(movementId: Long): MoneyDocument? = ledger.findDocument(movementId)
}
