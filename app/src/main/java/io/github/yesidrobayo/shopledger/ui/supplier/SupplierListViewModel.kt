package io.github.yesidrobayo.shopledger.ui.supplier

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yesidrobayo.shopledger.domain.model.catalog.SupplierListEntry
import io.github.yesidrobayo.shopledger.domain.model.catalog.SuppliersOverview
import io.github.yesidrobayo.shopledger.domain.rule.TextNormalizer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Lo que muestra la lista, según lo que haya y lo que se busque. */
@Immutable
sealed interface SupplierListContent {
    /** Todavía no llegan los proveedores. */
    data object Loading : SupplierListContent

    /** No hay ningún proveedor, ni vigente ni archivado: solo la invitación a crear el primero. */
    data object Empty : SupplierListContent

    /** Ninguno coincide con [query]. */
    data class NoResults(val query: String) : SupplierListContent

    /** Los que coinciden, vigentes y archivados aparte. */
    data class Suppliers(
        val active: List<SupplierListEntry>,
        val archived: List<SupplierListEntry>,
    ) : SupplierListContent

    data object ReadError : SupplierListContent
}

/**
 * El estado de la lista de proveedores: lo escrito en la búsqueda, lo que se muestra, si el grupo
 * de archivados está abierto, la confirmación pendiente y el aviso de la última acción.
 */
@Immutable
data class SupplierListUiState(
    val query: String = "",
    val content: SupplierListContent = SupplierListContent.Loading,
    val showArchived: Boolean = false,
    val message: SupplierMessage? = null,
)

/** Lo que la persona hace en la lista, fuera de la hoja. */
sealed interface SupplierListAction {
    data class QueryChange(val query: String) : SupplierListAction

    data object Retry : SupplierListAction

    /** Abre la hoja vacía, con lo buscado como nombre si no hubo resultados. */
    data class NewSupplier(val name: String = "") : SupplierListAction

    /** Abre el detalle: lo resuelve la navegación. */
    data class OpenSupplier(val supplierId: Long) : SupplierListAction

    data object ToggleArchived : SupplierListAction

    data class Reactivate(val supplierId: Long) : SupplierListAction

    data object MessageShown : SupplierListAction
}

/**
 * La lista de proveedores y su búsqueda, sin mayúsculas ni tildes. Los archivados van en su propio
 * grupo, cerrado al abrir, y cada uno se puede reactivar desde ahí. La hoja crea uno nuevo sin salir
 * de la lista.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SupplierListViewModel(private val operations: SupplierOperations) : ViewModel() {
    private val query = MutableStateFlow("")
    private val attempts = MutableStateFlow(0)
    private val extra = MutableStateFlow(Extra())

    /** La hoja de crear un proveedor, abierta o no. */
    val editor =
        SupplierEditorModel(
            scope = viewModelScope,
            save = operations::save,
            onSaved = { show(SupplierMessage.Saved) },
        )

    private val content = attempts.flatMapLatest {
        combine(operations.suppliers(), query) { overview, typed -> contentOf(overview, typed) }
            .onStart { emit(SupplierListContent.Loading) }
            // Una falla de lectura no cierra la pantalla: muestra el error y deja reintentar.
            .catch { emit(SupplierListContent.ReadError) }
    }

    val uiState: StateFlow<SupplierListUiState> =
        combine(query, content, extra) { typed, shown, more ->
            SupplierListUiState(typed, shown, more.showArchived, more.message)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = SupplierListUiState(),
        )

    fun onAction(action: SupplierListAction) {
        when (action) {
            is SupplierListAction.QueryChange -> query.value = action.query

            SupplierListAction.Retry -> attempts.update { it + 1 }

            is SupplierListAction.NewSupplier -> editor.openNew(action.name.trim())

            // Lo resuelve la navegación.
            is SupplierListAction.OpenSupplier -> Unit

            SupplierListAction.ToggleArchived ->
                extra.update { it.copy(showArchived = !it.showArchived) }

            is SupplierListAction.Reactivate ->
                viewModelScope.launch {
                    lifecycleMessage(
                        operations.reactivate(action.supplierId),
                        SupplierMessage.Reactivated,
                    )?.let(::show)
                }

            SupplierListAction.MessageShown -> extra.update { it.copy(message = null) }
        }
    }

    private fun show(message: SupplierMessage) {
        extra.update { it.copy(message = message) }
    }

    private data class Extra(
        val showArchived: Boolean = false,
        val message: SupplierMessage? = null,
    )

    companion object {
        /** Lo que sigue viva la lectura al rotar la pantalla, para no releer la lista. */
        private const val STOP_TIMEOUT_MILLIS = 5_000L

        /** Lo que se muestra de [overview] con [typed] escrito en la búsqueda. */
        internal fun contentOf(overview: SuppliersOverview, typed: String): SupplierListContent {
            if (overview.active.isEmpty() && overview.archived.isEmpty()) {
                return SupplierListContent.Empty
            }
            val wanted = TextNormalizer.normalize(typed.trim())
            if (wanted.isEmpty()) {
                return SupplierListContent.Suppliers(overview.active, overview.archived)
            }
            val matches: (SupplierListEntry) -> Boolean = {
                it.supplier.details.normalizedName.contains(wanted)
            }
            val active = overview.active.filter(matches)
            val archived = overview.archived.filter(matches)
            return if (active.isEmpty() && archived.isEmpty()) {
                SupplierListContent.NoResults(typed.trim())
            } else {
                SupplierListContent.Suppliers(active, archived)
            }
        }
    }
}
