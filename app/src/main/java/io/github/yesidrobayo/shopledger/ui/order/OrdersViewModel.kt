package io.github.yesidrobayo.shopledger.ui.order

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.document.Order
import io.github.yesidrobayo.shopledger.domain.model.document.OrderStatus
import io.github.yesidrobayo.shopledger.domain.usecase.document.OrderRow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/** Lo que la lista de pedidos lee. */
interface OrderListOperations {
    /** Los de [status]: sin entregar, por fecha de entrega; los demás, del más nuevo al más viejo. */
    fun list(status: OrderStatus): Flow<List<OrderRow>>

    /** Si hay al menos un pedido, en cualquier estado. */
    fun hasAny(): Flow<Boolean>

    /** Hoy en la zona del negocio, o `null` si el teléfono no la conoce. */
    fun today(): Flow<BusinessDate?>
}

/** Un pedido como lo muestra su fila. [overdue] es sin entregar con la fecha de entrega pasada. */
@Immutable
data class OrderItem(
    val id: Long,
    val customerName: String?,
    val dueDate: BusinessDate?,
    val total: Money,
    val status: OrderStatus,
    val overdue: Boolean,
)

/** Lo que muestra la lista. */
sealed interface OrdersContent {
    /** Leyendo. El esqueleto solo aparece si tarda. */
    data object Loading : OrdersContent

    /** No se pudo leer. Se puede reintentar. */
    data object Failed : OrdersContent

    /** No hay ningún pedido: se invita a crear el primero. */
    data object Empty : OrdersContent

    /** Hay pedidos, pero ninguno con el estado elegido. */
    data object FilterEmpty : OrdersContent

    data class Rows(val items: List<OrderItem>) : OrdersContent
}

/**
 * La lista de pedidos: el estado elegido en los chips, lo que se lee con él y, en una ventana
 * ancha, el pedido abierto a la derecha.
 */
@Immutable
data class OrdersUiState(
    val filter: OrderStatus = OrderStatus.PENDING,
    val content: OrdersContent = OrdersContent.Loading,
    val selectedId: Long? = null,
)

/** Lo que la persona hace en la lista. */
sealed interface OrdersAction {
    data class SelectFilter(val status: OrderStatus) : OrdersAction

    /** Abre el pedido: encima en un teléfono, a la derecha en una ventana ancha. */
    data class Open(val orderId: Long) : OrdersAction

    data object CloseDetail : OrdersAction

    data object Retry : OrdersAction

    /** Navegación: las resuelve la ruta. */
    data object New : OrdersAction

    data object Back : OrdersAction
}

/**
 * La lista de pedidos. Empieza en `Pendientes`. Uno sin entregar con la fecha de entrega pasada se
 * marca `Entrega vencida`, calculado con la fecha de hoy: su estado no cambia solo. Una falla de
 * lectura no cierra la pantalla: se dice y se puede reintentar.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OrdersViewModel(private val operations: OrderListOperations) : ViewModel() {
    private val filter = MutableStateFlow(OrderStatus.PENDING)
    private val selected = MutableStateFlow<Long?>(null)
    private val attempts = MutableStateFlow(0)

    private val rows: Flow<List<OrderRow>?> = combine(filter, attempts) { status, _ -> status }
        .flatMapLatest { status ->
            operations.list(status).map<List<OrderRow>, List<OrderRow>?> { it }
                .catch { emit(null) }
        }

    val uiState: StateFlow<OrdersUiState> = combine(
        filter,
        rows,
        operations.hasAny().catch { emit(true) },
        operations.today().catch { emit(null) },
        selected,
    ) { status, read, any, today, open ->
        OrdersUiState(filter = status, content = contentOf(read, any, today), selectedId = open)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        OrdersUiState(),
    )

    fun onAction(action: OrdersAction) {
        when (action) {
            is OrdersAction.SelectFilter -> {
                filter.value = action.status
                selected.value = null
            }

            is OrdersAction.Open -> selected.value = action.orderId

            OrdersAction.CloseDetail -> selected.value = null

            OrdersAction.Retry -> attempts.update { it + 1 }

            OrdersAction.New, OrdersAction.Back -> Unit
        }
    }

    private fun contentOf(
        read: List<OrderRow>?,
        any: Boolean,
        today: BusinessDate?,
    ): OrdersContent = when {
        read == null -> OrdersContent.Failed
        read.isNotEmpty() -> OrdersContent.Rows(read.map { itemOf(it, today) })
        any -> OrdersContent.FilterEmpty
        else -> OrdersContent.Empty
    }

    private fun itemOf(row: OrderRow, today: BusinessDate?): OrderItem {
        val order = row.order
        return OrderItem(
            id = order.id,
            customerName = row.customerName,
            dueDate = order.dueDate,
            total = order.total,
            status = order.status,
            overdue = isOverdue(order, today),
        )
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

/** Si [order] sigue sin entregar con su fecha de entrega antes de [today]. */
internal fun isOverdue(order: Order, today: BusinessDate?): Boolean {
    val due = order.dueDate
    return order.status.isOpen && due != null && today != null && due < today
}
