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
import io.github.yesidrobayo.shopledger.domain.model.settings.AppPreferences
import io.github.yesidrobayo.shopledger.domain.model.settings.PreferenceChange
import io.github.yesidrobayo.shopledger.domain.model.settings.ThemeMode
import io.github.yesidrobayo.shopledger.domain.usecase.settings.ChangePreferenceResult
import io.github.yesidrobayo.shopledger.ui.ScreenReads
import io.github.yesidrobayo.shopledger.ui.components.CompactChipRow
import io.github.yesidrobayo.shopledger.ui.components.NoticeCard
import io.github.yesidrobayo.shopledger.ui.components.NoticeVariant
import io.github.yesidrobayo.shopledger.ui.components.SectionHeader
import io.github.yesidrobayo.shopledger.ui.components.SettingsRow
import io.github.yesidrobayo.shopledger.ui.components.SettingsRowAction
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Las preferencias de uso que se cambian en Ajustes: los avisos de stock y el tema. No son datos
 * del negocio: guardarlas no cuenta como cambio sin respaldar.
 *
 * @param stockAlerts si los avisos de stock están encendidos, o `null` mientras se lee.
 * @param theme el tema elegido, o `null` mientras se lee.
 * @param saveFailed el último cambio no se pudo guardar.
 * @param readFailed las preferencias no se pudieron leer: se dice con `Reintentar`.
 */
@Immutable
data class UsageSettingsUiState(
    val stockAlerts: Boolean? = null,
    val theme: ThemeMode? = null,
    val saveFailed: Boolean = false,
    val readFailed: Boolean = false,
)

/** Lo que la persona cambia en `Avisos` y `Apariencia`. */
sealed interface UsageAction {
    /** Enciende o apaga los avisos de stock. Encenderlos puede ofrecer el permiso de avisos. */
    data class StockAlerts(val enabled: Boolean) : UsageAction

    /** Elige `Automático`, `Claro` u `Oscuro`. La paleta no cambia. */
    data class Theme(val mode: ThemeMode) : UsageAction

    /** Abre los ajustes de avisos de la app en el sistema. */
    data object OpenNotificationSettings : UsageAction

    /** Vuelve a leer las preferencias tras una falla. */
    data object Retry : UsageAction
}

/**
 * Los avisos de stock y el tema. El tema se aplica a toda la app desde la actividad, que lee la
 * misma preferencia: aquí solo se guarda. Cambiar el tema nunca toca la paleta.
 *
 * @param preferences las preferencias de uso, de nuevo cada vez que cambian.
 * @param changePreference guarda una preferencia, en su propia columna.
 */
class UsageSettingsViewModel(
    preferences: () -> Flow<AppPreferences>,
    private val changePreference: suspend (PreferenceChange) -> ChangePreferenceResult,
) : ViewModel() {
    private val saveFailed = MutableStateFlow(false)

    /** La lectura de las preferencias: una falla se dice con `Reintentar`, y la app no se cierra. */
    private val reads = ScreenReads()

    val uiState: StateFlow<UsageSettingsUiState> = combine(
        // Empieza sin nada, para que una falla antes de la primera lectura también llegue al estado.
        reads.guard { preferences().map<AppPreferences, AppPreferences?> { it } }
            .onStart { emit(null) },
        saveFailed,
        reads.failed,
    ) { current, failed, unreadable ->
        UsageSettingsUiState(
            stockAlerts = current?.lowStockNotificationsEnabled.takeUnless { unreadable },
            theme = current?.themeMode.takeUnless { unreadable },
            saveFailed = failed,
            readFailed = unreadable,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        UsageSettingsUiState(),
    )

    fun onRetry() = reads.retry()

    fun onStockAlertsChange(enabled: Boolean) {
        if (uiState.value.stockAlerts == enabled) return
        save(PreferenceChange.LowStockNotifications(enabled))
    }

    fun onThemeChange(mode: ThemeMode) {
        if (uiState.value.theme == mode) return
        save(PreferenceChange.Theme(mode))
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

    private companion object {
        /** Lo que sigue viva la lectura al rotar la pantalla. */
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

/**
 * `Avisos`: el interruptor de los avisos de stock. Encendido con los avisos bloqueados en el
 * teléfono, un aviso lo explica y lleva a los ajustes del sistema; la lista de lo que se está
 * acabando funciona igual.
 *
 * @param blocked si los avisos no pueden salir: sin permiso, apagados o con el canal silenciado.
 */
@Composable
fun AlertsGroup(state: UsageSettingsUiState, blocked: Boolean, onAction: (UsageAction) -> Unit) {
    if (state.readFailed) {
        // Sin preferencias no hay avisos ni tema que mostrar: se dice una vez, con `Reintentar`.
        NoticeCard(
            text = stringResource(R.string.state_error_read),
            variant = NoticeVariant.ERROR,
            actionLabel = stringResource(R.string.action_retry),
            onAction = { onAction(UsageAction.Retry) },
            modifier = Modifier.padding(horizontal = AppTheme.spacing.s16),
        )
        return
    }
    val enabled = state.stockAlerts ?: return
    SectionHeader(stringResource(R.string.settings_group_alerts))
    SettingsRow(
        title = stringResource(R.string.settings_stock_alerts),
        action = SettingsRowAction.Toggle(enabled) { onAction(UsageAction.StockAlerts(it)) },
    )
    if (enabled && blocked) {
        NoticeCard(
            text = stringResource(R.string.permission_notifications_denied_settings),
            variant = NoticeVariant.INFO,
            actionLabel = stringResource(R.string.action_open_settings),
            onAction = { onAction(UsageAction.OpenNotificationSettings) },
            modifier = Modifier.padding(horizontal = AppTheme.spacing.s16),
        )
    }
}

/**
 * `Apariencia`: `Tema`, con `Automático`, `Claro` y `Oscuro` en una fila de selección única. Si el
 * último cambio no se guardó, lo dice debajo.
 */
@Composable
fun AppearanceGroup(state: UsageSettingsUiState, onAction: (UsageAction) -> Unit) {
    val theme = state.theme ?: return
    val spacing = AppTheme.spacing
    SectionHeader(stringResource(R.string.settings_group_appearance))
    Column(Modifier.padding(horizontal = spacing.s16, vertical = spacing.s8)) {
        Text(
            stringResource(R.string.settings_theme_title),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = spacing.s8),
        )
        CompactChipRow(
            options = ThemeMode.entries,
            label = { themeLabel(it) },
            selected = setOf(theme),
            onToggle = { onAction(UsageAction.Theme(it)) },
            multiple = false,
        )
    }
    if (state.saveFailed) {
        NoticeCard(
            text = stringResource(R.string.state_error_save),
            variant = NoticeVariant.ERROR,
            modifier = Modifier.padding(horizontal = spacing.s16),
        )
    }
}

@Composable
private fun themeLabel(mode: ThemeMode): String = when (mode) {
    ThemeMode.SYSTEM -> stringResource(R.string.settings_theme_system)
    ThemeMode.LIGHT -> stringResource(R.string.settings_theme_light)
    ThemeMode.DARK -> stringResource(R.string.settings_theme_dark)
}

@ThemePreviews
@Composable
private fun UsageGroupsPreview() {
    PreviewTheme {
        Column {
            val state = UsageSettingsUiState(stockAlerts = true, theme = ThemeMode.SYSTEM)
            AlertsGroup(state, blocked = true) {}
            AppearanceGroup(state.copy(saveFailed = true)) {}
        }
    }
}
