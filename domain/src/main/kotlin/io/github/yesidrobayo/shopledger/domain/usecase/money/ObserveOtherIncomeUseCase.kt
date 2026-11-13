package io.github.yesidrobayo.shopledger.domain.usecase.money

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.money.AccountCurrency
import io.github.yesidrobayo.shopledger.domain.port.AppSettingsRepository
import io.github.yesidrobayo.shopledger.domain.port.MoneyLedgerRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

/**
 * Los otros ingresos de un período: dinero que no vino de una venta, como un reintegro. Se muestran
 * aparte y nunca entran en la ganancia, porque la ganancia es lo que se vendió menos lo que costó.
 *
 * Suma solo los de cuentas en la moneda del negocio, con sus mismos decimales: uno de otra moneda no
 * se mezcla. Uno anulado no cuenta. Vuelve a emitir si cambia el libro o la moneda del negocio.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ObserveOtherIncomeUseCase(
    private val settings: AppSettingsRepository,
    private val ledger: MoneyLedgerRepository,
) {
    operator fun invoke(period: ReportPeriod): Flow<Money> = settings.observe()
        .map { AccountCurrency.of(it.regional.currency) }
        .distinctUntilChanged()
        .flatMapLatest { currency -> ledger.observeOtherIncome(period, currency) }
        .distinctUntilChanged()
}
