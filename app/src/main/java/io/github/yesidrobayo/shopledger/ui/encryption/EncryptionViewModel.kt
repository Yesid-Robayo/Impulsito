package io.github.yesidrobayo.shopledger.ui.encryption

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.backup.ActivationOutcome
import io.github.yesidrobayo.shopledger.domain.model.backup.EncryptionStatus
import io.github.yesidrobayo.shopledger.domain.model.backup.RecoveryCodeDraft
import io.github.yesidrobayo.shopledger.domain.model.backup.RecoveryKitFileName
import io.github.yesidrobayo.shopledger.domain.model.backup.TypedCodeCheck
import io.github.yesidrobayo.shopledger.domain.usecase.backup.EncryptionUseCase
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * `Cifrado de copias`: el estado y la activación en cuatro pasos (aceptar el riesgo, ver el código
 * y guardarlo, reescribirlo, y la prueba de ida y vuelta).
 *
 * - **El código vive solo aquí.** Se crea al pasar el paso 1, y se descarta al salir de la
 *   activación o al terminarla. Nunca entra en el estado guardado de la pantalla.
 * - **Ninguna copia cifrada sale antes del paso 4:** el cifrado se enciende solo si la prueba de ida
 *   y vuelta pasó y el código quedó sellado. Un fallo deja todo como estaba.
 * - Nada de esto usa la red.
 *
 * @param port el cifrado de las copias.
 * @param writeKit escribe el kit de recuperación con el código y su fecha en el documento elegido;
 *   `true` si quedó escrito.
 * @param today la fecha de hoy en la zona del negocio, para el kit y su nombre.
 * @param computation el hilo de la prueba y del kit, fuera del principal.
 */
class EncryptionViewModel(
    private val port: EncryptionUseCase,
    private val writeKit: suspend (
        uri: String,
        groups: List<String>,
        date: BusinessDate,
    ) -> Boolean,
    private val today: suspend () -> BusinessDate,
    private val computation: CoroutineDispatcher,
) : ViewModel() {
    private val state = MutableStateFlow(EncryptionUiState())
    val uiState: StateFlow<EncryptionUiState> = state.asStateFlow()

    /** El código de la activación en curso. Nunca sale de la memoria de este objeto. */
    private var draft: RecoveryCodeDraft? = null
    private var running: Job? = null

    /** La fecha del kit, leída al pasar el paso 1: el nombre y el contenido dicen el mismo día. */
    private var kitDate: BusinessDate? = null

    init {
        refresh()
    }

    fun onAction(action: EncryptionAction) {
        when (action) {
            EncryptionAction.Back, EncryptionAction.ObscuredTouch -> Unit

            EncryptionAction.Start -> start()

            is EncryptionAction.Accept -> updateStep<ActivationStepUi.Risk> {
                it.copy(accepted = action.accepted)
            }

            EncryptionAction.ContinueRisk -> continueRisk()

            EncryptionAction.SaveKit -> updateStep<ActivationStepUi.ShowCode> {
                it.copy(kit = KitState.SAVING)
            }

            is EncryptionAction.KitFileCreated -> saveKit(action.uri)

            EncryptionAction.OnPaper -> updateStep<ActivationStepUi.ShowCode> {
                it.copy(onPaper = true)
            }

            EncryptionAction.ContinueCode -> {
                val step = state.value.step as? ActivationStepUi.ShowCode
                if (step?.canContinue ==
                    true
                ) {
                    state.update { it.copy(step = ActivationStepUi.Retype()) }
                }
            }

            is EncryptionAction.Typed ->
                updateStep<ActivationStepUi.Retype> { ActivationStepUi.Retype(action.groups, null) }

            EncryptionAction.CheckCode -> checkCode()

            EncryptionAction.Retry -> {
                if ((state.value.step as? ActivationStepUi.Testing)?.failure != null) test()
            }

            EncryptionAction.Finish, EncryptionAction.Exit -> leaveActivation()
        }
    }

    /** El nombre sugerido del kit, `recovery-kit-AAAAMMDD.pdf`; `null` antes del paso 2. */
    fun kitFileName(): String? = kitDate?.let(RecoveryKitFileName::of)

    /** Si el selector del kit se canceló: el paso sigue esperando el kit o el papel. */
    fun kitCancelled() = updateStep<ActivationStepUi.ShowCode> {
        if (it.kit == KitState.SAVING) it.copy(kit = KitState.NONE) else it
    }

    private fun start() {
        when (state.value.status) {
            is EncryptionStatusUi.Off -> state.update { it.copy(step = ActivationStepUi.Risk()) }

            // Con el cifrado activo o detenido, esta pantalla no ofrece activar de nuevo: crear otro
            // código es otra acción. Sin estado, no se cambia nada.
            EncryptionStatusUi.On,
            EncryptionStatusUi.KeyUnavailable,
            EncryptionStatusUi.Loading,
            EncryptionStatusUi.Unknown,
            -> Unit
        }
    }

    private fun continueRisk() {
        val step = state.value.step as? ActivationStepUi.Risk ?: return
        if (!step.accepted) return
        val code = port.newCode()
        draft = code
        state.update { it.copy(step = ActivationStepUi.ShowCode(code.groups)) }
        viewModelScope.launch { kitDate = safely { today() } }
    }

    private fun saveKit(uri: String) {
        val groups = draft?.groups ?: return
        val date = kitDate
        if (date == null) {
            updateStep<ActivationStepUi.ShowCode> { it.copy(kit = KitState.FAILED) }
            return
        }
        updateStep<ActivationStepUi.ShowCode> { it.copy(kit = KitState.SAVING) }
        viewModelScope.launch {
            val saved =
                try {
                    withContext(computation) { writeKit(uri, groups, date) }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    false
                }
            updateStep<ActivationStepUi.ShowCode> {
                it.copy(kit = if (saved) KitState.SAVED else KitState.FAILED)
            }
        }
    }

    private fun checkCode() {
        val step = state.value.step as? ActivationStepUi.Retype ?: return
        val code = draft ?: return
        when (val check = port.checkTyped(code, step.typed.joinToString(""))) {
            TypedCodeCheck.Matches -> test()

            // Con casillas vacías, el primer grupo incompleto es el que falta.
            TypedCodeCheck.Incomplete -> {
                val first = step.typed.indexOfFirst { it.length < GROUP_LENGTH }.coerceAtLeast(0)
                state.update { it.copy(step = ActivationStepUi.Retype(step.typed, first)) }
            }

            is TypedCodeCheck.GroupMismatch ->
                state.update {
                    it.copy(step = ActivationStepUi.Retype(step.typed, check.group - 1))
                }
        }
    }

    /** La prueba de ida y vuelta y, si pasa, el sello y el encendido. */
    private fun test() {
        val code = draft ?: return
        if (running?.isActive == true) return
        state.update { it.copy(step = ActivationStepUi.Testing()) }
        running =
            viewModelScope.launch {
                val passed = safely { port.roundTrip(code) } ?: false
                if (!passed) {
                    state.update {
                        it.copy(step = ActivationStepUi.Testing(ActivationFailure.ROUND_TRIP))
                    }
                    return@launch
                }
                when (safely { port.activate(code) } ?: ActivationOutcome.SAVE_FAILED) {
                    ActivationOutcome.ACTIVATED -> {
                        draft = null
                        state.update { it.copy(step = ActivationStepUi.Done) }
                        refresh()
                    }

                    ActivationOutcome.SAVE_FAILED ->
                        state.update {
                            it.copy(step = ActivationStepUi.Testing(ActivationFailure.SAVE))
                        }
                }
            }
    }

    private fun leaveActivation() {
        // La prueba ya en curso termina sola: cortarla a mitad del sello no deshace nada.
        if (state.value.step is ActivationStepUi.Testing && running?.isActive == true) return
        draft = null
        kitDate = null
        state.update { it.copy(step = null) }
        refresh()
    }

    private fun refresh() {
        viewModelScope.launch {
            val status =
                when (val read = safely { port.status() }) {
                    is EncryptionStatus.Off -> EncryptionStatusUi.Off(read.wasOn)
                    is EncryptionStatus.On -> EncryptionStatusUi.On
                    EncryptionStatus.KeyUnavailable -> EncryptionStatusUi.KeyUnavailable
                    EncryptionStatus.Unknown, null -> EncryptionStatusUi.Unknown
                }
            state.update { it.copy(status = status) }
        }
    }

    private inline fun <reified T : ActivationStepUi> updateStep(
        crossinline change: (T) -> ActivationStepUi,
    ) {
        state.update { current ->
            val step = current.step as? T ?: return@update current
            current.copy(step = change(step))
        }
    }

    /** Un fallo de disco o de Keystore no cierra la app: queda como un resultado `null`. */
    private suspend fun <T> safely(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val GROUP_LENGTH = RecoveryCodeDraft.GROUP_LENGTH
    }
}
