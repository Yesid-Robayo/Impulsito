package io.github.yesidrobayo.shopledger.ui.location

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.stock.Location
import io.github.yesidrobayo.shopledger.domain.model.stock.LocationDraft
import io.github.yesidrobayo.shopledger.domain.model.stock.LocationInputError
import io.github.yesidrobayo.shopledger.domain.model.stock.LocationsOverview
import io.github.yesidrobayo.shopledger.domain.rule.TextNormalizer
import io.github.yesidrobayo.shopledger.domain.usecase.stock.InventoryOverview
import io.github.yesidrobayo.shopledger.domain.usecase.stock.LocationLifecycleResult
import io.github.yesidrobayo.shopledger.domain.usecase.stock.SaveLocationResult
import io.github.yesidrobayo.shopledger.domain.usecase.stock.StockTransferDetail
import io.github.yesidrobayo.shopledger.domain.usecase.stock.StockTransfersRead
import io.github.yesidrobayo.shopledger.domain.usecase.stock.VoidStockTransferResult
import io.github.yesidrobayo.shopledger.ui.ReadOutcome
import io.github.yesidrobayo.shopledger.ui.ScreenReads
import io.github.yesidrobayo.shopledger.ui.readOutcome
import io.github.yesidrobayo.shopledger.ui.stock.InventoryItem
import io.github.yesidrobayo.shopledger.ui.stock.inventoryItemOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Lo que la pantalla de ubicaciones le pide al dominio. Es una interfaz para que las pruebas del
 * ViewModel den respuestas fijas sin una base.
 */
interface LocationsOperations {
    fun locations(): Flow<LocationsOverview>

    /** El inventario visto desde [locationId]: cada producto con lo que hay allí. */
    fun stockAt(locationId: Long): Flow<InventoryOverview>

    fun transfers(days: Int): Flow<StockTransfersRead>

    suspend fun readTransfer(transferId: Long): StockTransferDetail?

    suspend fun saveLocation(editingId: Long?, draft: LocationDraft): SaveLocationResult

    suspend fun archive(locationId: Long): LocationLifecycleResult

    suspend fun reactivate(locationId: Long): LocationLifecycleResult

    suspend fun voidTransfer(transferId: Long): VoidStockTransferResult
}

/**
 * La pantalla de ubicaciones y transferencias: las ubicaciones con cuántos productos tienen stock
 * en cada una, el stock de la abierta, las transferencias por día y sus hojas.
 *
 * - **Archivar no esconde stock.** Si a la ubicación le queda algo, ni se pregunta: se dice que
 *   primero hay que transferirlo, con el botón para hacerlo.
 * - **Las transferencias se leen por tramos** de [TRANSFER_WINDOW_DAYS] días, y el siguiente se
 *   pide al llegar al final de la lista.
 * - **Anular avisa sin bloquear** lo que quedaría en negativo en el destino.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocationsViewModel(private val operations: LocationsOperations) : ViewModel() {
    private val state = MutableStateFlow(LocationsUiState())
    val uiState: StateFlow<LocationsUiState> = state.asStateFlow()

    private val listReads = ScreenReads()
    private val transferReads = ScreenReads()
    private val days = MutableStateFlow(TRANSFER_WINDOW_DAYS)
    private val selected = MutableStateFlow<Long?>(null)
    private val query = MutableStateFlow("")

    init {
        viewModelScope.launch {
            listReads.guard { operations.locations() }.collect { overview ->
                state.update { it.copy(content = LocationsContent.Ready(overview)) }
            }
        }
        viewModelScope.launch {
            listReads.failed.collect { failed ->
                if (failed) state.update { it.copy(content = LocationsContent.ReadError) }
            }
        }
        viewModelScope.launch {
            transferReads.guard { days.flatMapLatest { operations.transfers(it) } }
                .collect { read -> state.update { it.copy(transfers = transfersOf(read)) } }
        }
        viewModelScope.launch {
            transferReads.failed.collect { failed ->
                if (failed) state.update { it.copy(transfers = TransfersContent.ReadError) }
            }
        }
        viewModelScope.launch { detail().collect { d -> state.update { it.copy(detail = d) } } }
    }

    fun onAction(action: LocationsAction) {
        when (action) {
            // La navegación las resuelve: aquí no cambia nada.
            LocationsAction.Back,
            is LocationsAction.NewTransfer,
            LocationsAction.ChooseZone,
            -> Unit

            LocationsAction.Retry -> {
                state.update {
                    it.copy(
                        content = LocationsContent.Loading,
                        transfers = TransfersContent.Loading,
                    )
                }
                listReads.retry()
                transferReads.retry()
            }

            is LocationsAction.SelectTab -> state.update { it.copy(tab = action.tab) }

            is LocationsAction.Select -> select(action.locationId)

            is LocationsAction.DetailQuery -> {
                query.value = action.text
                state.update { it.copy(detailQuery = action.text) }
            }

            LocationsAction.NewLocation -> state.update { it.copy(editor = LocationEditor(null)) }

            LocationsAction.RenameLocation -> openedLocation()?.let { location ->
                val editor = LocationEditor(
                    editingId = location.id,
                    isInitial = location.isInitial,
                    name = location.details.name.orEmpty(),
                    kind = location.details.kind,
                )
                state.update { it.copy(editor = editor) }
            }

            is LocationsAction.EditorName -> editEditor {
                it.copy(name = action.text, errors = emptySet(), failure = null)
            }

            is LocationsAction.EditorKind -> editEditor { it.copy(kind = action.kind) }

            LocationsAction.SaveLocation -> saveLocation()

            LocationsAction.DismissEditor -> state.update { it.copy(editor = null) }

            LocationsAction.AskArchive -> askArchive()

            LocationsAction.Reactivate -> openedLocation()?.let { reactivate(it.id) }

            LocationsAction.LoadOlderTransfers -> loadOlder()

            is LocationsAction.OpenTransfer -> openTransfer(action.transferId)

            LocationsAction.DismissTransfer -> state.update { it.copy(transfer = null) }

            LocationsAction.AskVoidTransfer -> state.value.transfer?.let { sheet ->
                state.update { it.copy(dialog = LocationsDialog.VoidTransfer(sheet.transferId)) }
            }

            LocationsAction.ConfirmDialog -> confirmDialog()

            LocationsAction.DismissDialog -> state.update { it.copy(dialog = null) }

            LocationsAction.MessageShown -> state.update { it.copy(message = null) }
        }
    }

    private fun select(locationId: Long?) {
        selected.value = locationId
        query.value = ""
        state.update {
            it.copy(
                selectedId = locationId,
                detail = LocationDetailContent.Loading,
                detailQuery = "",
                archiveBlocked = false,
            )
        }
    }

    /**
     * El stock de la ubicación abierta: los productos con un saldo distinto de cero allí, también
     * negativo, que es justo lo que hay que mirar. La búsqueda los filtra sin mayúsculas ni
     * tildes.
     */
    private fun detail(): Flow<LocationDetailContent> = selected.flatMapLatest { id ->
        if (id == null) {
            flowOf(LocationDetailContent.Loading)
        } else {
            val location = state.map { locationOf(it.content, id) }.distinctUntilChanged()
            combine(location, operations.stockAt(id), query) { found, stock, text ->
                when (found) {
                    LocationLookup.Waiting -> LocationDetailContent.Loading
                    LocationLookup.Missing -> LocationDetailContent.Missing
                    is LocationLookup.Found -> {
                        val stocked = stock.tracked.filter { it.onHand != QuantityMilli.ZERO }
                        LocationDetailContent.Ready(
                            location = found.location,
                            items = itemsOf(stocked, stock, text),
                            hasStock = stocked.isNotEmpty(),
                        )
                    }
                }
            }
        }
    }

    private fun itemsOf(
        stocked: List<Product>,
        stock: InventoryOverview,
        text: String,
    ): List<InventoryItem> {
        val wanted = TextNormalizer.normalize(text.trim())
        return stocked
            .filter { wanted.isEmpty() || wanted in it.details.normalizedName }
            .map { inventoryItemOf(it, stock.units) }
    }

    private fun openedLocation(): Location? =
        (state.value.detail as? LocationDetailContent.Ready)?.location

    private fun editEditor(change: (LocationEditor) -> LocationEditor) {
        state.update { current -> current.copy(editor = current.editor?.let(change)) }
    }

    private fun saveLocation() {
        val editor = state.value.editor ?: return
        if (editor.saving) return
        editEditor { it.copy(saving = true, failure = null) }
        viewModelScope.launch {
            val result = operations.saveLocation(
                editor.editingId,
                LocationDraft(editor.name, editor.kind),
            )
            when (result) {
                is SaveLocationResult.Saved ->
                    state.update {
                        it.copy(editor = null, message = LocationsMessage.LocationSaved)
                    }

                is SaveLocationResult.Invalid -> editEditor {
                    it.copy(saving = false, errors = result.errors.map(::editorError).toSet())
                }

                SaveLocationResult.DuplicateName -> editEditor {
                    it.copy(saving = false, errors = setOf(LocationEditorError.NAME_REPEATED))
                }

                // Ya no existe: no hay nada que renombrar.
                SaveLocationResult.NotFound -> state.update { it.copy(editor = null) }

                is SaveLocationResult.StorageFailed -> editEditor {
                    it.copy(saving = false, failure = SaveFailed(result.failure.isOutOfSpace))
                }
            }
        }
    }

    /** Con stock no se pregunta: se dice que primero hay que transferirlo. */
    private fun askArchive() {
        val detail = state.value.detail as? LocationDetailContent.Ready ?: return
        if (detail.location.isInitial) return
        if (detail.hasStock) {
            state.update { it.copy(archiveBlocked = true) }
        } else {
            state.update { it.copy(dialog = LocationsDialog.Archive(detail.location)) }
        }
    }

    private fun archive(location: Location) {
        viewModelScope.launch {
            when (val result = operations.archive(location.id)) {
                LocationLifecycleResult.Done -> select(null)

                LocationLifecycleResult.HasStock -> state.update { it.copy(archiveBlocked = true) }

                // El menú no ofrece archivar la inicial ni una archivada; si llega, no hay nada.
                LocationLifecycleResult.Initial,
                LocationLifecycleResult.Unchanged,
                LocationLifecycleResult.NameTaken,
                LocationLifecycleResult.NotFound,
                -> Unit

                is LocationLifecycleResult.StorageFailed -> fail(result.failure.isOutOfSpace)
            }
        }
    }

    private fun reactivate(locationId: Long) {
        viewModelScope.launch {
            when (val result = operations.reactivate(locationId)) {
                LocationLifecycleResult.NameTaken ->
                    state.update { it.copy(message = LocationsMessage.NameTaken) }

                LocationLifecycleResult.Done,
                LocationLifecycleResult.Unchanged,
                LocationLifecycleResult.NotFound,
                LocationLifecycleResult.Initial,
                LocationLifecycleResult.HasStock,
                -> Unit

                is LocationLifecycleResult.StorageFailed -> fail(result.failure.isOutOfSpace)
            }
        }
    }

    private fun loadOlder() {
        val current = state.value.transfers as? TransfersContent.Ready ?: return
        if (!current.hasOlder || current.loadingOlder) return
        state.update { it.copy(transfers = current.copy(loadingOlder = true)) }
        days.update { it + TRANSFER_WINDOW_DAYS }
    }

    private fun openTransfer(transferId: Long) {
        state.update { it.copy(transfer = TransferSheet.Loading(transferId)) }
        viewModelScope.launch { refreshTransfer(transferId) }
    }

    /** Lee la hoja otra vez; si ya no existe o no se pudo leer, la cierra. */
    private suspend fun refreshTransfer(transferId: Long) {
        val read = readOutcome { operations.readTransfer(transferId) }
        state.update { current ->
            if (current.transfer?.transferId != transferId) return@update current
            when (read) {
                is ReadOutcome.Read -> current.copy(
                    transfer = read.value?.let { TransferSheet.Ready(it) },
                )

                ReadOutcome.Failed -> current.copy(
                    transfer = null,
                    message = LocationsMessage.Failed(outOfSpace = false),
                )
            }
        }
    }

    private fun confirmDialog() {
        val dialog = state.value.dialog ?: return
        state.update { it.copy(dialog = null) }
        when (dialog) {
            is LocationsDialog.Archive -> archive(dialog.location)
            is LocationsDialog.VoidTransfer -> voidTransfer(dialog.transferId)
        }
    }

    private fun voidTransfer(transferId: Long) {
        editSheet { it.copy(voiding = true, failure = null) }
        viewModelScope.launch {
            when (val result = operations.voidTransfer(transferId)) {
                VoidStockTransferResult.Voided -> state.update {
                    it.copy(transfer = null, message = LocationsMessage.TransferVoided)
                }

                // Otra hoja ya la anuló, o ya no existe: se muestra como quedó.
                VoidStockTransferResult.AlreadyVoided, VoidStockTransferResult.NotFound ->
                    refreshTransfer(transferId)

                is VoidStockTransferResult.StorageFailed -> editSheet {
                    it.copy(voiding = false, failure = SaveFailed(result.failure.isOutOfSpace))
                }

                // El motivo es siempre un error de registro y no hay nota; sin zona no hay fecha.
                VoidStockTransferResult.ReasonNotAllowed,
                VoidStockTransferResult.NoteTooLong,
                VoidStockTransferResult.UnknownTimeZone,
                -> editSheet { it.copy(voiding = false, failure = SaveFailed(false)) }
            }
        }
    }

    private fun editSheet(change: (TransferSheet.Ready) -> TransferSheet.Ready) {
        state.update { current ->
            val sheet = current.transfer as? TransferSheet.Ready ?: return@update current
            current.copy(transfer = change(sheet))
        }
    }

    private fun fail(outOfSpace: Boolean) {
        state.update { it.copy(message = LocationsMessage.Failed(outOfSpace)) }
    }

    private companion object {
        /** Los días de cada tramo de transferencias. */
        const val TRANSFER_WINDOW_DAYS = 30

        fun editorError(error: LocationInputError): LocationEditorError = when (error) {
            LocationInputError.NAME_EMPTY -> LocationEditorError.NAME_EMPTY
            LocationInputError.NAME_TOO_LONG -> LocationEditorError.NAME_TOO_LONG
        }

        fun transfersOf(read: StockTransfersRead): TransfersContent = when (read) {
            StockTransfersRead.UnknownZone -> TransfersContent.UnknownZone
            is StockTransfersRead.Ready -> TransfersContent.Ready(
                days = read.transfers
                    .groupBy { it.transfer.content.businessDate }
                    .map { (date, transfers) -> TransferDay(date, transfers) },
                hasOlder = read.hasOlder,
                loadingOlder = false,
            )
        }

        fun locationOf(content: LocationsContent, id: Long): LocationLookup = when (content) {
            LocationsContent.Loading -> LocationLookup.Waiting
            LocationsContent.ReadError -> LocationLookup.Missing
            is LocationsContent.Ready -> {
                val overview = content.overview
                val found = overview.active.firstOrNull { it.location.id == id }?.location
                    ?: overview.archived.firstOrNull { it.id == id }
                found?.let { LocationLookup.Found(it) } ?: LocationLookup.Missing
            }
        }
    }
}

/** Si la ubicación abierta está entre las leídas. */
private sealed interface LocationLookup {
    data object Waiting : LocationLookup

    data object Missing : LocationLookup

    data class Found(val location: Location) : LocationLookup
}
