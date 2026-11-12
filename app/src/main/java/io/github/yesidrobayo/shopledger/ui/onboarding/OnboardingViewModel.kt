package io.github.yesidrobayo.shopledger.ui.onboarding

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupCopyList
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationInfo
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import io.github.yesidrobayo.shopledger.domain.usecase.backup.BackupDestinationReader
import io.github.yesidrobayo.shopledger.domain.usecase.backup.RestoreBackupUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.onboarding.CompleteOnboardingResult
import io.github.yesidrobayo.shopledger.domain.usecase.onboarding.OnboardingResume
import io.github.yesidrobayo.shopledger.domain.usecase.settings.ChangePreferenceResult
import io.github.yesidrobayo.shopledger.lock.AuthOutcome
import io.github.yesidrobayo.shopledger.ui.backup.BackupAction
import io.github.yesidrobayo.shopledger.ui.backup.RestoreFlow
import io.github.yesidrobayo.shopledger.ui.backup.RestoreUi
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * El primer uso: idioma, región, bienvenida y protección, en cuatro pasos.
 *
 * - **Se retoma donde quedó.** El paso vive en el estado guardado de la entrada; tras cerrar la app,
 *   se sigue en la bienvenida si la región ya se confirmó.
 * - **Nada espera a la red.** `Ahora no` siempre sigue, y sin red se dice que se puede empezar y
 *   elegir dónde guardar después.
 * - **Un teléfono nuevo puede traer sus datos.** Al volver de conectar un servicio, se buscan sus
 *   copias: si hay, se ofrece restaurar o empezar de cero; una lista que falla nunca cuenta como
 *   vacía. Con un archivo, la misma restauración de la pantalla del respaldo.
 * - **Terminar queda guardado**, también con `Ahora no`: si no, la bienvenida volvería siempre.
 * - **El bloqueo viene marcado** si el teléfono tiene bloqueo de pantalla, y la persona puede
 *   apagarlo. Marcado, `Continuar` pide la verificación del sistema, y solo con ella el bloqueo
 *   queda encendido. Sin bloqueo de pantalla no se ofrece, y nadie queda fuera de sus datos.
 *
 * @param resume dónde retomar según lo guardado.
 * @param complete guarda el fin del primer uso.
 * @param enableLock enciende el bloqueo de la app, tras la verificación.
 * @param destination el destino conectado y sus copias, y si hay red.
 * @param slotsLabel el nombre de la nube del teléfono, si se sabe.
 * @param calendar la zona del negocio, que la región puede cambiar en el paso 2.
 */
class OnboardingViewModel(
    private val resume: suspend () -> OnboardingResume,
    private val complete: suspend () -> CompleteOnboardingResult,
    private val enableLock: suspend () -> ChangePreferenceResult,
    private val destination: BackupDestinationReader,
    private val slotsLabel: suspend () -> String?,
    calendar: Flow<BusinessCalendarResult>,
    restore: RestoreBackupUseCase,
    private val savedState: SavedStateHandle,
    private val computation: CoroutineDispatcher,
) : ViewModel() {
    private val state = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = state.asStateFlow()

    /** Si el destino conectado ya se miró: se buscan sus copias una sola vez. */
    private var destinationSeen: Boolean
        get() = savedState[DESTINATION_SEEN] ?: false
        set(value) {
            savedState[DESTINATION_SEEN] = value
        }

    private val restoring =
        RestoreFlow(
            use = restore,
            scope = viewModelScope,
            computation = computation,
            get = { state.value.restore },
            set = { next -> state.update { it.copy(restore = next) } },
            needsNetwork = { true },
            hasNetwork = destination::hasNetwork,
        )

    init {
        savedState.get<Boolean>(LOCK_CHOSEN)?.let { chosen ->
            state.update { it.copy(lockChosen = chosen) }
        }
        viewModelScope.launch {
            calendar.collect { result ->
                val zone = (result as? BusinessCalendarResult.Ready)?.calendar?.zone
                state.update { it.copy(zone = zone) }
            }
        }
        viewModelScope.launch {
            val label = read { slotsLabel() }
            val saved = savedState.get<String>(STEP)?.let { name ->
                OnboardingStepId.entries.firstOrNull { it.name == name }
            }
            val step =
                saved ?: when (read { resume() } ?: OnboardingResume.LANGUAGE) {
                    OnboardingResume.LANGUAGE -> OnboardingStepId.LANGUAGE

                    OnboardingResume.WELCOME -> OnboardingStepId.WELCOME

                    // Una copia restaurada ya trae el primer uso terminado.
                    OnboardingResume.DONE -> null
                }
            if (step == null) {
                state.update { it.copy(done = true) }
            } else {
                state.update { it.copy(slotsLabel = label) }
                go(step)
                refresh()
            }
        }
    }

    fun onAction(action: OnboardingAction) {
        when (action) {
            OnboardingAction.LanguageDone -> go(OnboardingStepId.REGION)

            OnboardingAction.RegionDone -> go(OnboardingStepId.WELCOME)

            OnboardingAction.Back -> back()

            OnboardingAction.OpenBring -> state.update { it.copy(bringSheet = true) }

            OnboardingAction.CloseBring -> state.update { it.copy(bringSheet = false) }

            OnboardingAction.NotNow -> go(OnboardingStepId.PROTECTION)

            OnboardingAction.Resumed -> refresh()

            OnboardingAction.RetrySearch -> search()

            OnboardingAction.AskStartOver -> state.update { it.copy(startOver = true) }

            OnboardingAction.DismissStartOver -> state.update { it.copy(startOver = false) }

            OnboardingAction.ConfirmStartOver -> {
                state.update { it.copy(startOver = false, found = null) }
                go(OnboardingStepId.PROTECTION)
            }

            OnboardingAction.Finish ->
                if (state.value.lockOffered && state.value.lockChosen) {
                    state.update { it.copy(lockCheck = true, saveFailed = false) }
                } else {
                    finish(lock = false)
                }

            is OnboardingAction.DeviceLock -> state.update { it.copy(lockOffered = action.secure) }

            is OnboardingAction.LockChoice -> {
                savedState[LOCK_CHOSEN] = action.enabled
                state.update { it.copy(lockChosen = action.enabled) }
            }

            is OnboardingAction.LockChecked -> lockChecked(action.outcome)

            is OnboardingAction.Restore -> restoreAction(action.action)

            // Lo resuelve la navegación.
            OnboardingAction.ChooseDestination,
            OnboardingAction.BringWithWebDav,
            OnboardingAction.FindFile,
            OnboardingAction.OpenDataTransparency,
            -> state.update { it.copy(bringSheet = false) }
        }
    }

    private fun restoreAction(action: BackupAction) {
        when (action) {
            is BackupAction.OpenCopy -> copyOf(action.key)?.let { restoring.open(it) }

            is BackupAction.ConfirmCopy -> copyOf(action.key)?.let { restoring.confirm(it) }

            is BackupAction.FilePicked -> restoring.picked(action.uri)

            BackupAction.StartRestore -> restoring.start()

            is BackupAction.Answer -> restoring.answer(action.answer)

            BackupAction.CancelRestore -> restoring.cancel()

            BackupAction.CloseRestore -> restoring.close()

            // El resto es de la pantalla del respaldo y no llega aquí.
            BackupAction.Back,
            BackupAction.BackUpNow,
            BackupAction.ChooseDestination,
            BackupAction.RetryCopies,
            BackupAction.ReviewPolicy,
            BackupAction.MessageShown,
            BackupAction.OpenProvider,
            BackupAction.MarkSeen,
            BackupAction.ShowMissingHelp,
            BackupAction.CloseMissingHelp,
            BackupAction.FindFile,
            BackupAction.OpenMeteredPolicy,
            is BackupAction.PickMeteredPolicy,
            BackupAction.ConfirmWifiOnly,
            BackupAction.CloseMeteredPolicy,
            is BackupAction.ReleaseCopy,
            BackupAction.ConfirmRelease,
            BackupAction.CloseRelease,
            BackupAction.OpenLocalCopies,
            BackupAction.Disconnect,
            BackupAction.ConfirmDisconnect,
            BackupAction.DismissDisconnect,
            BackupAction.Reconnect,
            BackupAction.SignInToProvider,
            BackupAction.ReviewConflict,
            BackupAction.RetryConflict,
            BackupAction.DecideLater,
            is BackupAction.RestoreCloud,
            BackupAction.KeepPhone,
            BackupAction.ConfirmKeep,
            BackupAction.DismissKeep,
            BackupAction.CreateConflictFile,
            BackupAction.RunDrill,
            BackupAction.ToggleHealth,
            BackupAction.CheckFullCopy,
            -> Unit
        }
    }

    private fun copyOf(key: String) =
        (state.value.found as? FoundCopies.Found)?.copies?.firstOrNull { it.key == key }

    private fun go(step: OnboardingStepId) {
        savedState[STEP] = step.name
        state.update {
            val visit = if (step == OnboardingStepId.REGION) it.regionVisit + 1 else it.regionVisit
            it.copy(step = step, bringSheet = false, regionVisit = visit)
        }
    }

    private fun back() {
        val previous =
            when (state.value.step) {
                OnboardingStepId.REGION -> OnboardingStepId.LANGUAGE
                OnboardingStepId.WELCOME -> OnboardingStepId.REGION
                OnboardingStepId.PROTECTION -> OnboardingStepId.WELCOME
                OnboardingStepId.LANGUAGE, null -> return
            }
        go(previous)
    }

    /**
     * Mira la red y el destino. Con un servicio recién conectado en la bienvenida, busca sus
     * copias; con la nube del teléfono no hay lista, y sigue a la protección.
     */
    private fun refresh() {
        viewModelScope.launch {
            val info = read { destination.describe() }
            val offline = !destination.hasNetwork()
            state.update { it.copy(offline = offline, connected = info != null) }
            if (info == null || destinationSeen || state.value.step != OnboardingStepId.WELCOME) {
                return@launch
            }
            destinationSeen = true
            when (info) {
                is BackupDestinationInfo.WebDav -> search()
                is BackupDestinationInfo.Slots -> go(OnboardingStepId.PROTECTION)
            }
        }
    }

    private fun search() {
        state.update { it.copy(found = FoundCopies.Searching) }
        viewModelScope.launch {
            when (val list = read { destination.listCopies() } ?: BackupCopyList.Failed) {
                is BackupCopyList.Listed ->
                    if (list.copies.any { it.restorable }) {
                        state.update { it.copy(found = FoundCopies.Found(list.copies)) }
                    } else {
                        state.update { it.copy(found = null) }
                        go(OnboardingStepId.PROTECTION)
                    }

                BackupCopyList.NoInternet,
                BackupCopyList.Failed,
                -> state.update { it.copy(found = FoundCopies.Failed) }

                // Se desconectó entre medio: no hay nada que ofrecer.
                BackupCopyList.NotConnected -> state.update { it.copy(found = null) }
            }
        }
    }

    private fun lockChecked(outcome: AuthOutcome) {
        state.update { it.copy(lockCheck = false) }
        when (outcome) {
            AuthOutcome.Success -> finish(lock = true)

            // El teléfono perdió su bloqueo de pantalla: ya no se ofrece, y el paso avisa del riesgo.
            // Con otro `Continuar` termina sin bloqueo.
            AuthOutcome.NoDeviceCredential -> state.update { it.copy(lockOffered = false) }

            // Cancelada o fallida, se queda en el paso: se puede volver a intentar o apagarlo.
            AuthOutcome.Canceled, is AuthOutcome.Failed -> Unit
        }
    }

    /**
     * Termina el primer uso. Con [lock], primero enciende el bloqueo: si no se guarda, no termina,
     * para que la persona no crea que su negocio quedó protegido.
     */
    private fun finish(lock: Boolean) {
        state.update { it.copy(saveFailed = false) }
        viewModelScope.launch {
            if (lock) {
                val locked = read { enableLock() } == ChangePreferenceResult.Saved
                if (!locked) {
                    state.update { it.copy(saveFailed = true) }
                    return@launch
                }
            }
            when (read { complete() }) {
                CompleteOnboardingResult.Completed -> state.update { it.copy(done = true) }

                is CompleteOnboardingResult.Failed,
                null,
                -> state.update { it.copy(saveFailed = true) }
            }
        }
    }

    private suspend fun <T> read(block: suspend () -> T): T? = try {
        withContext(computation) { block() }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val STEP = "onboardingStep"
        const val DESTINATION_SEEN = "onboardingDestinationSeen"
        const val LOCK_CHOSEN = "onboardingLockChosen"
    }
}
