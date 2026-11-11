package io.github.yesidrobayo.shopledger.ui.destination

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationInfo
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * `Dónde guardar las copias`: dos opciones con el mismo peso, y la elección es de la persona.
 *
 * - Solo lee qué destino está conectado, del teléfono y sin red: elegir y `Ahora no` funcionan sin
 *   internet, y nada se guarda aquí. Conectar es de la pantalla siguiente, que prueba antes.
 * - La nube del teléfono pasa antes por la hoja `Antes de elegir`, que dice lo que se puede perder.
 *   Cerrarla vuelve aquí sin cambiar nada.
 * - La opción ya conectada lleva a la pantalla del respaldo: no se vuelve a conectar lo mismo.
 *
 * @param describe el destino conectado, o `null` si no hay ninguno.
 * @param firstRun si la pantalla es un paso del primer uso.
 * @param slotsHandoff cómo llegan las copias a la nube del teléfono.
 * @param computation el hilo donde se lee el destino, fuera del principal.
 */
class DestinationChoiceViewModel(
    private val describe: suspend () -> BackupDestinationInfo?,
    firstRun: Boolean,
    slotsHandoff: SlotsHandoff,
    private val computation: CoroutineDispatcher,
) : ViewModel() {
    private val state =
        MutableStateFlow(DestinationChoiceUiState(firstRun = firstRun, slotsHandoff = slotsHandoff))
    val uiState: StateFlow<DestinationChoiceUiState> = state.asStateFlow()

    init {
        viewModelScope.launch {
            val connected = connectedOf(read())
            state.update { it.copy(connected = connected) }
        }
    }

    fun onAction(action: DestinationChoiceAction) {
        when (action) {
            DestinationChoiceAction.Back -> go(DestinationChoiceTarget.BACK)

            DestinationChoiceAction.NotNow -> go(DestinationChoiceTarget.NOT_NOW)

            DestinationChoiceAction.ChooseSlots ->
                if (state.value.connected == ConnectedDestination.DOCUMENT_SLOTS) {
                    go(DestinationChoiceTarget.BACKUP)
                } else {
                    state.update { it.copy(slotsWarning = true) }
                }

            DestinationChoiceAction.ConfirmSlots -> {
                state.update { it.copy(slotsWarning = false) }
                go(DestinationChoiceTarget.DOCUMENT_SLOTS)
            }

            DestinationChoiceAction.DismissSlotsWarning ->
                state.update { it.copy(slotsWarning = false) }

            DestinationChoiceAction.ChooseWebDav ->
                go(
                    if (state.value.connected == ConnectedDestination.WEBDAV) {
                        DestinationChoiceTarget.BACKUP
                    } else {
                        DestinationChoiceTarget.WEBDAV_CONNECT
                    },
                )

            DestinationChoiceAction.OpenDataTransparency ->
                go(DestinationChoiceTarget.DATA_TRANSPARENCY)

            DestinationChoiceAction.TargetHandled -> state.update { it.copy(target = null) }
        }
    }

    private fun go(target: DestinationChoiceTarget) = state.update { it.copy(target = target) }

    /**
     * El destino conectado, o `null` si no hay o no se pudo leer. Una lectura fallida no cierra la
     * pantalla: las dos opciones siguen, y elegir lleva a probar antes de guardar nada.
     */
    private suspend fun read(): BackupDestinationInfo? = try {
        withContext(computation) { describe() }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private fun connectedOf(info: BackupDestinationInfo?): ConnectedDestination = when (info) {
        is BackupDestinationInfo.WebDav -> ConnectedDestination.WEBDAV
        is BackupDestinationInfo.Slots -> ConnectedDestination.DOCUMENT_SLOTS
        null -> ConnectedDestination.NONE
    }
}
