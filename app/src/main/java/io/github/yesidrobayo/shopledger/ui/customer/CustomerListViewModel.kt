package io.github.yesidrobayo.shopledger.ui.customer

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.customer.Customer
import io.github.yesidrobayo.shopledger.domain.rule.customer.CustomerSearch
import io.github.yesidrobayo.shopledger.domain.usecase.customer.CustomersOverview
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

/** Los filtros de la lista: uno a la vez. */
enum class CustomerFilter {
    ALL,

    /** Los que deben, también archivados: su deuda sigue en `Por cobrar`. */
    RECEIVABLE,

    /** Los que tienen saldo a favor, también archivados. */
    CREDIT,

    ARCHIVED,
}

/** Lo que muestra la lista, según lo que haya, el filtro y lo que se busque. */
@Immutable
sealed interface CustomerListContent {
    /** Todavía no llegan los clientes. */
    data object Loading : CustomerListContent

    /** No hay ningún cliente, ni vigente ni archivado: solo la invitación a crear el primero. */
    data object Empty : CustomerListContent

    /** Ninguno coincide con [query]. */
    data class NoResults(val query: String) : CustomerListContent

    /** Sin búsqueda, ninguno cabe en el filtro elegido. */
    data object FilterEmpty : CustomerListContent

    /** Los que se muestran, en el orden de su nombre, y los anonimizados al final. */
    data class Customers(val customers: List<Customer>) : CustomerListContent

    data object ReadError : CustomerListContent
}

/**
 * El estado de la lista de clientes: lo buscado, el filtro, lo que se muestra, lo que hay por cobrar
 * entre todos, si se muestran los puntos y el aviso de la última acción.
 *
 * [receivable] es `null` mientras no hay nadie que deba: entonces no hay tarjeta. Con [pickForSale],
 * la lista se abrió desde el cobro para elegir el cliente de la venta, y [picked] es el recién creado
 * en la hoja, que vuelve al cobro elegido.
 */
@Immutable
data class CustomerListUiState(
    val query: String = "",
    val filter: CustomerFilter = CustomerFilter.ALL,
    val content: CustomerListContent = CustomerListContent.Loading,
    val receivable: Money? = null,
    val showPoints: Boolean = false,
    val message: CustomerMessage? = null,
    val pickForSale: Boolean = false,
    val picked: Long? = null,
)

/** Lo que la persona hace en la lista, fuera de la hoja. */
sealed interface CustomerListAction {
    data class QueryChange(val query: String) : CustomerListAction

    data class FilterChange(val filter: CustomerFilter) : CustomerListAction

    /** Vuelve a `Todos` y borra la búsqueda. */
    data object ClearFilters : CustomerListAction

    data object Retry : CustomerListAction

    /** Abre la hoja vacía, con lo buscado como nombre si no hubo resultados. */
    data class NewCustomer(val name: String = "") : CustomerListAction

    /** Abre el detalle: lo resuelve la navegación. */
    data class OpenCustomer(val customerId: Long) : CustomerListAction

    /** Abre el programa de puntos: lo resuelve la navegación. */
    data object OpenLoyalty : CustomerListAction

    data object Back : CustomerListAction

    data class Reactivate(val customerId: Long) : CustomerListAction

    data object MessageShown : CustomerListAction

    /** El cliente recién creado ya volvió al cobro. */
    data object PickedShown : CustomerListAction
}

/**
 * La lista de clientes: busca por nombre, sin mayúsculas ni tildes, o por los dígitos del teléfono,
 * y filtra por quién debe, quién tiene saldo a favor o quién está archivado. La hoja crea uno nuevo
 * sin salir de la lista.
 *
 * Con [pickForSale], la lista elige el cliente del cobro: el que se crea en la hoja vuelve elegido,
 * sin el aviso de guardado, porque el cobro ya lo muestra.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CustomerListViewModel(
    private val operations: CustomerOperations,
    private val pickForSale: Boolean = false,
) : ViewModel() {
    private val query = MutableStateFlow("")
    private val filter = MutableStateFlow(CustomerFilter.ALL)
    private val attempts = MutableStateFlow(0)
    private val message = MutableStateFlow<CustomerMessage?>(null)
    private val picked = MutableStateFlow<Long?>(null)

    /** La hoja de crear un cliente, abierta o no. */
    val editor =
        CustomerEditorModel(
            scope = viewModelScope,
            save = operations::save,
            onSaved = { id ->
                if (pickForSale) picked.value = id else message.value = CustomerMessage.Saved
            },
        )

    private val overview = attempts.flatMapLatest {
        combine<CustomersOverview, Boolean, Loaded?>(
            operations.customers(),
            operations.loyaltyEnabled(),
        ) { customers, points -> Loaded(customers, points) }
            .onStart { emit(null) }
            // Una falla de lectura no cierra la pantalla: muestra el error y deja reintentar.
            .catch { emit(Loaded(failed = true)) }
    }

    val uiState: StateFlow<CustomerListUiState> =
        combine(overview, query, filter, message, picked) { loaded, typed, chosen, shown, created ->
            CustomerListUiState(
                query = typed,
                filter = chosen,
                content = when {
                    loaded == null -> CustomerListContent.Loading
                    loaded.failed -> CustomerListContent.ReadError
                    else -> contentOf(requireNotNull(loaded.overview), typed, chosen)
                },
                receivable = loaded?.overview?.receivable?.takeIf { it > Money.ZERO },
                showPoints = loaded?.showPoints ?: false,
                message = shown,
                pickForSale = pickForSale,
                picked = created,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = CustomerListUiState(),
        )

    fun onAction(action: CustomerListAction) {
        when (action) {
            is CustomerListAction.QueryChange -> query.value = action.query

            is CustomerListAction.FilterChange -> filter.value = action.filter

            CustomerListAction.ClearFilters -> {
                query.value = ""
                filter.value = CustomerFilter.ALL
            }

            CustomerListAction.Retry -> attempts.update { it + 1 }

            is CustomerListAction.NewCustomer -> editor.openNew(action.name.trim())

            // Los resuelve la navegación.
            is CustomerListAction.OpenCustomer, CustomerListAction.OpenLoyalty,
            CustomerListAction.Back,
            -> Unit

            is CustomerListAction.Reactivate ->
                viewModelScope.launch {
                    reactivateMessage(operations.reactivate(action.customerId))?.let {
                        message.value = it
                    }
                }

            CustomerListAction.MessageShown -> message.value = null

            CustomerListAction.PickedShown -> picked.value = null
        }
    }

    /** Lo leído, o la falla de leer. */
    private data class Loaded(
        val overview: CustomersOverview? = null,
        val showPoints: Boolean = false,
        val failed: Boolean = false,
    )

    companion object {
        /** Lo que sigue viva la lectura al rotar la pantalla, para no releer la lista. */
        private const val STOP_TIMEOUT_MILLIS = 5_000L

        /**
         * Si tocar [customerId] en [content] lo elige para la venta. Un archivado o un anonimizado
         * no se vende, como en la lista del cobro: tocarlo abre su detalle, como siempre.
         */
        internal fun pickable(content: CustomerListContent, customerId: Long): Boolean {
            val tapped = (content as? CustomerListContent.Customers)?.customers
                ?.firstOrNull { it.id == customerId } ?: return false
            return !tapped.isArchived && !tapped.isAnonymized
        }

        /** Lo que se muestra de [overview] con [typed] en la búsqueda y [chosen] como filtro. */
        internal fun contentOf(
            overview: CustomersOverview,
            typed: String,
            chosen: CustomerFilter,
        ): CustomerListContent {
            if (overview.active.isEmpty() && overview.archived.isEmpty()) {
                return CustomerListContent.Empty
            }
            // Vigentes y archivados juntos, en el mismo orden que cada grupo: por nombre, y los
            // anonimizados al final.
            val all = (overview.active + overview.archived).sortedWith(
                compareBy<Customer>({
                    it.contact == null
                }, { it.contact?.normalizedName }, { it.id }),
            )
            val base = when (chosen) {
                CustomerFilter.ALL -> overview.active
                CustomerFilter.RECEIVABLE -> all.filter { it.balance > Money.ZERO }
                CustomerFilter.CREDIT -> all.filter { it.balance < Money.ZERO }
                CustomerFilter.ARCHIVED -> overview.archived
            }
            val shown = CustomerSearch.filter(base, typed)
            return when {
                shown.isNotEmpty() -> CustomerListContent.Customers(shown)
                typed.isNotBlank() -> CustomerListContent.NoResults(typed.trim())
                else -> CustomerListContent.FilterEmpty
            }
        }
    }
}
