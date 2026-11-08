package io.github.yesidrobayo.shopledger.domain.usecase.payment

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.port.AppSettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * El paso al que se redondea un pago en efectivo, de los ajustes regionales, para mostrarlo junto al
 * tipo `Efectivo`. Cero es sin redondeo. Vuelve a emitir solo cuando cambia.
 */
class ObserveCashRoundingUseCase(private val settings: AppSettingsRepository) {
    operator fun invoke(): Flow<Money> =
        settings.observe().map { it.regional.cashRounding }.distinctUntilChanged()
}
