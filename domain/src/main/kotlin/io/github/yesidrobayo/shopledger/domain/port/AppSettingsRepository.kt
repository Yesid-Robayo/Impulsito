package io.github.yesidrobayo.shopledger.domain.port

import io.github.yesidrobayo.shopledger.domain.model.settings.AppSettings
import io.github.yesidrobayo.shopledger.domain.model.settings.BusinessProfile
import io.github.yesidrobayo.shopledger.domain.model.settings.PreferenceChange
import io.github.yesidrobayo.shopledger.domain.model.settings.RegionalSettingsInput
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
     * Bloquea la moneda del negocio en [lockedAt], con el primer registro con dinero. Solo dentro
     * de [TransactionRunner.write], la misma transacción que escribe ese registro: si el registro
     * no se guarda, la moneda tampoco queda bloqueada.
     *
     * Solo la primera vez: con la moneda ya bloqueada no cambia nada y devuelve `false`, así que
     * llamarlo en cada registro es seguro. Sin moneda confirmada la base lo rechaza: quien llama lo
     * revisa antes, con `CurrencyLock`.
     *
     * @return `true` si este llamado puso el bloqueo.
     */
    suspend fun lockCurrency(lockedAt: Instant): Boolean

    /**
     * Cambia la configuración regional por [input], ya revisada. Solo dentro de
     * [TransactionRunner.write]: es un dato del negocio y cuenta como cambio.
     *
     * Mientras la moneda no esté bloqueada, la cuenta de efectivo inicial toma la misma moneda en
     * la misma transacción: nace sin moneda, como el negocio. Ninguna fecha de negocio ya escrita
     * cambia con la zona.
     */
    suspend fun updateRegional(input: RegionalSettingsInput, updatedAt: Instant)

    /**
     * Cambia la única preferencia que dice [change]. Fuera de [TransactionRunner.write]: no cuenta
     * como un cambio sin respaldar.
     */
    suspend fun updatePreference(change: PreferenceChange, updatedAt: Instant)

    /**
     * Enciende o apaga las propinas del cobro. Solo dentro de [TransactionRunner.write]: es un dato
     * del negocio y cuenta como cambio.
     */
    suspend fun updateTipsEnabled(enabled: Boolean, updatedAt: Instant)

    /** Marca el fin del primer uso. Fuera de [TransactionRunner.write]: no cuenta como cambio. */
    suspend fun markOnboardingCompleted(completedAt: Instant)
}
