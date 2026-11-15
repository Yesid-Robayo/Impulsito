package io.github.yesidrobayo.shopledger.ui.privacy

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationInfo
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupStatus
import io.github.yesidrobayo.shopledger.domain.model.safety.CloudAccountCheck
import io.github.yesidrobayo.shopledger.domain.model.safety.CloudAccountKind
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Marca o desmarca un punto para un tipo de destino; nulo si no se guardó. */
typealias MarkCheck =
    suspend (CloudAccountKind, CloudAccountCheck, Boolean) -> Set<CloudAccountCheck>?

/**
 * `Seguridad de la cuenta de la nube`: la lista corta que la persona marca, según su destino, y
 * la guía para no perder la cuenta. La app no comprueba nada en el servicio: solo guarda lo
 * marcado en este teléfono. Nada de esto usa la red.
 *
 * Una marca cambia en pantalla al tocarla; si no se guarda, vuelve a su estado anterior y se dice.
 *
 * @param describeDestination el destino conectado, leído del teléfono, o nulo sin destino.
 * @param checkedFor los puntos marcados para un tipo de destino.
 * @param mark marca o desmarca un punto y devuelve la lista guardada, o nulo si no se guardó.
 * @param backupStatus el estado del respaldo, para decir si se perdió el acceso a la nube.
 * @param computation donde se leen los archivos, fuera del hilo principal.
 */
class CloudAccountViewModel(
    private val describeDestination: suspend () -> BackupDestinationInfo?,
    private val checkedFor: suspend (CloudAccountKind) -> Set<CloudAccountCheck>,
    private val mark: MarkCheck,
    backupStatus: Flow<BackupStatus>,
    private val computation: CoroutineContext,
) : ViewModel() {
    private val local = MutableStateFlow(CloudAccountUiState())
    private var reading: Job? = null

    /** Si el respaldo dice que se perdió el acceso. Un estado que no se lee no avisa de nada. */
    private val permissionLost: Flow<Boolean> =
        backupStatus
            .map { it == BackupStatus.PERMISSION_LOST }
            .onStart { emit(false) }
            .catch { emit(false) }

    val uiState: StateFlow<CloudAccountUiState> =
        combine(local, permissionLost) { state, lost ->
            val sessionClosed =
                (state.destination.knownOrNull() as? BackupDestinationInfo.Slots)
                    ?.sessionRequired == true
            state.copy(accessLost = lost || sessionClosed)
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            CloudAccountUiState(),
        )

    init {
        refresh()
    }

    fun onAction(action: CloudAccountAction) {
        when (action) {
            is CloudAccountAction.Mark -> onMark(action.check, action.checked)

            CloudAccountAction.Refresh -> refresh()

            CloudAccountAction.MessageShown -> local.update { it.copy(saveFailed = false) }

            // Las resuelven la pantalla y la navegación.
            CloudAccountAction.Back,
            CloudAccountAction.OpenProvider,
            is CloudAccountAction.OpenGuide,
            CloudAccountAction.ChooseDestination,
            CloudAccountAction.Reconnect,
            -> Unit
        }
    }

    /**
     * Lee el destino y, si hay uno, lo marcado para él. Una lectura nueva reemplaza a la que
     * seguía en curso.
     */
    private fun refresh() {
        reading?.cancel()
        reading =
            viewModelScope.launch {
                val (destination, checked) =
                    withContext(computation) {
                        val destination: Fact<BackupDestinationInfo?> =
                            attempt { Fact.Known(describeDestination()) } ?: Fact.Unavailable
                        val kind = destination.knownOrNull()?.kind()
                        // Lo marcado que no se puede leer cuenta como nada marcado: es una guía.
                        val checked = kind?.let { attempt { checkedFor(it) } }.orEmpty()
                        destination to checked
                    }
                local.update {
                    it.copy(loading = false, destination = destination, checked = checked)
                }
            }
    }

    private fun onMark(check: CloudAccountCheck, checked: Boolean) {
        val state = local.value
        if (!state.canMark) return
        val kind = state.kind ?: return
        val before = state.checked
        local.update {
            it.copy(
                checked = if (checked) before + check else before - check,
                saveFailed = false,
            )
        }
        viewModelScope.launch {
            val saved = withContext(computation) { attempt { mark(kind, check, checked) } }
            local.update {
                if (saved == null) {
                    it.copy(checked = before, saveFailed = true)
                } else {
                    it.copy(checked = saved)
                }
            }
        }
    }

    private companion object {
        /** Lo que sigue viva la lectura al rotar la pantalla. */
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

/** El valor de [block], o nulo si falla: leer o guardar una marca nunca cierra la pantalla. */
private suspend fun <T> attempt(block: suspend () -> T): T? = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    null
}
