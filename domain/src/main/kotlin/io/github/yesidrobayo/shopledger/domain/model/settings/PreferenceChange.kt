package io.github.yesidrobayo.shopledger.domain.model.settings

/**
 * Un cambio de una sola preferencia de [AppPreferences].
 *
 * Cada cambio toca una sola columna. Si dos pantallas cambian dos preferencias a la vez, ninguna
 * pisa la otra con un valor viejo, como pasaría al reescribir la fila entera.
 */
sealed interface PreferenceChange {
    data class Theme(val mode: ThemeMode) : PreferenceChange

    data class LowStockNotifications(val enabled: Boolean) : PreferenceChange

    data class AppLock(val enabled: Boolean) : PreferenceChange

    data class AppLockDelay(val timeout: AppLockTimeout) : PreferenceChange

    data class MeteredBackup(val policy: MeteredBackupPolicy) : PreferenceChange
}
