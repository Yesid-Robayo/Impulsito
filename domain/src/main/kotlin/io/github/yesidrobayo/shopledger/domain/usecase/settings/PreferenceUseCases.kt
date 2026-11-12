package io.github.yesidrobayo.shopledger.domain.usecase.settings

import io.github.yesidrobayo.shopledger.domain.model.settings.AppLockTimeout
import io.github.yesidrobayo.shopledger.domain.model.settings.PreferenceChange
import io.github.yesidrobayo.shopledger.domain.port.AppSettingsRepository
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * El bloqueo de la app como lo guardan los ajustes: si está encendido y cuánto puede estar la app en
 * segundo plano antes de pedirlo de nuevo.
 */
data class AppLockSettings(val enabled: Boolean, val timeout: AppLockTimeout)

/** El bloqueo de la app, de nuevo solo cuando cambia. */
class ObserveAppLockUseCase(private val settings: AppSettingsRepository) {
    operator fun invoke(): Flow<AppLockSettings> = settings
        .observe()
        .map { AppLockSettings(it.preferences.appLockEnabled, it.preferences.appLockTimeout) }
        .distinctUntilChanged()
}

/** Resultado de [ChangePreferenceUseCase]. */
sealed interface ChangePreferenceResult {
    data object Saved : ChangePreferenceResult

    /** La base no pudo escribir, por [failure]. La preferencia sigue como estaba. */
    data class Failed(val failure: StorageFailure) : ChangePreferenceResult
}

/**
 * Cambia una sola preferencia de uso. No es un dato del negocio: va fuera de la transacción de los
 * casos de uso y no cuenta como cambio sin respaldar.
 *
 * Quien pide una autenticación antes del cambio, como encender o apagar el bloqueo, la resuelve antes
 * de llamar: aquí solo se guarda.
 */
class ChangePreferenceUseCase(
    private val settings: AppSettingsRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(change: PreferenceChange): ChangePreferenceResult = try {
        settings.updatePreference(change, clock.now())
        ChangePreferenceResult.Saved
    } catch (failure: StorageFailureException) {
        ChangePreferenceResult.Failed(failure.failure)
    }
}
