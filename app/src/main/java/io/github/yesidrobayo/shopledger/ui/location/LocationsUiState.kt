package io.github.yesidrobayo.shopledger.ui.location

import androidx.compose.runtime.Immutable
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.stock.Location
import io.github.yesidrobayo.shopledger.domain.model.stock.LocationKind
import io.github.yesidrobayo.shopledger.domain.model.stock.LocationsOverview
import io.github.yesidrobayo.shopledger.domain.usecase.stock.StockTransferDetail
import io.github.yesidrobayo.shopledger.domain.usecase.stock.StockTransferSummary
import io.github.yesidrobayo.shopledger.ui.stock.InventoryItem

/** Los dos segmentos de la pantalla. */
enum class LocationsTab {
    LOCATIONS,
    TRANSFERS,
}

/** Lo que muestra el segmento de ubicaciones. */
@Immutable
sealed interface LocationsContent {
    data object Loading : LocationsContent

    data object ReadError : LocationsContent

    data class Ready(val overview: LocationsOverview) : LocationsContent
}

/** Las transferencias de un día de negocio, de la más nueva a la más antigua. */
@Immutable
data class TransferDay(val date: BusinessDate, val transfers: List<StockTransferSummary>)

/** Lo que muestra el segmento de transferencias. */
@Immutable
sealed interface TransfersContent {
    data object Loading : TransfersContent

    data object ReadError : TransfersContent

    /** Este teléfono no conoce la zona del negocio: se pide elegirla. */
    data object UnknownZone : TransfersContent

    /**
     * Las transferencias leídas, por día. [hasOlder] dice si puede haber más antes: se piden al
     * llegar al final, y [loadingOlder] dibuja un esqueleto mientras llegan.
     */
    data class Ready(
        val days: List<TransferDay>,
        val hasOlder: Boolean,
        val loadingOlder: Boolean,
    ) : TransfersContent
}

/**
 * El stock de la ubicación abierta: sus productos con un saldo distinto de cero, por nombre, ya
 * filtrados por la búsqueda. [hasStock] dice si le queda algo, aunque la búsqueda lo esconda.
 */
@Immutable
sealed interface LocationDetailContent {
    data object Loading : LocationDetailContent

    /** La ubicación ya no existe. */
    data object Missing : LocationDetailContent

    data class Ready(
        val location: Location,
        val items: List<InventoryItem>,
        val hasStock: Boolean,
    ) : LocationDetailContent
}

/** Lo que impide guardar la hoja de una ubicación, junto a su campo. */
enum class LocationEditorError {
    NAME_EMPTY,
    NAME_TOO_LONG,
    NAME_REPEATED,
}

/**
 * La hoja de una ubicación: nueva si [editingId] es nulo, o la que se renombra. En la inicial
 * ([isInitial]), un nombre en blanco la deja con el traducido, que la hoja muestra como pista.
 */
@Immutable
data class LocationEditor(
    val editingId: Long?,
    val isInitial: Boolean = false,
    val name: String = "",
    val kind: LocationKind = LocationKind.STORE,
    val errors: Set<LocationEditorError> = emptySet(),
    val failure: SaveFailed? = null,
    val saving: Boolean = false,
)

/** El guardado falló, sin dejar nada a medias. [outOfSpace] dice si falta espacio. */
@Immutable
data class SaveFailed(val outOfSpace: Boolean)

/**
 * La hoja de una transferencia: se lee al abrirla, y se puede anular si está activa.
 * [lotUsed] dice que no se anuló porque un lote con código ya no tiene en el destino lo que llegó.
 */
@Immutable
sealed interface TransferSheet {
    val transferId: Long

    data class Loading(override val transferId: Long) : TransferSheet

    data class Ready(
        val detail: StockTransferDetail,
        val voiding: Boolean = false,
        val failure: SaveFailed? = null,
        val lotUsed: Boolean = false,
    ) : TransferSheet {
        override val transferId: Long get() = detail.transfer.id
    }
}

/** Lo que pide confirmación antes de hacerse. */
@Immutable
sealed interface LocationsDialog {
    data class Archive(val location: Location) : LocationsDialog

    data class VoidTransfer(val transferId: Long) : LocationsDialog
}

/** Un aviso breve, abajo, tras una acción. */
sealed interface LocationsMessage {
    data object LocationSaved : LocationsMessage

    data object TransferVoided : LocationsMessage

    /** Otra ubicación vigente ya usa el nombre de la que se quería reactivar. */
    data object NameTaken : LocationsMessage

    data class Failed(val outOfSpace: Boolean) : LocationsMessage
}

/**
 * El estado completo de la pantalla de ubicaciones y transferencias.
 *
 * [selectedId] es la ubicación abierta: en una ventana angosta reemplaza la lista, y en una ancha
 * va a su derecha. [archiveBlocked] dice que archivarla no se pudo porque le queda stock.
 */
@Immutable
data class LocationsUiState(
    val tab: LocationsTab = LocationsTab.LOCATIONS,
    val content: LocationsContent = LocationsContent.Loading,
    val transfers: TransfersContent = TransfersContent.Loading,
    val selectedId: Long? = null,
    val detail: LocationDetailContent = LocationDetailContent.Loading,
    val detailQuery: String = "",
    val archiveBlocked: Boolean = false,
    val editor: LocationEditor? = null,
    val transfer: TransferSheet? = null,
    val dialog: LocationsDialog? = null,
    val message: LocationsMessage? = null,
) {
    /** Si hay una hoja, una confirmación o una ubicación abierta: atrás la cierra primero. */
    val overlayOpen: Boolean
        get() = editor != null || transfer != null || dialog != null || selectedId != null

    /** Si hay más de una ubicación vigente: solo entonces se puede transferir. */
    val canTransfer: Boolean
        get() = (content as? LocationsContent.Ready)?.overview?.showsLocations == true
}

/** Lo que la persona hace en la pantalla. */
sealed interface LocationsAction {
    data object Back : LocationsAction

    data object Retry : LocationsAction

    data class SelectTab(val tab: LocationsTab) : LocationsAction

    /** Abre una ubicación, o vuelve a la lista con `null`. */
    data class Select(val locationId: Long?) : LocationsAction

    data class DetailQuery(val text: String) : LocationsAction

    data object NewLocation : LocationsAction

    data object RenameLocation : LocationsAction

    data class EditorName(val text: String) : LocationsAction

    data class EditorKind(val kind: LocationKind) : LocationsAction

    data object SaveLocation : LocationsAction

    data object DismissEditor : LocationsAction

    data object AskArchive : LocationsAction

    data object Reactivate : LocationsAction

    /** Abre el formulario de transferencia, desde [fromLocationId] si se sabe. */
    data class NewTransfer(val fromLocationId: Long? = null) : LocationsAction

    /** Pide el tramo siguiente de transferencias, al llegar al final de la lista. */
    data object LoadOlderTransfers : LocationsAction

    data object ChooseZone : LocationsAction

    data class OpenTransfer(val transferId: Long) : LocationsAction

    data object DismissTransfer : LocationsAction

    data object AskVoidTransfer : LocationsAction

    data object ConfirmDialog : LocationsAction

    data object DismissDialog : LocationsAction

    data object MessageShown : LocationsAction
}
