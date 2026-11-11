package io.github.yesidrobayo.shopledger.ui.backup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupCopyList
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupCopySummary
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationInfo
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationKind
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupOverview
import io.github.yesidrobayo.shopledger.domain.model.backup.RestoreAnswer
import io.github.yesidrobayo.shopledger.domain.model.backup.RestoreProgress
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import io.github.yesidrobayo.shopledger.domain.usecase.backup.BackupDestinationReader
import io.github.yesidrobayo.shopledger.domain.usecase.backup.RestoreBackupUseCase
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
 * @param recordCheck anota `Ya la vi en {provider}` con la generación en pantalla; devuelve si quedó.
 * @param computation el hilo donde se leen el destino y la lista, fuera del principal.
 * @param restore restaurar una copia de la lista o un archivo elegido; nulo donde no se ofrece.
 */
class BackupViewModel(
    overview: (Flow<Unit>) -> Flow<BackupOverview>,
    private val destination: BackupDestinationReader,
    calendar: Flow<BusinessCalendarResult>,
    private val requestBackup: suspend () -> Unit,
    private val recordCheck: suspend (Long) -> Boolean,
    private val computation: CoroutineDispatcher,
    private val restore: RestoreBackupUseCase? = null,
) : ViewModel() {
    private val state = MutableStateFlow(BackupUiState())
    val uiState: StateFlow<BackupUiState> = state.asStateFlow()

    // Cada vuelta a primer plano recalcula el estado con la hora nueva.
    private val refreshes = MutableStateFlow(0L)
    private var listing: Job? = null
    private var restoring: Job? = null

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

            BackupAction.MarkSeen -> markSeen()

            BackupAction.ShowMissingHelp -> state.update { it.copy(missingHelp = true) }

            BackupAction.CloseMissingHelp -> state.update { it.copy(missingHelp = false) }

            is BackupAction.OpenCopy -> copyOf(action.key)?.let { copy ->
                state.update { it.copy(restore = RestoreUi.CopySheet(copy)) }
            }

            is BackupAction.ConfirmCopy -> copyOf(action.key)?.let { copy ->
                state.update { it.copy(restore = RestoreUi.ConfirmCopy(copy)) }
            }

            is BackupAction.FilePicked ->
                state.update { it.copy(restore = RestoreUi.ConfirmFile(action.uri)) }

            BackupAction.StartRestore -> startRestore()

            is BackupAction.Answer -> answer(action.answer)

            BackupAction.CancelRestore -> cancelRestore()

            BackupAction.CloseRestore -> state.update { it.copy(restore = RestoreUi.Idle) }

            // Lo resuelve la navegación.
            BackupAction.Back,
            BackupAction.ChooseDestination,
            BackupAction.ReviewPolicy,
            BackupAction.OpenProvider,
            BackupAction.FindFile,
            -> Unit
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
            val handoff = read { destination.latestHandoff() }
            state.update { current ->
                val provider = (info as? BackupDestinationInfo.Slots)?.providerLabel
                current.copy(
                    destination = info,
                    copies = copies,
                    handoff = handoff,
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
     * Anota la revisión de la copia en pantalla y la vuelve a leer, para mostrar la fecha nueva.
     * Nunca toca lo que cuenta como respaldado: el estado sigue diciendo entregada.
     */
    private fun markSeen() {
        val generation = state.value.handoff?.generation ?: return
        viewModelScope.launch {
            val saved = read { recordCheck(generation) } == true
            if (!saved) return@launch
            val handoff = read { destination.latestHandoff() }
            state.update { it.copy(handoff = handoff, message = BackupMessage.CHECK_SAVED) }
        }
    }

    private fun copyOf(key: String): BackupCopySummary? =
        (state.value.copies as? BackupCopyList.Listed)?.copies?.firstOrNull {
            it.key == key && it.restorable
        }

    /**
     * Empieza lo confirmado. Con WebDAV, traer la copia pide internet: sin red no empieza, y los
     * datos no cambian.
     */
    private fun startRestore() {
        val use = restore ?: return
        val current = state.value.restore
        val (origin, run) =
            when (current) {
                is RestoreUi.ConfirmCopy -> {
                    val webDav = state.value.destination is BackupDestinationInfo.WebDav
                    if (webDav && !destination.hasNetwork()) {
                        state.update {
                            it.copy(
                                restore = RestoreUi.Stopped(
                                    RestoreNotice.NO_INTERNET,
                                    RestoreOrigin.LIST,
                                ),
                            )
                        }
                        return
                    }
                    RestoreOrigin.LIST to suspend { use.copy(current.copy.key) }
                }

                is RestoreUi.ConfirmFile -> RestoreOrigin.FILE to suspend { use.file(current.uri) }

                RestoreUi.Idle,
                is RestoreUi.CopySheet,
                is RestoreUi.Working,
                is RestoreUi.Asking,
                is RestoreUi.Stopped,
                RestoreUi.Ready,
                -> return
            }
        follow(origin, run)
    }

    private fun answer(answer: RestoreAnswer) {
        val use = restore ?: return
        val asking = state.value.restore as? RestoreUi.Asking ?: return
        follow(asking.origin) { use.answer(answer) }
    }

    /** Corre un tramo de la restauración y deja en pantalla en qué quedó. */
    private fun follow(origin: RestoreOrigin, step: suspend () -> RestoreProgress) {
        state.update { it.copy(restore = RestoreUi.Working(origin)) }
        restoring = viewModelScope.launch {
            val progress = read { step() } ?: RestoreProgress.Failed
            state.update { it.copy(restore = uiOf(progress, origin)) }
        }
    }

    private fun uiOf(progress: RestoreProgress, origin: RestoreOrigin): RestoreUi {
        if (RestoreBackupUseCase.asks(progress)) return RestoreUi.Asking(progress, origin)
        if (progress is RestoreProgress.ReadyToRestart) return RestoreUi.Ready
        val notice = noticeOf(progress, origin) ?: RestoreNotice.OTHER
        val missing = (progress as? RestoreProgress.NotEnoughSpace)?.missingBytes ?: 0L
        return RestoreUi.Stopped(notice, origin, missing)
    }

    /**
     * Cancela: corta lo que corre, lo que abandona la preparación sin tocar los datos, o suelta la
     * pregunta en espera. Lista para reiniciar ya no se cancela.
     */
    private fun cancelRestore() {
        val current = state.value.restore
        val origin =
            when (current) {
                is RestoreUi.Working -> current.origin

                is RestoreUi.Asking -> current.origin

                RestoreUi.Idle,
                is RestoreUi.CopySheet,
                is RestoreUi.ConfirmCopy,
                is RestoreUi.ConfirmFile,
                is RestoreUi.Stopped,
                RestoreUi.Ready,
                -> return
            }
        restoring?.cancel()
        restoring = null
        state.update { it.copy(restore = RestoreUi.Stopped(RestoreNotice.CANCELLED, origin)) }
        if (current is RestoreUi.Asking) {
            viewModelScope.launch { read { restore?.cancel() } }
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
