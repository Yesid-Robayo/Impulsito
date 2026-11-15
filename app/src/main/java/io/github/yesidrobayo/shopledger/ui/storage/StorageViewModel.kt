package io.github.yesidrobayo.shopledger.ui.storage

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yesidrobayo.shopledger.domain.model.erase.EraseDestination
import io.github.yesidrobayo.shopledger.domain.model.erase.EraseOutcome
import io.github.yesidrobayo.shopledger.domain.model.erase.ErasePhase
import io.github.yesidrobayo.shopledger.domain.model.erase.EraseScope
import io.github.yesidrobayo.shopledger.domain.model.erase.EraseStop
import io.github.yesidrobayo.shopledger.domain.model.erase.EraseSummary
import io.github.yesidrobayo.shopledger.domain.model.erase.StorageUsage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** En qué paso va `Borrar mis datos`. Nunca empieza solo: cada paso lo da la persona. */
sealed interface EraseFlow {
    /** 1. `¿Qué se borra?`, sin nada elegido de antemano. */
    data class Scope(val selected: EraseScope?) : EraseFlow

    /** 2. `Lo que se va a borrar`, con lo que no alcanza y `Exportar antes`. */
    data class Review(val scope: EraseScope) : EraseFlow

    /** 3. `¿Borrar definitivamente?`, que pide escribir la palabra. */
    data class Confirm(val scope: EraseScope, val typed: String) : EraseFlow

    /** 4. `Borrando`, sin `Cancelar`. [cloud] dice si va por las copias del destino. */
    data class Running(val cloud: Boolean) : EraseFlow

    /** Se detuvo por [reason]. Salvo un fallo local, este teléfono no se tocó. */
    data class Stopped(val reason: EraseStop) : EraseFlow

    /** `¿Borrar solo este teléfono?`, la confirmación expresa tras un borrado que no siguió. */
    data object ConfirmDeviceOnly : EraseFlow
}

/**
 * Lo que muestra `Almacenamiento y borrado`.
 *
 * @property usage lo que ocupa cada parte, o `null` mientras se mide.
 * @property summary lo que se perdería al borrar, o `null` mientras se lee o si no se leyó.
 * @property summaryFailed si no se pudo leer lo que se perdería: borrar sigue disponible.
 * @property fromAndroid si llegó desde los ajustes de Android.
 * @property finished todo quedó borrado: la app vuelve a empezar.
 */
@Immutable
data class StorageUiState(
    val usage: StorageUsage? = null,
    val summary: EraseSummary? = null,
    val summaryFailed: Boolean = false,
    val fromAndroid: Boolean = false,
    val flow: EraseFlow? = null,
    val finished: Boolean = false,
) {
    /** Si `Borrar mis datos` se puede empezar: nunca durante una restauración. */
    val eraseAvailable: Boolean get() = summary?.restoreInProgress != true

    /** Lo que se perdería sin respaldo, para destacar `Respaldar ahora`. */
    val unsyncedChanges: Long get() = summary?.unsyncedChanges ?: 0
}

/** Lo que la persona hace en `Almacenamiento y borrado`. */
sealed interface StorageAction {
    data object Back : StorageAction

    data object BackupNow : StorageAction

    data object OpenLocalCopies : StorageAction

    data object OpenGuide : StorageAction

    data object OpenErase : StorageAction

    data class ChooseScope(val scope: EraseScope) : StorageAction

    data object Continue : StorageAction

    /** Vuelve al paso anterior del borrado, o lo cierra desde el primero. */
    data object StepBack : StorageAction

    data class Typed(val text: String) : StorageAction

    /** Escribió la palabra y la verificación del sistema, si la pide el bloqueo, pasó. */
    data object EraseAuthorized : StorageAction

    data object ExportFirst : StorageAction

    data object Retry : StorageAction

    data object Reconnect : StorageAction

    data object DeviceOnly : StorageAction

    /** Confirmó `Borrar solo este teléfono`, con la verificación si la pide el bloqueo. */
    data object DeviceOnlyAuthorized : StorageAction

    data object Dismiss : StorageAction

    /** Volvió a primer plano: se mide otra vez. */
    data object Refresh : StorageAction
}

/**
 * `Almacenamiento y borrado`. Mide fuera del hilo principal, y un fallo al medir solo deja esa
 * fila en `No disponible`. `Borrar mis datos` va paso a paso y nunca se ejecuta solo; mientras
 * borra no se puede cerrar, y si se detiene dice por qué y qué hacer. Un borrado que quedó
 * esperando a la nube aparece al abrir, para retomarlo o borrar solo este teléfono.
 *
 * @param confirmWord la palabra que se escribe para confirmar, en el idioma activo.
 * @param erase empieza el borrado confirmado.
 * @param resume retoma el borrado en espera.
 * @param deviceOnly borra solo este teléfono, con su confirmación expresa.
 */
class StorageViewModel(
    private val usage: suspend () -> StorageUsage,
    private val summary: suspend () -> EraseSummary?,
    private val erase: suspend (EraseScope) -> EraseOutcome,
    private val resume: suspend () -> EraseOutcome?,
    private val deviceOnly: suspend () -> EraseOutcome,
    private val backupNow: suspend () -> Unit,
    private val confirmWord: String,
    private val computation: CoroutineDispatcher,
    fromAndroid: Boolean,
) : ViewModel() {
    private val state = MutableStateFlow(StorageUiState(fromAndroid = fromAndroid))
    val uiState: StateFlow<StorageUiState> = state.asStateFlow()
    private var measuring: Job? = null

    init {
        measure(offerPending = true)
    }

    fun onAction(action: StorageAction) {
        when (action) {
            // La navegación resuelve atrás, las copias locales, exportar y reconectar.
            StorageAction.Back, StorageAction.OpenLocalCopies, StorageAction.ExportFirst,
            StorageAction.Reconnect, StorageAction.OpenGuide,
            -> Unit

            StorageAction.BackupNow -> viewModelScope.launch { safely { backupNow() } }

            StorageAction.Refresh -> if (state.value.flow == null) measure(offerPending = false)

            StorageAction.OpenErase -> {
                val current = state.value
                if (current.eraseAvailable && current.flow == null) {
                    state.update { it.copy(flow = EraseFlow.Scope(null)) }
                }
            }

            is StorageAction.ChooseScope -> updateFlow { flow ->
                if (flow is EraseFlow.Scope) EraseFlow.Scope(action.scope) else flow
            }

            StorageAction.Continue -> updateFlow { flow ->
                when (flow) {
                    is EraseFlow.Scope -> flow.selected?.let(EraseFlow::Review) ?: flow
                    is EraseFlow.Review -> EraseFlow.Confirm(flow.scope, typed = "")
                    else -> flow
                }
            }

            StorageAction.StepBack -> updateFlow { flow ->
                when (flow) {
                    is EraseFlow.Review -> EraseFlow.Scope(flow.scope)
                    is EraseFlow.Confirm -> EraseFlow.Review(flow.scope)
                    is EraseFlow.Running -> flow
                    else -> null
                }
            }

            is StorageAction.Typed -> updateFlow { flow ->
                if (flow is EraseFlow.Confirm) flow.copy(typed = action.text) else flow
            }

            StorageAction.EraseAuthorized -> {
                val flow = state.value.flow as? EraseFlow.Confirm ?: return
                if (!matches(flow.typed)) return
                val cloud =
                    flow.scope == EraseScope.DEVICE_AND_COPIES &&
                        state.value.summary?.destination != EraseDestination.NONE
                run(cloud) { erase(flow.scope) }
            }

            StorageAction.Retry -> {
                val stopped = state.value.flow as? EraseFlow.Stopped ?: return
                val local = stopped.reason == EraseStop.LOCAL_FAILED
                run(cloud = !local) { resume() ?: EraseOutcome.Finished }
            }

            StorageAction.DeviceOnly -> if (state.value.flow is EraseFlow.Stopped) {
                state.update { it.copy(flow = EraseFlow.ConfirmDeviceOnly) }
            }

            StorageAction.DeviceOnlyAuthorized ->
                if (state.value.flow == EraseFlow.ConfirmDeviceOnly) {
                    run(cloud = false) { deviceOnly() }
                }

            StorageAction.Dismiss -> updateFlow { flow ->
                if (flow is EraseFlow.Running) flow else null
            }
        }
    }

    /** Si [typed] es la palabra pedida, sin importar mayúsculas ni espacios a los lados. */
    fun matches(typed: String): Boolean = typed.trim().equals(confirmWord, ignoreCase = true)

    private fun updateFlow(change: (EraseFlow) -> EraseFlow?) {
        state.update { current ->
            current.flow?.let { current.copy(flow = change(it)) } ?: current
        }
    }

    /**
     * Corre un paso del borrado hasta que termine, aunque la pantalla se cierre: un borrado a
     * medias se retoma, pero cortarlo aquí no ahorra nada.
     */
    private fun run(cloud: Boolean, step: suspend () -> EraseOutcome) {
        state.update { it.copy(flow = EraseFlow.Running(cloud)) }
        viewModelScope.launch {
            val outcome =
                withContext(NonCancellable) {
                    try {
                        step()
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Exception) {
                        // Nada del error se muestra: sin la nube confirmada, lo local no se tocó.
                        val reason = if (cloud) EraseStop.UNREACHABLE else EraseStop.LOCAL_FAILED
                        EraseOutcome.Stopped(reason)
                    }
                }
            when (outcome) {
                EraseOutcome.Finished -> state.update { it.copy(finished = true) }

                is EraseOutcome.Stopped ->
                    state.update { it.copy(flow = EraseFlow.Stopped(outcome.reason)) }
            }
        }
    }

    private fun measure(offerPending: Boolean) {
        measuring?.cancel()
        measuring =
            viewModelScope.launch {
                val read = withContext(computation) { safely { summary() } }
                state.update { current ->
                    val pending = read?.pending
                    val offered =
                        if (offerPending && current.flow == null && pending != null) {
                            EraseFlow.Stopped(pendingStop(pending))
                        } else {
                            current.flow
                        }
                    current.copy(summary = read, summaryFailed = read == null, flow = offered)
                }
                val measured = withContext(computation) { safely { usage() } }
                state.update { it.copy(usage = measured ?: UNMEASURED) }
            }
    }

    private suspend fun <T> safely(block: suspend () -> T): T? = try {
        block()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        null
    }

    private companion object {
        val UNMEASURED = StorageUsage(null, null, null, null, null)

        /**
         * Un borrado en espera al abrir: si esperaba a la nube, algunas copias pueden seguir allí
         * y este teléfono no se tocó; si ya iba por lo local, falta terminarlo.
         */
        fun pendingStop(phase: ErasePhase): EraseStop = when (phase) {
            ErasePhase.REMOTE -> EraseStop.PARTIAL
            ErasePhase.LOCAL -> EraseStop.LOCAL_FAILED
        }
    }
}
