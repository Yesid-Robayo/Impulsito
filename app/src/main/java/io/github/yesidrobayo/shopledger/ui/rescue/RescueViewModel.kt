package io.github.yesidrobayo.shopledger.ui.rescue

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupCopyList
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupCopySummary
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationKind
import io.github.yesidrobayo.shopledger.domain.usecase.backup.BackupDestinationReader
import io.github.yesidrobayo.shopledger.domain.usecase.backup.RestoreBackupUseCase
import io.github.yesidrobayo.shopledger.ui.backup.BackupAction
import io.github.yesidrobayo.shopledger.ui.backup.RestoreFlow
import io.github.yesidrobayo.shopledger.ui.backup.RestoreUi
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
 * La pantalla de rescate: el destino guardado y sus copias, restaurar, y lo que se ofrece mientras
 * tanto. **Nunca abre la base**: todo sale de `noBackupFilesDir`.
 *
 * Al abrir lee el destino de `link.json` y si hay una base dañada apartada. Las copias se leen solo
 * cuando la persona toca `Restaurar`: con WebDAV eso usa la red, y esta pantalla no espera a nada
 * para mostrarse.
 *
 * @param cause por qué la base no abrió.
 * @param destination el destino de `link.json` y sus copias.
 * @param destinationKind el tipo de ese destino, o nulo sin `link.json`.
 * @param hasDamagedOriginal si una restauración apartó una base dañada.
 * @param restore restaurar una copia o un archivo, sin la base.
 * @param computation el hilo de las lecturas, fuera del principal.
 */
class RescueViewModel(
    cause: RescueReason,
    private val destination: BackupDestinationReader,
    private val destinationKind: suspend () -> BackupDestinationKind?,
    private val hasDamagedOriginal: suspend () -> Boolean,
    restore: RestoreBackupUseCase,
    private val computation: CoroutineDispatcher,
) : ViewModel() {
    private val state = MutableStateFlow(RescueUiState(cause))
    val uiState: StateFlow<RescueUiState> = state.asStateFlow()

    private var listing: Job? = null
    private val restoring =
        RestoreFlow(
            use = restore,
            scope = viewModelScope,
            computation = computation,
            get = { state.value.restore },
            set = { next -> state.update { it.copy(restore = next) } },
            needsNetwork = { state.value.destinationKind == BackupDestinationKind.WEBDAV },
            hasNetwork = destination::hasNetwork,
        )

    init {
        viewModelScope.launch {
            val kind = read { destinationKind() }
            val damaged = read { hasDamagedOriginal() } == true
            state.update { it.copy(destinationKind = kind, damagedAvailable = damaged) }
        }
    }

    fun onAction(action: RescueAction) {
        when (action) {
            RescueAction.ShowCopies -> showCopies()

            RescueAction.CloseCopies -> state.update { it.copy(copiesOpen = false) }

            RescueAction.RetryCopies -> list()

            is RescueAction.Restore -> onRestore(action.action)

            RescueAction.ShowSupport -> state.update { it.copy(supportOpen = true) }

            RescueAction.CloseSupport -> state.update { it.copy(supportOpen = false) }

            RescueAction.ShowLanguage -> state.update { it.copy(languageOpen = true) }

            RescueAction.CloseLanguage -> state.update { it.copy(languageOpen = false) }

            RescueAction.ShareDamaged -> state.update { it.copy(sharing = true) }

            RescueAction.CloseShareDamaged, RescueAction.ConfirmShareDamaged ->
                state.update { it.copy(sharing = false) }

            // Lo resuelve la pantalla: reiniciar la app, o el portapapeles.
            RescueAction.Retry, RescueAction.CopySupport -> Unit
        }
    }

    private fun onRestore(action: BackupAction) {
        when (action) {
            is BackupAction.OpenCopy ->
                copyOf(action.key)?.let { copy ->
                    state.update { it.copy(copiesOpen = false) }
                    restoring.open(copy)
                }

            is BackupAction.ConfirmCopy -> copyOf(action.key)?.let(restoring::confirm)

            is BackupAction.FilePicked -> restoring.picked(action.uri)

            BackupAction.StartRestore -> restoring.start()

            is BackupAction.Answer -> restoring.answer(action.answer)

            BackupAction.CancelRestore -> restoring.cancel()

            BackupAction.CloseRestore -> restoring.close()

            BackupAction.RetryCopies -> list()

            // El selector lo abre la pantalla. Lo demás no existe en el rescate.
            BackupAction.FindFile,
            BackupAction.Back,
            BackupAction.BackUpNow,
            BackupAction.MessageShown,
            BackupAction.MarkSeen,
            BackupAction.ShowMissingHelp,
            BackupAction.CloseMissingHelp,
            BackupAction.ChooseDestination,
            BackupAction.ReviewPolicy,
            BackupAction.OpenProvider,
            -> Unit
        }
    }

    private fun showCopies() {
        if (state.value.destinationKind == null) return
        state.update { it.copy(copiesOpen = true) }
        list()
    }

    private fun list() {
        listing?.cancel()
        state.update { it.copy(copies = null) }
        listing = viewModelScope.launch {
            val copies = read { destination.listCopies() } ?: BackupCopyList.Failed
            state.update { it.copy(copies = copies) }
        }
    }

    private fun copyOf(key: String): BackupCopySummary? =
        (state.value.copies as? BackupCopyList.Listed)?.copies?.firstOrNull { it.key == key }

    /** Lo leído, o nulo si falló: una lectura que falla nunca cierra el rescate. */
    private suspend fun <T> read(block: suspend () -> T): T? = try {
        withContext(computation) { block() }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
}
