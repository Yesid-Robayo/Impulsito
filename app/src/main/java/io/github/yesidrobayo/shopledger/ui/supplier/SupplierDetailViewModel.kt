package io.github.yesidrobayo.shopledger.ui.supplier

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yesidrobayo.shopledger.domain.model.catalog.SupplierOverview
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SupplierLifecycleResult
import io.github.yesidrobayo.shopledger.ui.payable.PayableDue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Lo que muestra el detalle de un proveedor. */
@Immutable
sealed interface SupplierDetailContent {
    data object Loading : SupplierDetailContent

    /** El proveedor no existe, por ejemplo porque se acaba de eliminar. */
    data object Missing : SupplierDetailContent

    data class Ready(val overview: SupplierOverview) : SupplierDetailContent

    data object ReadError : SupplierDetailContent
}

/**
 * El estado del detalle: lo que se muestra, la confirmación pendiente y el aviso de la última
 * acción. [closed] pide volver a la lista, tras eliminarlo.
 */
@Immutable
data class SupplierDetailUiState(
    val content: SupplierDetailContent = SupplierDetailContent.Loading,
    val dialog: SupplierDialog? = null,
    val message: SupplierMessage? = null,
    val closed: Boolean = false,
    val payableDue: PayableDue? = null,
)

/** Lo que la persona hace en el detalle, fuera de la hoja. */
sealed interface SupplierDetailAction {
    data object Back : SupplierDetailAction

    data object Edit : SupplierDetailAction

    data object AskArchive : SupplierDetailAction

    data object AskDelete : SupplierDetailAction

    data object Reactivate : SupplierDetailAction

    data object ConfirmDialog : SupplierDetailAction

    data object DismissDialog : SupplierDetailAction

    /** Abre el marcador del sistema con [phone]: lo resuelve la pantalla. */
    data class Dial(val phone: String) : SupplierDetailAction

    /** Abre una compra: lo resuelve la navegación. */
    data class OpenPurchase(val purchaseId: Long) : SupplierDetailAction

    /** Abre un producto: lo resuelve la navegación. */
    data class OpenProduct(val productId: Long) : SupplierDetailAction

    /** Abre una compra nueva con este proveedor: lo resuelve la navegación. */
    data object NewPurchase : SupplierDetailAction

    /** Abre lo que se le debe en cuentas por pagar: lo resuelve la navegación. */
    data object OpenPayables : SupplierDetailAction

    /** Abre una devolución a este proveedor: lo resuelve la navegación. */
    data object ReturnToSupplier : SupplierDetailAction

    data object Retry : SupplierDetailAction

    data object MessageShown : SupplierDetailAction
}

/**
 * El detalle de un proveedor: sus datos, lo comprado, sus compras y sus productos habituales.
 *
 * Con historia, el menú solo ofrece archivar; sin ella, eliminar. Cada una pregunta antes. Un
 * archivado se puede editar, para cambiar su nombre o vaciar sus datos de contacto, y reactivar.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SupplierDetailViewModel(
    private val supplierId: Long,
    private val operations: SupplierOperations,
) : ViewModel() {
    private val attempts = MutableStateFlow(0)
    private val extra = MutableStateFlow(Extra())

    /** La hoja de editar el proveedor, abierta o no. */
    val editor =
        SupplierEditorModel(
            scope = viewModelScope,
            save = operations::save,
            onSaved = { show(SupplierMessage.Saved) },
        )

    private val content = attempts.flatMapLatest {
        operations.overview(supplierId)
            .map { overview ->
                if (overview == null) {
                    SupplierDetailContent.Missing
                } else {
                    SupplierDetailContent.Ready(overview)
                }
            }
            .onStart { emit(SupplierDetailContent.Loading) }
            .catch { emit(SupplierDetailContent.ReadError) }
            .onEach { shown -> latest = (shown as? SupplierDetailContent.Ready)?.overview }
    }

    /** El vencimiento de lo que se le debe; si no se puede leer, la ficha sigue sin él. */
    private val due = operations.payableDue(supplierId).onStart { emit(null) }.catch { emit(null) }

    /** Lo último que se mostró, para que editar o archivar usen el nombre que se ve. */
    private var latest: SupplierOverview? = null

    val uiState: StateFlow<SupplierDetailUiState> =
        combine(content, extra, due) { shown, more, payable ->
            SupplierDetailUiState(shown, more.dialog, more.message, more.closed, payable)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = SupplierDetailUiState(),
        )

    fun onAction(action: SupplierDetailAction) {
        when (action) {
            SupplierDetailAction.Edit -> overview()?.let { editor.openEdit(it.supplier) }

            SupplierDetailAction.AskArchive ->
                overview()?.let { overview ->
                    val name = overview.supplier.details.name
                    extra.update { it.copy(dialog = SupplierDialog.Archive(supplierId, name)) }
                }

            SupplierDetailAction.AskDelete ->
                overview()?.let { overview ->
                    val name = overview.supplier.details.name
                    extra.update { it.copy(dialog = SupplierDialog.Delete(supplierId, name)) }
                }

            SupplierDetailAction.Reactivate ->
                lifecycle(SupplierMessage.Reactivated) { operations.reactivate(supplierId) }

            SupplierDetailAction.ConfirmDialog -> confirmDialog()

            SupplierDetailAction.DismissDialog -> extra.update { it.copy(dialog = null) }

            SupplierDetailAction.Retry -> attempts.update { it + 1 }

            SupplierDetailAction.MessageShown -> extra.update { it.copy(message = null) }

            // Las resuelve la pantalla con la navegación o con otra app.
            SupplierDetailAction.Back, is SupplierDetailAction.Dial,
            is SupplierDetailAction.OpenPurchase, is SupplierDetailAction.OpenProduct,
            SupplierDetailAction.NewPurchase, SupplierDetailAction.OpenPayables,
            SupplierDetailAction.ReturnToSupplier,
            -> Unit
        }
    }

    private fun overview(): SupplierOverview? = latest

    private fun confirmDialog() {
        val dialog = extra.value.dialog ?: return
        extra.update { it.copy(dialog = null) }
        when (dialog) {
            is SupplierDialog.Archive ->
                lifecycle(SupplierMessage.Archived) { operations.archive(dialog.supplierId) }

            is SupplierDialog.Delete ->
                viewModelScope.launch {
                    val result = operations.delete(dialog.supplierId)
                    val message = lifecycleMessage(result, SupplierMessage.Deleted)
                    extra.update {
                        it.copy(message = message, closed = message == SupplierMessage.Deleted)
                    }
                }
        }
    }

    private fun lifecycle(done: SupplierMessage, block: suspend () -> SupplierLifecycleResult) {
        viewModelScope.launch { lifecycleMessage(block(), done)?.let(::show) }
    }

    private fun show(message: SupplierMessage) {
        extra.update { it.copy(message = message) }
    }

    private data class Extra(
        val dialog: SupplierDialog? = null,
        val message: SupplierMessage? = null,
        val closed: Boolean = false,
    )

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
