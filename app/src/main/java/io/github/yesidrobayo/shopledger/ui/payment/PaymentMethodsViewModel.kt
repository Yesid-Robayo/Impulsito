package io.github.yesidrobayo.shopledger.ui.payment

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodRecord
import io.github.yesidrobayo.shopledger.domain.usecase.payment.ObservePaymentMethodsUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.payment.ReorderPaymentMethodsResult
import io.github.yesidrobayo.shopledger.domain.usecase.payment.ReorderPaymentMethodsUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * La lista de métodos de pago: vigentes y archivados, siempre al día con la base, y el modo de
 * ordenar.
 *
 * Mientras se ordena, la lista que llega de la base se sigue guardando, pero el orden armado no se
 * toca: si cambia lo que hay, guardar responde `ListChanged` y el modo empieza de nuevo con la lista
 * de hoy.
 */
class PaymentMethodsViewModel(
    private val observe: ObservePaymentMethodsUseCase,
    private val reorder: ReorderPaymentMethodsUseCase,
) : ViewModel() {
    private val state = MutableStateFlow<PaymentMethodsUiState>(PaymentMethodsUiState.Loading)
    private var reading: Job? = null

    val uiState: StateFlow<PaymentMethodsUiState> = state.asStateFlow()

    init {
        read()
    }

    fun onAction(action: PaymentMethodsAction) {
        when (action) {
            PaymentMethodsAction.Retry -> read()

            PaymentMethodsAction.ToggleArchived -> updateReady {
                it.copy(archivedExpanded = !it.archivedExpanded)
            }

            PaymentMethodsAction.StartSorting -> updateReady {
                it.copy(sorting = SortingState(it.active))
            }

            PaymentMethodsAction.CancelSorting -> updateReady { it.copy(sorting = null) }

            is PaymentMethodsAction.Move -> updateReady { ready ->
                val sorting = ready.sorting ?: return@updateReady ready
                ready.copy(
                    sorting = SortingState(moved(sorting.order, action.from, action.to)),
                )
            }

            PaymentMethodsAction.SaveOrder -> saveOrder()

            // La navegación la resuelve la ruta: aquí no cambia nada.
            PaymentMethodsAction.Back,
            PaymentMethodsAction.NewMethod,
            is PaymentMethodsAction.OpenMethod,
            -> Unit
        }
    }

    private fun read() {
        reading?.cancel()
        state.value = PaymentMethodsUiState.Loading
        reading = viewModelScope.launch {
            combine(observe(ArchiveFilter.ACTIVE), observe(ArchiveFilter.ARCHIVED)) { a, b ->
                a to b
            }
                .catch { failure ->
                    if (failure is CancellationException) throw failure
                    state.value = PaymentMethodsUiState.ReadError
                }.collect { (active, archived) ->
                    state.update { current ->
                        when (current) {
                            is PaymentMethodsUiState.Ready ->
                                current.copy(active = active, archived = archived)

                            PaymentMethodsUiState.Loading,
                            PaymentMethodsUiState.ReadError,
                            -> PaymentMethodsUiState.Ready(active, archived)
                        }
                    }
                }
        }
    }

    private fun saveOrder() {
        val ready = state.value as? PaymentMethodsUiState.Ready ?: return
        val sorting = ready.sorting ?: return
        if (sorting.status == SortingStatus.Saving) return
        updateReady { it.copy(sorting = sorting.copy(status = SortingStatus.Saving)) }
        viewModelScope.launch {
            val result = reorder(sorting.order.map { it.id })
            updateReady { current ->
                when (result) {
                    ReorderPaymentMethodsResult.Reordered,
                    ReorderPaymentMethodsResult.Unchanged,
                    -> current.copy(sorting = null)

                    ReorderPaymentMethodsResult.ListChanged ->
                        current.copy(
                            sorting = SortingState(current.active, SortingStatus.ListChanged),
                        )

                    is ReorderPaymentMethodsResult.StorageFailed ->
                        current.copy(
                            sorting =
                                sorting.copy(
                                    status = SortingStatus.Failed(
                                        SaveProblem.of(result.failure.isOutOfSpace),
                                    ),
                                ),
                        )
                }
            }
        }
    }

    private fun updateReady(change: (PaymentMethodsUiState.Ready) -> PaymentMethodsUiState.Ready) {
        state.update { current ->
            if (current is PaymentMethodsUiState.Ready) change(current) else current
        }
    }

    internal companion object {
        /** [order] con el elemento de [from] llevado a [to]. Fuera de rango, queda igual. */
        fun moved(
            order: List<PaymentMethodRecord>,
            from: Int,
            to: Int,
        ): List<PaymentMethodRecord> {
            if (from !in order.indices || to !in order.indices || from == to) return order
            val list = order.toMutableList()
            list.add(to, list.removeAt(from))
            return list
        }
    }
}
