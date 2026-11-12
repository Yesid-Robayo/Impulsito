package io.github.yesidrobayo.shopledger.ui.encryption

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.backup.ActivationOutcome
import io.github.yesidrobayo.shopledger.domain.model.backup.CodeCheckOutcome
import io.github.yesidrobayo.shopledger.domain.model.backup.DeactivationOutcome
import io.github.yesidrobayo.shopledger.domain.model.backup.EncryptionStatus
import io.github.yesidrobayo.shopledger.domain.model.backup.RecoveryCodeDraft
import io.github.yesidrobayo.shopledger.domain.model.backup.RecoveryCodeView
import io.github.yesidrobayo.shopledger.domain.model.backup.RecoveryKitFileName
import io.github.yesidrobayo.shopledger.domain.model.backup.ResealOutcome
import io.github.yesidrobayo.shopledger.domain.model.backup.TypedCodeCheck
import io.github.yesidrobayo.shopledger.domain.rule.backup.RecoveryCodeCheckRule
import io.github.yesidrobayo.shopledger.domain.usecase.backup.EncryptionUseCase
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Instant
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
 * @param now el instante actual, para el calendario de la prueba periódica del código.
 * @param dateOf la fecha del negocio de un instante, para mostrar las fechas de la prueba.
 * @param snooze hasta cuándo la persona pidió `Más tarde`, solo en memoria del proceso.
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
    private val now: () -> Instant = { Clock.System.now() },
    private val dateOf: suspend (Instant) -> BusinessDate? = { null },
    private val snooze: RecoveryCheckSnooze = RecoveryCheckSnooze.Process,
) : ViewModel() {
    private val state = MutableStateFlow(EncryptionUiState())
    val uiState: StateFlow<EncryptionUiState> = state.asStateFlow()

    /** El código de la activación en curso. Nunca sale de la memoria de este objeto. */
    private var draft: RecoveryCodeDraft? = null
    private var running: Job? = null
    private var viewing: Job? = null
    private var checking: Job? = null

    /** Si esta pantalla ya ofreció la prueba vencida: se ofrece una vez al abrirla. */
    private var checkOffered = false

    /** Si la activación en curso es un cambio de código. */
    private var changing = false

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

            EncryptionAction.AskChange ->
                if (state.value.status == EncryptionStatusUi.On) {
                    state.update { it.copy(confirmChange = true) }
                }

            EncryptionAction.CloseChange -> state.update { it.copy(confirmChange = false) }

            EncryptionAction.ConfirmChange -> {
                state.update { it.copy(confirmChange = false) }
                if (state.value.status == EncryptionStatusUi.On) newCodeFor(change = true)
            }

            EncryptionAction.CreateNewCode ->
                if (state.value.status ==
                    EncryptionStatusUi.KeyUnavailable
                ) {
                    newCodeFor(change = true)
                }

            EncryptionAction.TypeExistingCode ->
                if (state.value.status == EncryptionStatusUi.KeyUnavailable &&
                    state.value.canTypeCode
                ) {
                    state.update { it.copy(step = ActivationStepUi.TypeExisting()) }
                }

            is EncryptionAction.TypedExisting ->
                updateStep<ActivationStepUi.TypeExisting> {
                    if (it.working) it else ActivationStepUi.TypeExisting(action.groups)
                }

            EncryptionAction.Reseal -> reseal()

            // La pantalla pide la verificación del teléfono antes de seguir.
            EncryptionAction.ViewCode -> Unit

            EncryptionAction.ViewCodeWithoutScreenLock ->
                state.update { it.copy(confirmViewWithoutScreenLock = true) }

            EncryptionAction.DismissViewWithoutScreenLock ->
                state.update { it.copy(confirmViewWithoutScreenLock = false) }

            EncryptionAction.ViewCodeAuthorized -> viewCode()

            EncryptionAction.CloseCode -> {
                viewing?.cancel()
                state.update { it.copy(codeView = null) }
            }

            EncryptionAction.TestCode -> openCheck()

            is EncryptionAction.TypedCheck ->
                state.update { current ->
                    val check = current.codeCheck
                    if (check == null || check.working) {
                        current
                    } else {
                        current.copy(codeCheck = check.copy(typed = action.groups, problem = null))
                    }
                }

            EncryptionAction.SubmitCheck -> submitCheck()

            // La pantalla pide la verificación del teléfono y abre `Ver mi código`.
            EncryptionAction.LostCode -> closeCheck()

            EncryptionAction.LaterCheck -> {
                snooze.until = RecoveryCodeCheckRule.snoozedUntil(now())
                closeCheck()
            }

            EncryptionAction.CheckPassedShown -> state.update { it.copy(codeCheckPassed = false) }

            EncryptionAction.AskDeactivate ->
                if (canDeactivate()) state.update { it.copy(confirmDeactivate = true) }

            EncryptionAction.CloseDeactivate -> state.update { it.copy(confirmDeactivate = false) }

            EncryptionAction.ConfirmDeactivate -> deactivate()

            EncryptionAction.MessageShown -> state.update { it.copy(message = null) }
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
        newCodeFor(change = false)
    }

    /**
     * Crea el código y va al paso 2. Cambiar el código repite los pasos 2 a 4: el riesgo ya se
     * aceptó al activar, y no necesita el código anterior.
     */
    private fun newCodeFor(change: Boolean) {
        val code = port.newCode()
        draft = code
        changing = change
        state.update { it.copy(step = ActivationStepUi.ShowCode(code.groups)) }
        viewModelScope.launch { kitDate = safely { today() } }
    }

    /** Apagar se ofrece con el cifrado activo o detenido, nunca a mitad de una activación. */
    private fun canDeactivate(): Boolean = state.value.step == null &&
        (
            state.value.status == EncryptionStatusUi.On ||
                state.value.status == EncryptionStatusUi.KeyUnavailable
            )

    /** Apaga el cifrado. Un fallo no cambia nada y lo dice. */
    private fun deactivate() {
        state.update { it.copy(confirmDeactivate = false) }
        if (!canDeactivate()) return
        viewModelScope.launch {
            val outcome = safely { port.deactivate() } ?: DeactivationOutcome.FAILED
            val message =
                when (outcome) {
                    DeactivationOutcome.DEACTIVATED -> EncryptionMessage.DEACTIVATED
                    DeactivationOutcome.FAILED -> EncryptionMessage.DEACTIVATE_FAILED
                }
            state.update { it.copy(message = message) }
            refresh()
        }
    }

    /** Guarda otra vez el código escrito. Un error no borra lo escrito ni tiene límite de intentos. */
    private fun reseal() {
        val step = state.value.step as? ActivationStepUi.TypeExisting ?: return
        if (step.working) return
        state.update { it.copy(step = ActivationStepUi.TypeExisting(step.typed, working = true)) }
        viewModelScope.launch {
            val outcome =
                safely { port.reseal(step.typed.joinToString("")) } ?: ResealOutcome.SAVE_FAILED
            val problem =
                when (outcome) {
                    ResealOutcome.SEALED -> null
                    ResealOutcome.TYPO, ResealOutcome.OTHER_CODE -> ResealProblem.MISMATCH
                    ResealOutcome.SAVE_FAILED -> ResealProblem.SAVE
                }
            if (problem == null) {
                state.update { it.copy(step = null) }
                refresh()
            } else {
                state.update { it.copy(step = ActivationStepUi.TypeExisting(step.typed, problem)) }
            }
        }
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
                        state.update { it.copy(step = ActivationStepUi.Done(changing)) }
                        refresh()
                    }

                    ActivationOutcome.SAVE_FAILED ->
                        state.update {
                            it.copy(step = ActivationStepUi.Testing(ActivationFailure.SAVE))
                        }
                }
            }
    }

    /**
     * Abre el código sellado para mostrarlo. Solo lee: no escribe nada ni cambia el estado del
     * cifrado. El registro de la auditoría llega cuando exista.
     */
    private fun viewCode() {
        if (state.value.step != null || viewing?.isActive == true) return
        state.update {
            it.copy(confirmViewWithoutScreenLock = false, codeView = CodeViewUi.Opening)
        }
        viewing =
            viewModelScope.launch {
                val view =
                    when (val read = safely { port.viewCode() }) {
                        is RecoveryCodeView.Shown -> CodeViewUi.Shown(read.code.groups)
                        RecoveryCodeView.Unavailable, null -> CodeViewUi.Unavailable
                    }
                state.update { if (it.codeView == null) it else it.copy(codeView = view) }
            }
    }

    /** Abre la hoja de la prueba, solo con el cifrado activo y sin otra cosa a la vista. */
    private fun openCheck() {
        state.update { current ->
            val free = current.step == null && current.codeView == null && current.codeCheck == null
            if (current.status == EncryptionStatusUi.On && free) {
                current.copy(codeCheck = CodeCheckUi())
            } else {
                current
            }
        }
    }

    private fun closeCheck() {
        checking?.cancel()
        state.update { it.copy(codeCheck = null) }
    }

    /**
     * Compara lo escrito con el código guardado. Si coincide, la capa de datos anota la fecha; si
     * no, no se escribe nada ni se cuenta el intento.
     */
    private fun submitCheck() {
        val check = state.value.codeCheck ?: return
        if (check.working) return
        state.update { it.copy(codeCheck = check.copy(working = true, problem = null)) }
        checking =
            viewModelScope.launch {
                val outcome =
                    safely { port.checkCode(check.typed.joinToString("")) }
                        ?: CodeCheckOutcome.UNAVAILABLE
                when (outcome) {
                    CodeCheckOutcome.CORRECT -> {
                        state.update { it.copy(codeCheck = null, codeCheckPassed = true) }
                        refresh()
                    }

                    CodeCheckOutcome.MISMATCH -> failCheck(CodeCheckProblem.MISMATCH)

                    CodeCheckOutcome.UNAVAILABLE -> failCheck(CodeCheckProblem.UNAVAILABLE)
                }
            }
    }

    private fun failCheck(problem: CodeCheckProblem) {
        state.update { current ->
            val check = current.codeCheck ?: return@update current
            current.copy(codeCheck = check.copy(working = false, problem = problem))
        }
    }

    /**
     * Las fechas de la prueba y, si ya toca y nadie pidió `Más tarde` hoy, la hoja de la prueba,
     * una vez por pantalla. Nunca bloquea nada: se cierra con `Más tarde`.
     */
    private suspend fun updateSchedule(on: EncryptionStatus.On?) {
        if (on == null) {
            state.update { it.copy(checkDates = null) }
            return
        }
        val enabledAt = on.enabledAtUtcMillis?.let(Instant::fromEpochMilliseconds)
        val lastCheck = on.lastCodeCheckAtUtcMillis?.let(Instant::fromEpochMilliseconds)
        val next = enabledAt?.let { RecoveryCodeCheckRule.nextDueAt(it, lastCheck) }
        val dates =
            CheckDatesUi(
                lastCheck = lastCheck?.let { safely { dateOf(it) } },
                nextCheck = next?.let { safely { dateOf(it) } },
            )
        state.update { it.copy(checkDates = dates) }
        // Sin fecha de activación, un dato raro, la prueba se pide: nunca se esconde.
        val due =
            enabledAt == null ||
                RecoveryCodeCheckRule.isDue(
                    encryptionEnabled = true,
                    enabledAt = enabledAt,
                    lastCorrectCheckAt = lastCheck,
                    now = now(),
                    snoozedUntil = snooze.until,
                )
        if (due && !checkOffered) {
            checkOffered = true
            openCheck()
        }
    }

    private fun leaveActivation() {
        // La prueba ya en curso termina sola: cortarla a mitad del sello no deshace nada.
        if (state.value.step is ActivationStepUi.Testing && running?.isActive == true) return
        draft = null
        kitDate = null
        changing = false
        state.update { it.copy(step = null) }
        refresh()
    }

    private fun refresh() {
        viewModelScope.launch {
            val read = safely { port.status() }
            val status =
                when (read) {
                    is EncryptionStatus.Off -> EncryptionStatusUi.Off(read.wasOn)

                    is EncryptionStatus.On -> EncryptionStatusUi.On

                    EncryptionStatus.KeyUnavailable, EncryptionStatus.NoCodeHere ->
                        EncryptionStatusUi.KeyUnavailable

                    EncryptionStatus.Unknown, null -> EncryptionStatusUi.Unknown
                }
            state.update {
                it.copy(status = status, canTypeCode = read != EncryptionStatus.NoCodeHere)
            }
            updateSchedule(read as? EncryptionStatus.On)
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
