package io.github.yesidrobayo.shopledger.domain.usecase.region

import io.github.yesidrobayo.shopledger.domain.model.settings.RegionalSettings
import io.github.yesidrobayo.shopledger.domain.port.AppSettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * La configuración regional guardada, para la pantalla que la edita y para los formatos de la app.
 * Vuelve a emitir solo cuando cambia algo regional: cambiar el tema no rearma ningún formato.
 *
 * No escribe nada.
 */
class ObserveRegionalSettingsUseCase(private val settings: AppSettingsRepository) {
    operator fun invoke(): Flow<RegionalSettings> =
        settings.observe().map { it.regional }.distinctUntilChanged()
}
