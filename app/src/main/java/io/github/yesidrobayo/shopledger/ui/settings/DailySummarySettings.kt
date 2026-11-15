package io.github.yesidrobayo.shopledger.ui.settings

import android.content.Context
import android.icu.text.DateFormat
import android.icu.util.TimeZone
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.usecase.report.SetDailySummaryTimeResult
import io.github.yesidrobayo.shopledger.domain.usecase.report.SetDailySummaryTimeUseCase
import io.github.yesidrobayo.shopledger.navigation.ScreenModel
import io.github.yesidrobayo.shopledger.navigation.rememberAppViewModel
import io.github.yesidrobayo.shopledger.notification.NotificationChannels
import io.github.yesidrobayo.shopledger.ui.components.ActionState
import io.github.yesidrobayo.shopledger.ui.components.ActionVariant
import io.github.yesidrobayo.shopledger.ui.components.AppBottomSheet
import io.github.yesidrobayo.shopledger.ui.components.NoticeCard
import io.github.yesidrobayo.shopledger.ui.components.NoticeVariant
import io.github.yesidrobayo.shopledger.ui.components.PrimaryActionButton
import io.github.yesidrobayo.shopledger.ui.components.SettingsRow
import io.github.yesidrobayo.shopledger.ui.components.SettingsRowAction
import io.github.yesidrobayo.shopledger.ui.components.saveErrorText
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.permission.NotificationPermissionPrompt
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import java.util.Date
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalTime

// `Resumen diario`, en el grupo `Avisos` de Ajustes: la hora de una notificación local que resume
// el día, apagada por defecto. Sin red y sin montos con el teléfono bloqueado.

/** Lo que muestra la fila del resumen diario. */
sealed interface DailySummaryUiState {
    /** Todavía no se leyó: la fila no aparece. */
    data object Loading : DailySummaryUiState

    /** No se pudo leer: se dice en su lugar, y lo demás de Ajustes sigue a mano. */
    data object ReadError : DailySummaryUiState

    /**
     * La hora guardada, o `null` si está apagado. [choosing] abre la hoja de la hora; [saving] y
     * [saveFailed] dicen cómo va el guardado, `true` si falló por espacio.
     */
    @Immutable
    data class Ready(
        val time: LocalTime?,
        val choosing: Boolean = false,
        val saving: Boolean = false,
        val saveFailed: Boolean? = null,
    ) : DailySummaryUiState
}

/** Lo que la persona hace con el resumen diario. */
sealed interface DailySummaryAction {
    /** Encendió o apagó el interruptor. Encenderlo abre la hoja de la hora. */
    data class Toggle(val enabled: Boolean) : DailySummaryAction

    /** Tocó la hora, para cambiarla. */
    data object ChooseTime : DailySummaryAction

    /** Guardó la hora elegida en la hoja. */
    data class Save(val time: LocalTime) : DailySummaryAction

    /** Cerró la hoja sin guardar. */
    data object Dismiss : DailySummaryAction
}

/**
 * El estado del resumen diario. Guardar una hora o apagarlo es un dato del negocio y va en su
 * propia transacción; programar el trabajo del teléfono lo hace quien sigue la hora al abrir la
 * base, así que esta fila solo escribe.
 *
 * @param observe la hora guardada, de nuevo con cada cambio.
 * @param save guarda una hora, o apaga el resumen con `null`.
 */
class DailySummaryViewModel(
    observe: Flow<LocalTime?>,
    private val save: suspend (LocalTime?) -> SetDailySummaryTimeResult,
) : ViewModel() {
    private val local = MutableStateFlow(Local())

    val uiState: StateFlow<DailySummaryUiState> =
        combine(observe, local) { time, shown ->
            val state: DailySummaryUiState =
                DailySummaryUiState.Ready(
                    time = time,
                    choosing = shown.choosing,
                    saving = shown.saving,
                    saveFailed = shown.saveFailed,
                )
            state
        }
            .catch { emit(DailySummaryUiState.ReadError) }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(STOP_AFTER_MS),
                DailySummaryUiState.Loading,
            )

    fun onAction(action: DailySummaryAction) {
        when (action) {
            is DailySummaryAction.Toggle ->
                if (action.enabled) {
                    local.update { it.copy(choosing = true, saveFailed = null) }
                } else {
                    persist(null)
                }

            DailySummaryAction.ChooseTime ->
                local.update { it.copy(choosing = true, saveFailed = null) }

            is DailySummaryAction.Save -> persist(action.time)

            DailySummaryAction.Dismiss -> local.update { it.copy(choosing = false) }
        }
    }

    private fun persist(time: LocalTime?) {
        if (local.value.saving) return
        local.update { it.copy(saving = true, saveFailed = null) }
        viewModelScope.launch {
            when (val result = save(time)) {
                SetDailySummaryTimeResult.Saved ->
                    local.update { it.copy(saving = false, choosing = false) }

                is SetDailySummaryTimeResult.Failed -> local.update {
                    it.copy(saving = false, saveFailed = result.failure.isOutOfSpace)
                }
            }
        }
    }

    private data class Local(
        val choosing: Boolean = false,
        val saving: Boolean = false,
        val saveFailed: Boolean? = null,
    )

    private companion object {
        /** Tras girar la pantalla, la lectura sigue viva este tiempo y no se repite. */
        const val STOP_AFTER_MS = 5_000L
    }
}

/**
 * `Resumen diario` en Ajustes, con su propio ViewModel en la entrada de Ajustes. Si los avisos no
 * pueden salir, lo explica y lleva a los ajustes del sistema; se mira de nuevo al volver.
 */
@Composable
internal fun DailySummarySetting() {
    val model = rememberAppViewModel(key = DAILY_SUMMARY_KEY) { dependencies ->
        val ports = dependencies.ports
        val clock = dependencies.container.clock
        val set = SetDailySummaryTimeUseCase(ports.transactions, ports.settings, clock)
        DailySummaryViewModel(
            observe = ports.settings.observe().map { it.notices.dailySummaryTime },
            save = { set(it) },
        )
    }
    val context = LocalContext.current
    var blocked by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        blocked = dailySummaryBlocked(context)
        onPauseOrDispose {}
    }
    when (model) {
        ScreenModel.Opening, ScreenModel.Unavailable -> Unit

        is ScreenModel.Ready -> {
            val viewModel = model.viewModel
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            DailySummaryRow(
                state = state,
                blocked = blocked,
                onAction = viewModel::onAction,
                onOpenSettings = { NotificationPermissionPrompt.openSettings(context) },
            )
        }
    }
}

/**
 * La fila del resumen diario, sin ViewModel: el interruptor con la hora o `Apagado`, la ayuda, el
 * aviso si los avisos no pueden salir, y la hoja de la hora.
 */
@Composable
internal fun DailySummaryRow(
    state: DailySummaryUiState,
    blocked: Boolean,
    onAction: (DailySummaryAction) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val rows = Modifier.padding(horizontal = AppTheme.spacing.s16)
    if (state is DailySummaryUiState.ReadError) {
        NoticeCard(stringResource(R.string.state_error_read), NoticeVariant.ERROR, rows)
        return
    }
    val ready = state as? DailySummaryUiState.Ready ?: return
    val time = ready.time
    val off = stringResource(R.string.settings_daily_summary_off)
    SettingsRow(
        title = stringResource(R.string.settings_daily_summary),
        summary = time?.let { timeText(it) } ?: off,
        action =
            SettingsRowAction.Toggle(time != null) { onAction(DailySummaryAction.Toggle(it)) },
    )
    if (time != null) {
        SettingsRow(
            title = stringResource(R.string.settings_daily_summary_time),
            summary = timeText(time),
            action = SettingsRowAction.Navigation { onAction(DailySummaryAction.ChooseTime) },
        )
        if (blocked) {
            NoticeCard(
                text = stringResource(R.string.permission_notifications_denied_settings),
                variant = NoticeVariant.INFO,
                actionLabel = stringResource(R.string.action_open_settings),
                onAction = onOpenSettings,
                modifier = rows,
            )
        }
    }
    if (!ready.choosing && ready.saveFailed != null) {
        NoticeCard(stringResource(saveErrorText(ready.saveFailed)), NoticeVariant.ERROR, rows)
    }
    if (ready.choosing) TimeSheet(ready, onAction)
}

/** La hoja de la hora: el reloj del sistema, la ayuda y `Guardar hora`. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeSheet(state: DailySummaryUiState.Ready, onAction: (DailySummaryAction) -> Unit) {
    val initial = state.time ?: DEFAULT_TIME
    val context = LocalContext.current
    val picker =
        rememberTimePickerState(
            initialHour = initial.hour,
            initialMinute = initial.minute,
            is24Hour = android.text.format.DateFormat.is24HourFormat(context),
        )
    AppBottomSheet(
        onDismissRequest = { onAction(DailySummaryAction.Dismiss) },
        title = stringResource(R.string.settings_daily_summary_time),
        action = {
            PrimaryActionButton(
                text = stringResource(R.string.settings_daily_summary_save),
                onClick = {
                    onAction(DailySummaryAction.Save(LocalTime(picker.hour, picker.minute)))
                },
                variant = ActionVariant.PRIMARY,
                state = if (state.saving) ActionState.WORKING else ActionState.IDLE,
                modifier = Modifier.fillMaxWidth(),
            )
        },
    ) {
        Column {
            TimePicker(state = picker)
        }
        Text(
            stringResource(R.string.settings_daily_summary_help),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state.saveFailed?.let {
            NoticeCard(stringResource(saveErrorText(it)), NoticeVariant.ERROR)
        }
    }
}

/** [time] como lo escribe la región de la interfaz: `20:30` o `8:30 p. m.`. */
@Composable
private fun timeText(time: LocalTime): String {
    val locale = LocalAppFormats.current.uiLocale
    val format =
        remember(locale) {
            DateFormat.getInstanceForSkeleton(TIME_SKELETON, locale).apply {
                timeZone = TimeZone.GMT_ZONE
            }
        }
    val millis = (time.hour * MINUTES_PER_HOUR + time.minute) * MILLIS_PER_MINUTE
    return synchronized(format) { format.format(Date(millis)) }
}

/**
 * Si el resumen no puede salir: sin el permiso de avisos, con los avisos de la app apagados o con
 * su canal silenciado.
 */
private fun dailySummaryBlocked(context: Context): Boolean {
    val manager = NotificationManagerCompat.from(context)
    if (!manager.areNotificationsEnabled()) return true
    val channel =
        manager.getNotificationChannelCompat(NotificationChannels.DAILY_SUMMARY_CHANNEL_ID)
    return channel?.importance == NotificationManagerCompat.IMPORTANCE_NONE
}

/** La hora que propone la hoja la primera vez: el cierre de un día de trabajo común. */
private val DEFAULT_TIME = LocalTime(20, 0)

/** Hora y minutos, en 12 o 24 horas según la región. */
private const val TIME_SKELETON = "jm"
private const val MINUTES_PER_HOUR = 60L
private const val MILLIS_PER_MINUTE = 60_000L

/** La clave del ViewModel del resumen diario dentro de la entrada de Ajustes. */
private const val DAILY_SUMMARY_KEY = "settings-daily-summary"
