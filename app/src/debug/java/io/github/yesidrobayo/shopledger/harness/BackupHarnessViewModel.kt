package io.github.yesidrobayo.shopledger.harness

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yesidrobayo.shopledger.data.backup.BackupNowRequest
import io.github.yesidrobayo.shopledger.data.harness.HarnessRestore
import io.github.yesidrobayo.shopledger.data.harness.HarnessResult
import io.github.yesidrobayo.shopledger.data.harness.HarnessState
import io.github.yesidrobayo.shopledger.data.harness.RestoreAnswer
import io.github.yesidrobayo.shopledger.data.harness.RestoreQuestion
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupStatusReport
import io.github.yesidrobayo.shopledger.domain.model.safety.PolicyConsentStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Cada acción del arnés. Solo corre una a la vez. */
internal enum class HarnessAction {
    POLICY,
    WEBDAV,
    SLOTS,
    CHANGES,
    BACKUP_NOW,
    RESTORE,
}

/** Cómo terminó la última acción, con su código. */
internal data class HarnessOutcome(
    val action: HarnessAction,
    val succeeded: Boolean,
    val code: String,
)

/** En qué va la restauración por archivo. */
internal sealed interface RestorePhase {
    data object Idle : RestorePhase

    /** Espera una respuesta de la persona. */
    data class Asking(val question: RestoreQuestion, val count: Long) : RestorePhase

    /** El diario quedó escrito: falta reiniciar la app. */
    data class ReadyToRestart(val warnings: Int) : RestorePhase
}

/** El estado de la pantalla del arnés. */
internal sealed interface HarnessUiState {
    /** Abriendo la base. */
    data object Loading : HarnessUiState

    /** La base no abre sin riesgo: la app iría al rescate, y el arnés no toca nada. */
    data object Unavailable : HarnessUiState

    data class Ready(
        val report: BackupStatusReport,
        val saved: HarnessState?,
        val policy: PolicyConsentStatus?,
        val working: HarnessAction?,
        val outcome: HarnessOutcome?,
        val restore: RestorePhase,
    ) : HarnessUiState
}

/**
 * El estado del arnés del respaldo y sus acciones.
 *
 * La contraseña nunca se guarda aquí: llega en la llamada a [connectWebDav] y sigue a la capa de
 * datos. Así no puede terminar en el estado guardado de la actividad.
 */
internal class BackupHarnessViewModel(private val port: HarnessPort) : ViewModel() {
    private val working = MutableStateFlow<HarnessAction?>(null)
    private val outcome = MutableStateFlow<HarnessOutcome?>(null)
    private val restore = MutableStateFlow<RestorePhase>(RestorePhase.Idle)
    private val policy = MutableStateFlow<PolicyConsentStatus?>(null)

    val uiState: StateFlow<HarnessUiState> =
        combine(
            port.statusReports(),
            port.savedState().onStart { emit(null) },
            combine(working, outcome, restore, policy, ::Actions),
        ) { report, saved, actions ->
            if (report == null) {
                HarnessUiState.Unavailable
            } else {
                HarnessUiState.Ready(
                    report = report,
                    saved = saved,
                    policy = actions.policy,
                    working = actions.working,
                    outcome = actions.outcome,
                    restore = actions.restore,
                )
            }
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            HarnessUiState.Loading,
        )

    init {
        viewModelScope.launch { policy.value = port.policyStatus() }
    }

    fun acceptPolicy() = run(HarnessAction.POLICY) {
        port.acceptPolicy().also { policy.value = port.policyStatus() }.toOutcome(it)
    }

    fun connectWebDav(url: String, username: String, password: String) = run(HarnessAction.WEBDAV) {
        port.connectWebDav(url.trim(), username.trim(), password).toOutcome(it)
    }

    fun prepareSlots(uris: List<String>) = run(HarnessAction.SLOTS) {
        port.prepareSlots(uris).toOutcome(it)
    }

    fun recordInventedChanges() = run(HarnessAction.CHANGES) {
        port.recordInventedChanges(CHANGES_PER_TAP).toOutcome(it)
    }

    fun backupNow() = run(HarnessAction.BACKUP_NOW) {
        val request = port.backupNow()
        HarnessOutcome(it, request == BackupNowRequest.QUEUED, request.name)
    }

    fun restoreFromFile(uri: String) = run(HarnessAction.RESTORE) {
        restoreOutcome(it, port.restoreFromFile(uri))
    }

    fun answer(answer: RestoreAnswer) = run(HarnessAction.RESTORE) {
        restoreOutcome(it, port.answer(answer))
    }

    fun cancelRestore() = run(HarnessAction.RESTORE) {
        val cancelled = port.cancelRestore()
        restore.value = RestorePhase.Idle
        HarnessOutcome(it, cancelled, if (cancelled) "CANCELLED" else "NOTHING_TO_CANCEL")
    }

    private fun restoreOutcome(action: HarnessAction, stage: HarnessRestore): HarnessOutcome =
        when (stage) {
            is HarnessRestore.ReadyToRestart -> {
                restore.value = RestorePhase.ReadyToRestart(stage.warnings)
                HarnessOutcome(action, true, "READY_TO_RESTART")
            }

            is HarnessRestore.Question -> {
                restore.value = RestorePhase.Asking(stage.question, stage.count)
                HarnessOutcome(action, true, stage.question.name)
            }

            is HarnessRestore.Stopped -> {
                restore.value = RestorePhase.Idle
                HarnessOutcome(action, false, stage.code)
            }
        }

    /**
     * Corre [block] como la acción [action], si no hay otra en curso. Una falla que nadie previó
     * queda como resultado, solo con su clase.
     */
    private fun run(action: HarnessAction, block: suspend (HarnessAction) -> HarnessOutcome) {
        if (!working.compareAndSet(null, action)) return
        viewModelScope.launch {
            try {
                outcome.value =
                    try {
                        block(action)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (failure: Exception) {
                        HarnessOutcome(action, false, failure::class.simpleName ?: "UNKNOWN")
                    }
            } finally {
                working.value = null
            }
        }
    }

    private fun HarnessResult.toOutcome(action: HarnessAction) =
        HarnessOutcome(action, this is HarnessResult.Done, code)

    private data class Actions(
        val working: HarnessAction?,
        val outcome: HarnessOutcome?,
        val restore: RestorePhase,
        val policy: PolicyConsentStatus?,
    )

    companion object {
        /** Cuántos proveedores inventados registra cada toque. */
        const val CHANGES_PER_TAP = 5

        private const val STOP_TIMEOUT_MS = 5_000L
    }
}
