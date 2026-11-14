package io.github.yesidrobayo.shopledger.ui.media

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yesidrobayo.shopledger.domain.model.catalog.ImageImport
import io.github.yesidrobayo.shopledger.domain.model.catalog.StoredImage
import io.github.yesidrobayo.shopledger.domain.model.document.ReceiptPhoto
import io.github.yesidrobayo.shopledger.domain.usecase.document.AttachReceiptResult
import io.github.yesidrobayo.shopledger.domain.usecase.document.RemoveReceiptResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Las fotos del comprobante de un gasto o una compra. [photos] es `null` mientras se leen.
 * [viewing] es la foto abierta para leerla, y [confirmRemove] pide confirmar antes de quitarla.
 */
@Immutable
data class ReceiptPhotosUiState(
    val photos: List<ReceiptPhoto>? = null,
    val adding: Boolean = false,
    val viewing: ReceiptPhoto? = null,
    val confirmRemove: Boolean = false,
    val removing: Boolean = false,
    val error: ReceiptPhotoError? = null,
)

/** Lo que salió mal, dicho una vez bajo las fotos. Nada se guardó a medias. */
enum class ReceiptPhotoError {
    /** No se pudo leer como imagen: la persona puede elegir otra. */
    UNREADABLE,

    /** El teléfono no tiene espacio. */
    OUT_OF_SPACE,

    /** No se pudo guardar ni quitar. Lo que había sigue igual. */
    SAVE_FAILED,
}

/** Lo que la persona hace con las fotos del comprobante. */
sealed interface ReceiptPhotosAction {
    /** Abre el selector del sistema: lo resuelve la ruta. */
    data object Choose : ReceiptPhotosAction

    /** El selector devolvió [source], un identificador opaco que nunca se guarda. */
    data class Picked(val source: String) : ReceiptPhotosAction

    data class Open(val photo: ReceiptPhoto) : ReceiptPhotosAction

    data object Close : ReceiptPhotosAction

    data object AskRemove : ReceiptPhotosAction

    data object CancelRemove : ReceiptPhotosAction

    data object ConfirmRemove : ReceiptPhotosAction
}

/**
 * Las fotos del comprobante de un documento: leerlas, agregar una con el selector del sistema y
 * quitarla, solo porque la persona lo confirma.
 *
 * - La imagen elegida es entrada no confiable: [importPhoto] la lee con tope y la recodifica sin
 *   EXIF antes de que [attach] la una al documento.
 * - Una falla nunca deja nada a medias, y lo dice una vez.
 *
 * @param photos las fotos del documento, con su imagen.
 * @param importPhoto trae la imagen elegida al tamaño de un comprobante.
 * @param attach une la imagen ya recodificada al documento, en una sola transacción.
 * @param remove quita una foto del documento, en una sola transacción.
 */
class ReceiptPhotosViewModel(
    photos: Flow<List<ReceiptPhoto>>,
    private val importPhoto: suspend (String) -> ImageImport,
    private val attach: suspend (StoredImage) -> AttachReceiptResult,
    private val remove: suspend (Long) -> RemoveReceiptResult,
) : ViewModel() {
    private val state = MutableStateFlow(ReceiptPhotosUiState())
    val uiState: StateFlow<ReceiptPhotosUiState> = state.asStateFlow()

    init {
        viewModelScope.launch {
            // Si no se pueden leer, no se muestra ninguna: el documento sigue igual.
            photos.catch { emit(emptyList()) }.collect { list ->
                state.update { current ->
                    current.copy(
                        photos = list,
                        viewing = current.viewing?.let { open ->
                            list.firstOrNull { it.attachmentId == open.attachmentId }
                        },
                    )
                }
            }
        }
    }

    fun onAction(action: ReceiptPhotosAction) {
        when (action) {
            is ReceiptPhotosAction.Picked -> add(action.source)

            is ReceiptPhotosAction.Open -> state.update {
                it.copy(viewing = action.photo, confirmRemove = false, error = null)
            }

            ReceiptPhotosAction.Close -> state.update {
                it.copy(viewing = null, confirmRemove = false)
            }

            ReceiptPhotosAction.AskRemove -> state.update { it.copy(confirmRemove = true) }

            ReceiptPhotosAction.CancelRemove -> state.update { it.copy(confirmRemove = false) }

            ReceiptPhotosAction.ConfirmRemove -> confirmRemove()

            ReceiptPhotosAction.Choose -> state.update { it.copy(error = null) }
        }
    }

    private fun add(source: String) {
        if (state.value.adding) return
        state.update { it.copy(adding = true, error = null) }
        viewModelScope.launch {
            val error = when (val imported = importPhoto(source)) {
                ImageImport.Unreadable -> ReceiptPhotoError.UNREADABLE

                ImageImport.OutOfSpace -> ReceiptPhotoError.OUT_OF_SPACE

                is ImageImport.Imported -> when (val result = attach(imported.image)) {
                    is AttachReceiptResult.Attached -> null

                    AttachReceiptResult.DocumentUnavailable -> ReceiptPhotoError.SAVE_FAILED

                    is AttachReceiptResult.Failed -> if (result.failure.isOutOfSpace) {
                        ReceiptPhotoError.OUT_OF_SPACE
                    } else {
                        ReceiptPhotoError.SAVE_FAILED
                    }
                }
            }
            state.update { it.copy(adding = false, error = error) }
        }
    }

    private fun confirmRemove() {
        val photo = state.value.viewing ?: return
        if (state.value.removing) return
        state.update { it.copy(removing = true, confirmRemove = false) }
        viewModelScope.launch {
            when (remove(photo.attachmentId)) {
                RemoveReceiptResult.Removed, RemoveReceiptResult.NotFound -> state.update {
                    it.copy(removing = false, viewing = null)
                }

                is RemoveReceiptResult.Failed -> state.update {
                    it.copy(removing = false, error = ReceiptPhotoError.SAVE_FAILED)
                }
            }
        }
    }
}
