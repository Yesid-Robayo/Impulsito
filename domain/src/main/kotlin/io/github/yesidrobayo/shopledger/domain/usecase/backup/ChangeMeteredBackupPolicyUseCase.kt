package io.github.yesidrobayo.shopledger.domain.usecase.backup

import io.github.yesidrobayo.shopledger.domain.model.settings.MeteredBackupPolicy
import io.github.yesidrobayo.shopledger.domain.model.settings.PreferenceChange
import io.github.yesidrobayo.shopledger.domain.port.AppSettingsRepository
import io.github.yesidrobayo.shopledger.domain.port.BackupSettingsPort
import io.github.yesidrobayo.shopledger.domain.port.Clock

/**
 * `Copias con datos móviles`: guarda la política elegida y vuelve a programar la subida, porque la
 * que espera tenía el retraso de la anterior. Elegir la que ya estaba no escribe nada.
 *
 * Que `Solo con Wi-Fi` se confirme con su advertencia lo resuelve la pantalla antes de llamar aquí.
 */
class ChangeMeteredBackupPolicyUseCase(
    private val settings: AppSettingsRepository,
    private val backup: BackupSettingsPort,
    private val clock: Clock,
) {
    /** Devuelve si cambió. */
    suspend operator fun invoke(policy: MeteredBackupPolicy): Boolean {
        if (settings.get().preferences.meteredBackupPolicy == policy) return false
        settings.updatePreference(PreferenceChange.MeteredBackup(policy), clock.now())
        backup.onMeteredPolicyChanged()
        return true
    }
}
