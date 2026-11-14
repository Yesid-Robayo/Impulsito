package io.github.yesidrobayo.shopledger.ui.localcopies

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.backup.ConnectFolderResult
import io.github.yesidrobayo.shopledger.domain.model.backup.ExportFileName
import io.github.yesidrobayo.shopledger.domain.model.backup.FullCopyExport
import io.github.yesidrobayo.shopledger.domain.model.backup.PhotoBackupFacts
import io.github.yesidrobayo.shopledger.domain.model.backup.PhotoExport
import io.github.yesidrobayo.shopledger.domain.model.backup.PhotoExportFileName
import io.github.yesidrobayo.shopledger.domain.model.backup.SecondDestinationView
import io.github.yesidrobayo.shopledger.domain.usecase.backup.SecondDestinationUseCase
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * `Copias locales`: exportar una copia completa a un archivo que elige la persona.
 *
 * Exportar solo lee la base: mientras tanto se puede seguir vendiendo, y ningún resultado cambia el
 * estado del respaldo. Una exportación a la vez: el botón no atiende toques mientras trabaja.
 *
 * @param export escribe la copia en el documento elegido.
 * @param encrypted si la copia sale cifrada, para su aviso; nulo si no se pudo leer.
 * @param zone la zona del negocio, para el nombre sugerido; nula si no se sabe.
 * @param now la hora del nombre sugerido.
 * @param computation el hilo donde se exporta, fuera del principal.
 * @param second el segundo destino; nulo donde no se ofrece.
 * @param appName el nombre de la app, sin traducir: da la carpeta de la app.
 * @param photoFacts cuántas fotos hay para exportar, entre otros datos de las fotos.
 * @param exportPhotos escribe el ZIP de las fotos en el documento elegido.
 */
class LocalCopiesViewModel(
    private val export: suspend (String) -> FullCopyExport,
    private val encrypted: suspend () -> Boolean?,
    private val zone: suspend () -> BusinessTimeZone?,
    private val now: () -> Instant,
    private val computation: CoroutineDispatcher,
    private val second: SecondDestinationUseCase? = null,
    private val appName: String = "",
    private val photoFacts: suspend () -> PhotoBackupFacts? = { null },
    private val exportPhotos: suspend (String) -> PhotoExport = { PhotoExport.Failed },
) : ViewModel() {
    private val state = MutableStateFlow(LocalCopiesUiState())
    val uiState: StateFlow<LocalCopiesUiState> = state.asStateFlow()

    init {
        viewModelScope.launch {
            val value = safely { encrypted() }
            // Sin saber, se avisa como en claro: es la advertencia que más protege.
            val businessZone = safely { zone() }
            state.update { it.copy(encrypted = value ?: false, zone = businessZone) }
        }
        refreshSecond()
        refreshPhotos()
    }

    fun onAction(action: LocalCopiesAction) {
        when (action) {
            is LocalCopiesAction.FileCreated -> exportTo(action.uri)

            LocalCopiesAction.DismissResult ->
                state.update {
                    if (it.export is ExportUi.Working) it else it.copy(export = ExportUi.Idle)
                }

            LocalCopiesAction.MessageShown -> state.update { it.copy(message = null) }

            is LocalCopiesAction.FolderPicked -> connect(action.treeUri)

            is LocalCopiesAction.SetFollow -> setFollow(action.follow)

            LocalCopiesAction.AskRemove -> state.update { it.copy(confirmRemove = true) }

            LocalCopiesAction.CloseRemove -> state.update { it.copy(confirmRemove = false) }

            LocalCopiesAction.ConfirmRemove -> remove()

            LocalCopiesAction.Resumed -> {
                refreshSecond()
                refreshPhotos()
            }

            is LocalCopiesAction.PhotosFileCreated -> exportPhotosTo(action.uri)

            LocalCopiesAction.DismissPhotosFailure ->
                state.update { it.copy(photos = it.photos.copy(failure = null)) }

            LocalCopiesAction.ObscuredTouch ->
                state.update { it.copy(message = LocalCopiesMessage.OBSCURED_TOUCH) }

            // Lo resuelve la navegación.
            LocalCopiesAction.Back,
            LocalCopiesAction.SaveToFile,
            LocalCopiesAction.ChooseFolder,
            LocalCopiesAction.ExportPhotos,
            -> Unit
        }
    }

    /** Cuántas fotos hay para exportar. Si no se puede leer, cero: no se ofrece exportar. */
    private fun refreshPhotos() {
        viewModelScope.launch {
            val count = safely { photoFacts() }?.exportable ?: 0
            state.update { it.copy(photos = it.photos.copy(exportable = count)) }
        }
    }

    /** El nombre sugerido del ZIP de las fotos, con la fecha del negocio. */
    fun suggestedPhotosName(): String = PhotoExportFileName.of(now(), state.value.zone)

    /** Exporta las fotos al documento [uri]. Una a la vez. */
    private fun exportPhotosTo(uri: String) {
        if (state.value.photos.working) return
        state.update { it.copy(photos = it.photos.copy(working = true, failure = null)) }
        viewModelScope.launch {
            val result = safely { exportPhotos(uri) } ?: PhotoExport.Failed
            state.update {
                val photos = it.photos.copy(working = false)
                when (result) {
                    is PhotoExport.Saved ->
                        it.copy(photos = photos, message = LocalCopiesMessage.PHOTOS_SAVED)

                    // Se borraron entre la lista y el toque: la sección lo dice al releer.
                    PhotoExport.NoPhotos -> it.copy(photos = photos.copy(exportable = 0))

                    PhotoExport.KeyUnavailable ->
                        it.copy(photos = photos.copy(failure = PhotosFailure.KEY_MISSING))

                    PhotoExport.Failed ->
                        it.copy(photos = photos.copy(failure = PhotosFailure.OTHER))
                }
            }
        }
    }

    private fun refreshSecond() {
        val port = second ?: return
        viewModelScope.launch {
            val view = safely { port.view() } ?: SecondDestinationView.Unreadable
            state.update { it.copy(second = view) }
        }
    }

    /**
     * Guarda la carpeta elegida. Una que no es del teléfono se rechaza sin guardar nada, y se dice
     * hasta que se elija otra.
     */
    private fun connect(treeUri: String) {
        val port = second ?: return
        viewModelScope.launch {
            val result = safely { port.connect(treeUri, appName) } ?: ConnectFolderResult.FAILED
            state.update {
                when (result) {
                    ConnectFolderResult.CONNECTED -> it.copy(folderRejected = false)

                    ConnectFolderResult.NOT_LOCAL -> it.copy(folderRejected = true)

                    ConnectFolderResult.FAILED ->
                        it.copy(folderRejected = false, message = LocalCopiesMessage.FOLDER_FAILED)
                }
            }
            refreshSecond()
        }
    }

    private fun setFollow(follow: Boolean) {
        val port = second ?: return
        viewModelScope.launch {
            // Si no se pudo guardar, el interruptor vuelve solo a lo guardado, y se dice.
            if (safely { port.setFollowMainBackup(follow) } == null) {
                state.update { it.copy(message = LocalCopiesMessage.FOLDER_FAILED) }
            }
            refreshSecond()
        }
    }

    private fun remove() {
        val port = second ?: return
        state.update { it.copy(confirmRemove = false) }
        viewModelScope.launch {
            if (safely { port.remove() } == null) {
                state.update { it.copy(message = LocalCopiesMessage.FOLDER_FAILED) }
            }
            refreshSecond()
        }
    }

    /** El nombre que se sugiere al crear el archivo, con la hora del negocio de ahora. */
    fun suggestedName(): String =
        ExportFileName.of(now(), state.value.zone, encrypted = state.value.encrypted == true)

    private fun exportTo(uri: String) {
        if (state.value.export is ExportUi.Working) return
        state.update { it.copy(export = ExportUi.Working) }
        viewModelScope.launch {
            val result = safely { export(uri) } ?: FullCopyExport.Failed
            state.update {
                when (result) {
                    is FullCopyExport.Saved ->
                        if (result.restorable) {
                            it.copy(export = ExportUi.Idle, message = LocalCopiesMessage.SAVED)
                        } else {
                            it.copy(export = ExportUi.SavedNotRestorable)
                        }

                    FullCopyExport.NoSpace ->
                        it.copy(export = ExportUi.Failed(ExportFailure.NO_SPACE))

                    FullCopyExport.Damaged ->
                        it.copy(export = ExportUi.Failed(ExportFailure.DAMAGED))

                    FullCopyExport.Failed -> it.copy(export = ExportUi.Failed(ExportFailure.OTHER))
                }
            }
        }
    }

    /** Lo leído, o nulo si falló: una falla nunca cierra la pantalla. */
    private suspend fun <T> safely(block: suspend () -> T): T? = try {
        withContext(computation) { block() }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        null
    }
}
