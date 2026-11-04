package io.github.yesidrobayo.shopledger.domain.port

import io.github.yesidrobayo.shopledger.domain.model.settings.AppSettings
import io.github.yesidrobayo.shopledger.domain.model.settings.BusinessProfile
import io.github.yesidrobayo.shopledger.domain.model.settings.PreferenceChange
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow

/**
 * Los ajustes, de su única fila, que la base crea al nacer.
 *
 * No todo cambio de ajustes es un cambio del negocio: el perfil del negocio cuenta como cambio sin
 * respaldar y va dentro de [TransactionRunner.write]; las preferencias de uso y el fin del primer
 * uso no cuentan, y van fuera de ella.
 */
interface AppSettingsRepository {
    /** Los ajustes, de nuevo cada vez que cambian. */
    fun observe(): Flow<AppSettings>

    suspend fun get(): AppSettings

    /** Cambia el perfil del negocio. Solo dentro de [TransactionRunner.write]. */
    suspend fun updateProfile(profile: BusinessProfile, updatedAt: Instant)

    /**
     * Cambia la única preferencia que dice [change]. Fuera de [TransactionRunner.write]: no cuenta
     * como un cambio sin respaldar.
     */
    suspend fun updatePreference(change: PreferenceChange, updatedAt: Instant)

    /** Marca el fin del primer uso. Fuera de [TransactionRunner.write]: no cuenta como cambio. */
    suspend fun markOnboardingCompleted(completedAt: Instant)
}
