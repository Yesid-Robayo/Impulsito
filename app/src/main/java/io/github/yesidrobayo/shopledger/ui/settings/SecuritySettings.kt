package io.github.yesidrobayo.shopledger.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.settings.AppLockTimeout
import io.github.yesidrobayo.shopledger.domain.model.settings.PreferenceChange
import io.github.yesidrobayo.shopledger.domain.usecase.settings.AppLockSettings
import io.github.yesidrobayo.shopledger.domain.usecase.settings.ChangePreferenceResult
import io.github.yesidrobayo.shopledger.lock.AuthOutcome
import io.github.yesidrobayo.shopledger.lock.AuthPurpose
import io.github.yesidrobayo.shopledger.ui.components.CompactChipRow
import io.github.yesidrobayo.shopledger.ui.components.LocalDiscreetModeSwitch
import io.github.yesidrobayo.shopledger.ui.components.NoticeCard
import io.github.yesidrobayo.shopledger.ui.components.NoticeVariant
import io.github.yesidrobayo.shopledger.ui.components.SectionHeader
import io.github.yesidrobayo.shopledger.ui.components.SettingsRow
import io.github.yesidrobayo.shopledger.ui.components.SettingsRowAction
import io.github.yesidrobayo.shopledger.ui.format.LocalDiscreetMode
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * El grupo `Seguridad` de Ajustes.
 *
 * @param lock el bloqueo guardado, o `null` mientras se lee: el grupo no se dibuja hasta saberlo.
 * @param needsDeviceLock se intentó encender el bloqueo en un teléfono sin bloqueo de pantalla: se
 *   explica que primero hay que configurarlo en el sistema.
 * @param saveFailed el último cambio no se pudo guardar.
 */
@Immutable
data class SecuritySettingsUiState(
    val lock: AppLockSettings? = null,
    val needsDeviceLock: Boolean = false,
    val saveFailed: Boolean = false,
)

/** Lo que la persona hace en el grupo `Seguridad`. */
sealed interface SecurityAction {
    /**
     * Toca `Bloquear con huella o PIN`. La navegación pide primero la verificación del sistema, y
     * luego entrega su resultado con [SecuritySettingsViewModel.onLockAuthenticated].
     */
    data class LockToggle(val enabled: Boolean) : SecurityAction

    /** Elige cuánto puede estar la app en segundo plano antes de pedir de nuevo el desbloqueo. */
    data class Timeout(val timeout: AppLockTimeout) : SecurityAction
}

/**
 * El bloqueo de la app en Ajustes. Encenderlo pide autenticarse antes de que quede activo, y
 * apagarlo, como operación sensible, también: el resultado llega ya resuelto, porque la verificación
 * es del sistema y se abre sobre la actividad. Sin bloqueo de pantalla en el teléfono, no se
 * enciende.
 *
 * @param lockSource el bloqueo guardado, de nuevo cada vez que cambia.
 * @param changePreference guarda una preferencia.
 */
class SecuritySettingsViewModel(
    lockSource: () -> Flow<AppLockSettings>,
    private val changePreference: suspend (PreferenceChange) -> ChangePreferenceResult,
) : ViewModel() {
    private val needsDeviceLock = MutableStateFlow(false)
    private val saveFailed = MutableStateFlow(false)

    val uiState: StateFlow<SecuritySettingsUiState> = combine(
        lockSource().map<AppLockSettings, AppLockSettings?> { it }.catch { emit(null) },
        needsDeviceLock,
        saveFailed,
    ) { lock, noDeviceLock, failed -> SecuritySettingsUiState(lock, noDeviceLock, failed) }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            SecuritySettingsUiState(),
        )

    /** Lo que resultó de la verificación pedida para dejar el bloqueo en [enabled]. */
    fun onLockAuthenticated(enabled: Boolean, outcome: AuthOutcome) {
        when (outcome) {
            AuthOutcome.Success -> {
                needsDeviceLock.value = false
                save(PreferenceChange.AppLock(enabled))
            }

            // Sin bloqueo de pantalla: encenderlo no se puede, y se explica. Apagarlo sí se guarda, sin
            // verificación posible: nadie queda fuera, como hace la app sola al abrirse.
            AuthOutcome.NoDeviceCredential ->
                if (enabled) needsDeviceLock.value = true else save(PreferenceChange.AppLock(false))

            // Cancelada o fallida, el ajuste queda como estaba, y el interruptor también.
            AuthOutcome.Canceled, is AuthOutcome.Failed -> Unit
        }
    }

    fun onTimeoutChange(timeout: AppLockTimeout) {
        if (uiState.value.lock?.timeout == timeout) return
        save(PreferenceChange.AppLockDelay(timeout))
    }

    private fun save(change: PreferenceChange) {
        saveFailed.value = false
        viewModelScope.launch {
            when (changePreference(change)) {
                ChangePreferenceResult.Saved -> Unit
                is ChangePreferenceResult.Failed -> saveFailed.value = true
            }
        }
    }

    companion object {
        /**
         * Encender pide la verificación del desbloqueo; apagar es una operación sensible, que pide la
         * más fuerte que el teléfono ofrezca.
         */
        fun purposeFor(enabled: Boolean): AuthPurpose =
            if (enabled) AuthPurpose.UNLOCK else AuthPurpose.SENSITIVE

        /** Lo que sigue viva la lectura al rotar la pantalla. */
        private const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

/**
 * El grupo `Seguridad`: el interruptor del bloqueo, con lo que hace y lo que no, y, encendido, cuánto
 * esperar antes de pedirlo de nuevo. Después, `Modo discreto`, que dice que oculta la pantalla y no
 * protege archivos.
 */
@Composable
fun SecurityGroup(
    state: SecuritySettingsUiState,
    appName: String,
    onAction: (SecurityAction) -> Unit,
) {
    val lock = state.lock ?: return
    val spacing = AppTheme.spacing
    SectionHeader(stringResource(R.string.settings_group_security))
    SettingsRow(
        title = stringResource(R.string.lock_setting),
        summary = stringResource(R.string.lock_setting_help, appName),
        action = SettingsRowAction.Toggle(lock.enabled) { onAction(SecurityAction.LockToggle(it)) },
    )
    if (state.needsDeviceLock && !lock.enabled) {
        NoticeCard(
            text = stringResource(R.string.lock_no_device_lock),
            variant = NoticeVariant.WARNING,
            modifier = Modifier.padding(horizontal = spacing.s16),
        )
    }
    if (lock.enabled) {
        Column(Modifier.padding(horizontal = spacing.s16, vertical = spacing.s8)) {
            SectionLabel(stringResource(R.string.lock_timeout_label))
            CompactChipRow(
                options = AppLockTimeout.entries,
                label = { timeoutLabel(it) },
                selected = setOf(lock.timeout),
                onToggle = { onAction(SecurityAction.Timeout(it)) },
                multiple = false,
            )
        }
    }
    // El modo discreto: ocultar es inmediato; mostrar con el bloqueo encendido pide verificar.
    val discreet = LocalDiscreetMode.current
    val discreetSwitch = LocalDiscreetModeSwitch.current
    SettingsRow(
        title = stringResource(R.string.discreet_setting),
        summary = stringResource(R.string.discreet_setting_help),
        action = SettingsRowAction.Toggle(discreet) { discreetSwitch(it) },
    )
    if (state.saveFailed) {
        NoticeCard(
            text = stringResource(R.string.state_error_save),
            variant = NoticeVariant.ERROR,
            modifier = Modifier.padding(horizontal = spacing.s16),
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(bottom = AppTheme.spacing.s8),
    )
}

@Composable
private fun timeoutLabel(timeout: AppLockTimeout): String = when (timeout) {
    AppLockTimeout.IMMEDIATELY -> stringResource(R.string.lock_timeout_immediate)
    AppLockTimeout.ONE_MINUTE -> stringResource(R.string.lock_timeout_1_min)
    AppLockTimeout.FIVE_MINUTES -> stringResource(R.string.lock_timeout_5_min)
}

@ThemePreviews
@Composable
private fun SecurityGroupPreview() {
    PreviewTheme {
        Column {
            SecurityGroup(
                SecuritySettingsUiState(AppLockSettings(true, AppLockTimeout.ONE_MINUTE)),
                stringResource(R.string.app_name),
            ) {}
            SecurityGroup(
                SecuritySettingsUiState(
                    AppLockSettings(false, AppLockTimeout.ONE_MINUTE),
                    needsDeviceLock = true,
                ),
                stringResource(R.string.app_name),
            ) {}
        }
    }
}
