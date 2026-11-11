package io.github.yesidrobayo.shopledger.ui.backup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupCopyList
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationInfo
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationKind
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupOverview
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import io.github.yesidrobayo.shopledger.domain.usecase.backup.BackupDestinationReader
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * La pantalla del respaldo: el estado con sus hechos, el destino, sus copias y `Respaldar ahora`.
 *
 * - El estado sale del disco y del teléfono, sin red, y se recalcula al volver a primer plano.
 * - El destino y la lista se vuelven a leer cuando cambia el destino o llega una copia nueva: así
 *   la lista que se ve es la de después de la copia.
 * - Leer nunca escribe: la lista no sube, poda ni fija nada.
 *
 * @param overview el estado; recibe las veces que la pantalla vuelve a primer plano.
 * @param destination el destino conectado y sus copias.
 * @param calendar la zona del negocio, para decir `ayer, 21:14`.
 * @param requestBackup pide la copia con `Respaldar ahora`.
 * @param computation el hilo donde se leen el destino y la lista, fuera del principal.
 */
class BackupViewModel(
    overview: (Flow<Unit>) -> Flow<BackupOverview>,
    private val destination: BackupDestinationReader,
    calendar: Flow<BusinessCalendarResult>,
    private val requestBackup: suspend () -> Unit,
    private val computation: CoroutineDispatcher,
) : ViewModel() {
    private val state = MutableStateFlow(BackupUiState())
    val uiState: StateFlow<BackupUiState> = state.asStateFlow()

    // Cada vuelta a primer plano recalcula el estado con la hora nueva.
    private val refreshes = MutableStateFlow(0L)
    private var listing: Job? = null

    init {
        val zones = calendar.map { (it as? BusinessCalendarResult.Ready)?.calendar?.zone }
        viewModelScope.launch {
            combine(overview(refreshes.map {}), zones) { current, zone -> current to zone }
                .collect { (current, zone) ->
                    state.update {
                        val slots = it.destination as? BackupDestinationInfo.Slots
                        val provider = slots?.providerLabel
                        it.copy(wording = BackupWording(current, provider, zone))
                    }
                }
        }
        // El destino y la lista se leen de nuevo con otro destino o con una copia nueva.
        viewModelScope.launch {
            uiState
                .map { it.wording?.overview?.let { o -> o.destinationKind to o.lastSuccessAt } }
                .distinctUntilChanged()
                .collect { if (it != null) reload() }
        }
    }

    fun onAction(action: BackupAction) {
        when (action) {
            BackupAction.BackUpNow -> backUpNow()

            BackupAction.RetryCopies -> reload()

            BackupAction.MessageShown -> state.update { it.copy(message = null) }

            // Lo resuelve la navegación.
            BackupAction.Back, BackupAction.ChooseDestination, BackupAction.ReviewPolicy -> Unit
        }
    }

    /** La pantalla volvió a primer plano: el estado se calcula con la hora de ahora. */
    fun onReturnToForeground() {
        refreshes.value += 1
    }

    private fun reload() {
        listing?.cancel()
        state.update { it.copy(copies = null) }
        listing = viewModelScope.launch {
            // Sin destino, o si no se pudo leer, no hay nombre que mostrar.
            val info = read { destination.describe() }
            val copies = read { destination.listCopies() } ?: BackupCopyList.Failed
            state.update { current ->
                val provider = (info as? BackupDestinationInfo.Slots)?.providerLabel
                current.copy(
                    destination = info,
                    copies = copies,
                    wording = current.wording?.copy(providerLabel = provider),
                )
            }
        }
    }

    private fun backUpNow() {
        if (state.value.requesting) return
        state.update { it.copy(requesting = true) }
        viewModelScope.launch {
            try {
                withContext(computation) { requestBackup() }
                // Con WebDAV, sin red la copia queda encolada: se dice, para que nadie espere.
                val webDav =
                    state.value.wording?.overview?.destinationKind == BackupDestinationKind.WEBDAV
                if (webDav && !destination.hasNetwork()) {
                    state.update { it.copy(message = BackupMessage.NO_INTERNET) }
                }
            } finally {
                state.update { it.copy(requesting = false) }
            }
        }
    }

    /**
     * Lo leído, o nulo si falló: una falla de lectura no cierra la pantalla, y la lista lo dice
     * como un error, nunca como una lista vacía.
     */
    private suspend fun <T> read(block: suspend () -> T): T? = try {
        withContext(computation) { block() }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        null
    }
}
