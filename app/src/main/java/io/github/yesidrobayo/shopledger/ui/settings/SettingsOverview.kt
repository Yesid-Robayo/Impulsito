package io.github.yesidrobayo.shopledger.ui.settings

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationInfo
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupOverview
import io.github.yesidrobayo.shopledger.domain.model.settings.CurrencyCode
import io.github.yesidrobayo.shopledger.localization.UiLanguage
import io.github.yesidrobayo.shopledger.ui.ScreenReads
import io.github.yesidrobayo.shopledger.ui.backup.BackupWording
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

/**
 * Lo que Ajustes dice del respaldo y de la región, aparte de lo que se cambia aquí mismo.
 *
 * @param backup el estado del respaldo, o `null` si no se pudo leer: sin él, el grupo `Respaldo` no
 *   se dibuja, y el resto de la pantalla sigue.
 * @param destination el destino conectado, leído sin red, o `null` sin destino.
 * @param currency la moneda del negocio, o `null` mientras se lee.
 * @param language el idioma elegido para la interfaz.
 * @param readFailed el respaldo o la moneda no se pudieron leer: se dice, con `Reintentar`.
 */
@Immutable
data class SettingsOverviewUiState(
    val backup: BackupWording? = null,
    val destination: BackupDestinationInfo? = null,
    val currency: CurrencyCode? = null,
    val language: UiLanguage = UiLanguage.System,
    val readFailed: Boolean = false,
)

/**
 * El resumen de Ajustes: el estado del respaldo con su destino, la moneda y el idioma. Solo lee: no
 * guarda nada, así que no tiene errores de guardado.
 *
 * El destino se vuelve a leer cuando cambia su tipo, como al conectar o desconectar, y cuando la
 * pantalla vuelve a primer plano: así la fila no se queda con un usuario viejo.
 *
 * @param backupOverview el estado del respaldo; recibe las veces que la pantalla vuelve a primer
 *   plano, para ver un día sin copia aunque nada haya cambiado.
 * @param describeDestination el destino conectado, leído del disco.
 * @param zones la zona del negocio, para decir cuándo fue la última copia.
 * @param currencies la moneda del negocio, de nuevo cada vez que cambia.
 * @param language el idioma de la interfaz.
 * @param computation donde se lee el destino, fuera del hilo principal.
 */
class SettingsOverviewViewModel(
    backupOverview: (Flow<Unit>) -> Flow<BackupOverview>,
    private val describeDestination: suspend () -> BackupDestinationInfo?,
    zones: Flow<BusinessTimeZone?> = flowOf(null),
    currencies: Flow<CurrencyCode> = flowOf(),
    language: Flow<UiLanguage> = flowOf(UiLanguage.System),
    private val computation: CoroutineContext,
) : ViewModel() {
    private val returns = MutableStateFlow(0L)

    /** El respaldo y la moneda: una falla se dice con `Reintentar`, y no esconde sus filas. */
    private val reads = ScreenReads()

    /** El estado con el destino, leído de nuevo con cada cambio y al volver a primer plano. */
    private val wordings: Flow<Pair<BackupWording, BackupDestinationInfo?>?> =
        combine(backupOverview(returns.map {}), zones) { overview, zone -> overview to zone }
            .mapLatest<
                Pair<BackupOverview, BusinessTimeZone?>,
                Pair<BackupWording, BackupDestinationInfo?>?,
                > { (overview, zone) ->
                val destination = withContext(computation) { describeDestination() }
                val provider = (destination as? BackupDestinationInfo.Slots)?.providerLabel
                BackupWording(overview, provider, zone) to destination
            }

    private val backup: Flow<Pair<BackupWording, BackupDestinationInfo?>?> =
        reads.guard { wordings }.onStart { emit(null) }

    val uiState: StateFlow<SettingsOverviewUiState> = combine(
        backup,
        reads.guard { currencies.map<CurrencyCode, CurrencyCode?> { it } }.onStart { emit(null) },
        language.catch { emit(UiLanguage.System) },
        reads.failed,
    ) { wording, currency, chosen, unreadable ->
        SettingsOverviewUiState(wording?.first, wording?.second, currency, chosen, unreadable)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        SettingsOverviewUiState(),
    )

    fun onRetry() = reads.retry()

    /** La pantalla volvió a primer plano: el estado y el destino se leen de nuevo. */
    fun onReturnToForeground() {
        returns.value += 1
    }

    private companion object {
        /** Lo que sigue viva la lectura al rotar la pantalla. */
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
