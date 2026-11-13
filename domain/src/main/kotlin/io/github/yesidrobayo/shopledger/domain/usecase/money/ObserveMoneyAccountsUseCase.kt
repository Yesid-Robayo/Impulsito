package io.github.yesidrobayo.shopledger.domain.usecase.money

import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.model.money.AccountCurrency
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyMovement
import io.github.yesidrobayo.shopledger.domain.port.AppSettingsRepository
import io.github.yesidrobayo.shopledger.domain.port.MoneyAccountRepository
import io.github.yesidrobayo.shopledger.domain.port.MoneyLedgerRepository
import io.github.yesidrobayo.shopledger.domain.rule.money.MoneyOverview
import io.github.yesidrobayo.shopledger.domain.rule.money.MoneyTotals
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Las cuentas vigentes con su dinero, separado por moneda: lo de la moneda del negocio suma, y cada
 * cuenta en otra moneda queda aparte con su saldo. Vuelve a emitir al cambiar una cuenta, un saldo o
 * la moneda del negocio.
 */
class ObserveMoneyAccountsUseCase(
    private val settings: AppSettingsRepository,
    private val accounts: MoneyAccountRepository,
) {
    operator fun invoke(): Flow<MoneyOverview> =
        combine(settings.observe(), accounts.observe(ArchiveFilter.ACTIVE)) { current, active ->
            MoneyTotals.overview(active, AccountCurrency.of(current.regional.currency))
        }.distinctUntilChanged()
}

/** Los movimientos de una cuenta, en el orden del libro: su extracto. */
class ObserveMoneyMovementsUseCase(private val ledger: MoneyLedgerRepository) {
    operator fun invoke(accountId: Long): Flow<List<MoneyMovement>> =
        ledger.observeByAccount(accountId)
}
