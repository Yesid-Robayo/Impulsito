package io.github.yesidrobayo.shopledger.ui.payable

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yesidrobayo.shopledger.domain.usecase.payable.PayableRow
import io.github.yesidrobayo.shopledger.domain.usecase.payable.PayableStatus
import io.github.yesidrobayo.shopledger.domain.usecase.payable.PayablesLoad
import io.github.yesidrobayo.shopledger.domain.usecase.payable.PayablesOverview
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/** Los filtros de la lista, en el orden en que se muestran. */
enum class PayableFilter { ALL, OVERDUE, UPCOMING, CREDIT }

/** Lo que muestra la lista de cuentas por pagar. */
@Immutable
sealed interface PayablesContent {
    data object Loading : PayablesContent

    /** Ningún proveedor tiene saldo: las compras a plazo aparecerán aquí. */
    data object Empty : PayablesContent

    /** Este teléfono no conoce la zona del negocio: no hay cómo saber qué está vencido. */
    data object UnknownZone : PayablesContent

    /** La lectura falló, o un libro tiene cifras que no caben: no se muestra nada a medias. */
    data object ReadError : PayablesContent

    /**
     * La lista con sus cifras. [rows] ya pasó por el filtro, y [overview] trae los totales de todos
     * los proveedores, que no cambian con el filtro.
     */
    data class Ready(val overview: PayablesOverview, val rows: List<PayableRow>) : PayablesContent
}

/** El estado de la lista: lo que se muestra y el filtro elegido. */
@Immutable
data class PayablesUiState(
    val content: PayablesContent = PayablesContent.Loading,
    val filter: PayableFilter = PayableFilter.ALL,
)

/** Lo que la persona hace en la lista. */
sealed interface PayablesAction {
    data object Back : PayablesAction

    data object Retry : PayablesAction

    data class FilterChange(val filter: PayableFilter) : PayablesAction

    data object ClearFilters : PayablesAction

    /** Abre el libro de un proveedor: lo resuelve la navegación. */
    data class OpenSupplier(val supplierId: Long) : PayablesAction

    /** `Nueva compra` desde la lista vacía: lo resuelve la navegación. */
    data object NewPurchase : PayablesAction

    data object ChooseZone : PayablesAction
}

/**
 * La lista de cuentas por pagar: los dos totales, el filtro y un proveedor por fila, primero lo
 * vencido. Solo lee: las deudas nacen de las compras y se pagan desde el detalle de cada proveedor.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PayablesViewModel(private val operations: PayableOperations) : ViewModel() {
    private val attempts = MutableStateFlow(0)
    private val filter = MutableStateFlow(PayableFilter.ALL)

    private val load = attempts.flatMapLatest {
        operations.payables()
            .map<PayablesLoad, Loaded> { Loaded.Shown(it) }
            .onStart { emit(Loaded.Waiting) }
            // Una lectura que falla no cierra la pantalla: ofrece reintentar.
            .catch { emit(Loaded.Failed) }
    }

    val uiState: StateFlow<PayablesUiState> = combine(load, filter) { loaded, chosen ->
        PayablesUiState(contentOf(loaded, chosen), chosen)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        PayablesUiState(),
    )

    fun onAction(action: PayablesAction) {
        when (action) {
            PayablesAction.Retry -> attempts.update { it + 1 }

            is PayablesAction.FilterChange -> filter.value = action.filter

            PayablesAction.ClearFilters -> filter.value = PayableFilter.ALL

            // Las resuelve la pantalla con la navegación.
            PayablesAction.Back, is PayablesAction.OpenSupplier, PayablesAction.NewPurchase,
            PayablesAction.ChooseZone,
            -> Unit
        }
    }

    private fun contentOf(loaded: Loaded, chosen: PayableFilter): PayablesContent = when (loaded) {
        Loaded.Waiting -> PayablesContent.Loading

        Loaded.Failed -> PayablesContent.ReadError

        is Loaded.Shown -> when (val result = loaded.load) {
            is PayablesLoad.UnknownTimeZone -> PayablesContent.UnknownZone

            PayablesLoad.LedgerDamaged -> PayablesContent.ReadError

            is PayablesLoad.Ready -> if (result.overview.rows.isEmpty()) {
                PayablesContent.Empty
            } else {
                PayablesContent.Ready(
                    result.overview,
                    result.overview.rows.filter { it.matches(chosen) },
                )
            }
        }
    }

    private sealed interface Loaded {
        data object Waiting : Loaded

        data object Failed : Loaded

        data class Shown(val load: PayablesLoad) : Loaded
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

/** Si la fila entra en [filter]. `Próximas` es lo que vence hoy o después, sin nada vencido. */
internal fun PayableRow.matches(filter: PayableFilter): Boolean = when (filter) {
    PayableFilter.ALL -> true
    PayableFilter.OVERDUE -> status == PayableStatus.OVERDUE
    PayableFilter.UPCOMING -> status == PayableStatus.UPCOMING
    PayableFilter.CREDIT -> status == PayableStatus.IN_FAVOR
}
