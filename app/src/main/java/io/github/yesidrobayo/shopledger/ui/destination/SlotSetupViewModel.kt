package io.github.yesidrobayo.shopledger.ui.destination

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupSlot
import io.github.yesidrobayo.shopledger.domain.model.backup.SlotFileOutcome
import io.github.yesidrobayo.shopledger.domain.model.backup.SlotFileRejection
import io.github.yesidrobayo.shopledger.domain.model.backup.SlotSetupConfirm
import io.github.yesidrobayo.shopledger.domain.model.backup.SlotSetupLook
import io.github.yesidrobayo.shopledger.domain.usecase.backup.PrepareSlotFilesUseCase
import io.github.yesidrobayo.shopledger.ui.ReadOutcome
import io.github.yesidrobayo.shopledger.ui.readOrNull
import io.github.yesidrobayo.shopledger.ui.readOutcome
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * `Preparar archivos en la nube`: crea los tres archivos uno por uno y, con los tres, deja las
 * ranuras como destino.
 *
 * - Al abrir lee lo que hay, sin red: los archivos creados antes siguen, y las ranuras que ya había
 *   y abren salen como listas.
 * - Cada archivo elegido se revisa enseguida; si no sirve, se dice por qué y la app no lo conserva.
 * - Con los tres, confirma sola. Si un corte dejó la confirmación a mitad, al volver la termina,
 *   también sola: repetirla no cambia nada.
 * - Con otro destino guardado, `Continuar` pregunta antes de cambiar: las copias anteriores quedan
 *   donde están, y la app deja de administrarlas.
 *
 * @param setup la preparación de las ranuras.
 * @param computation el hilo donde se lee y se escribe, fuera del principal.
 */
class SlotSetupViewModel(
    private val setup: PrepareSlotFilesUseCase,
    private val computation: CoroutineDispatcher,
) : ViewModel() {
    private val state = MutableStateFlow(SlotSetupUiState())
    val uiState: StateFlow<SlotSetupUiState> = state.asStateFlow()

    init {
        read()
    }

    /**
     * Lee lo que hay, sin red. Si no se puede leer, la pantalla lo dice con `Reintentar`, en lugar
     * de quedarse en el esqueleto.
     */
    private fun read() {
        viewModelScope.launch {
            val read = readOutcome { withContext(computation) { setup.look() } }
            val look = (read as? ReadOutcome.Read)?.value
            if (look == null) {
                state.update { it.copy(readFailed = true) }
                return@launch
            }
            show(look)
        }
    }

    fun onAction(action: SlotSetupAction) {
        when (action) {
            is SlotSetupAction.FilePicked -> picked(action.uri)

            SlotSetupAction.Continue -> continueOn()

            SlotSetupAction.ConfirmChange -> {
                state.update { it.copy(askingChange = false) }
                confirm()
            }

            SlotSetupAction.DismissChange -> state.update { it.copy(askingChange = false) }

            SlotSetupAction.CreateAnotherFile -> state.update { it.copy(notice = null) }

            SlotSetupAction.MessageShown -> state.update { it.copy(message = null) }

            SlotSetupAction.TargetHandled -> state.update { it.copy(target = null) }

            SlotSetupAction.Back -> go(SlotSetupTarget.BACK)

            SlotSetupAction.Retry -> {
                state.update { it.copy(readFailed = false) }
                read()
            }

            SlotSetupAction.Help -> go(SlotSetupTarget.HELP)

            SlotSetupAction.ConnectService -> go(SlotSetupTarget.WEBDAV_CONNECT)

            SlotSetupAction.GoToLocalCopies, SlotSetupAction.ExportPhotos ->
                go(SlotSetupTarget.LOCAL_COPIES)

            SlotSetupAction.ChooseOtherService -> go(SlotSetupTarget.DESTINATION_CHOICE)

            // Lo resuelve la navegación, con el selector del sistema.
            SlotSetupAction.CreateFile -> state.update { it.copy(notice = null) }
        }
    }

    private fun go(target: SlotSetupTarget) = state.update { it.copy(target = target) }

    private fun picked(uri: String?) {
        val slot = state.value.next ?: return
        if (uri == null) {
            state.update { it.copy(message = SlotSetupMessage.CANCELLED) }
            return
        }
        if (state.value.working) return
        state.update { it.copy(working = true, notice = null) }
        viewModelScope.launch {
            val outcome =
                readOrNull { withContext(computation) { setup.addFile(slot, uri) } }
                    ?: SlotFileOutcome.Unavailable
            state.update { it.copy(working = false) }
            when (outcome) {
                is SlotFileOutcome.Accepted -> show(outcome.look)

                is SlotFileOutcome.Rejected ->
                    state.update { it.copy(notice = noticeOf(outcome.reason, outcome.sameAs)) }

                SlotFileOutcome.Unavailable ->
                    state.update { it.copy(message = SlotSetupMessage.SAVE_FAILED) }
            }
        }
    }

    private fun continueOn() {
        val current = state.value
        val look = current.look ?: return
        when {
            current.ready -> go(SlotSetupTarget.BACKUP)
            look.next != null -> Unit
            look.otherDestination -> state.update { it.copy(askingChange = true) }
            else -> confirm()
        }
    }

    /**
     * Muestra [look]. Con los tres archivos y sin otro destino confirma sola: con ranuras ya
     * conectadas que abren, solo termina lo que haya quedado atrás.
     */
    private fun show(look: SlotSetupLook) {
        state.update { it.copy(look = look) }
        if (look.next == null && !look.otherDestination) confirm()
    }

    private fun confirm() {
        if (state.value.working) return
        state.update { it.copy(working = true, confirmFailed = false) }
        viewModelScope.launch {
            val result =
                readOrNull { withContext(computation) { setup.confirm() } }
                    ?: SlotSetupConfirm.Failed
            when (result) {
                is SlotSetupConfirm.Done ->
                    state.update { it.copy(working = false, ready = true) }

                SlotSetupConfirm.Incomplete, SlotSetupConfirm.OtherDestination -> {
                    // Algo cambió mientras tanto: se vuelve a leer lo que hay.
                    val look =
                        (
                            readOutcome {
                                withContext(computation) { setup.look() }
                            } as? ReadOutcome.Read
                            )?.value
                    state.update { it.copy(working = false, look = look ?: it.look) }
                }

                SlotSetupConfirm.Failed ->
                    state.update {
                        it.copy(
                            working = false,
                            confirmFailed = true,
                            message = SlotSetupMessage.SAVE_FAILED,
                        )
                    }
            }
        }
    }

    private fun noticeOf(reason: SlotFileRejection, sameAs: BackupSlot?): SlotSetupNotice =
        when (reason) {
            SlotFileRejection.OUTSIDE_PROVIDERS -> SlotSetupNotice.OutsideProviders

            // Sin permiso que dure, la app tampoco podría escribir con la pantalla cerrada.
            SlotFileRejection.NO_ACCESS, SlotFileRejection.NOT_WRITABLE ->
                SlotSetupNotice.NotWritable

            SlotFileRejection.SIZE_UNKNOWN -> SlotSetupNotice.SizeUnknown

            SlotFileRejection.NOT_EMPTY -> SlotSetupNotice.NotEmpty

            SlotFileRejection.SAME_FILE ->
                SlotSetupNotice.SameFile((sameAs?.ordinal ?: 0) + 1)
        }
}
