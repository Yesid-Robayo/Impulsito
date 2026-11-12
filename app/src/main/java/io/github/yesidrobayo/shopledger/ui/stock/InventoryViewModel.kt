package io.github.yesidrobayo.shopledger.ui.stock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yesidrobayo.shopledger.domain.usecase.stock.InventoryOverview
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/**
 * El inventario y la lista por agotarse.
 *
 * Todo sale de los productos, no de las alertas, así que funciona aunque las notificaciones estén
 * negadas. Cada cambio confirmado en otra pantalla, como una venta o un ajuste, llega solo, porque
 * la lista observa la base. El armado corre en [computation], nunca en el hilo principal.
 *
 * Con el chip de stock negativo y ya sin productos en negativo, muestra lo que se está acabando:
 * el aviso desaparece cuando el stock vuelve a cero o más.
 *
 * @param initialChip el chip con que se abre: desde Inicio, el de stock negativo.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InventoryViewModel(
    observe: () -> Flow<InventoryOverview>,
    computation: CoroutineDispatcher,
    initialChip: InventoryChip = InventoryChip.LOW_STOCK,
) : ViewModel() {
    private val chip = MutableStateFlow(initialChip)
    private val attempts = MutableStateFlow(0)

    private val overview: Flow<InventoryOverview?> = attempts.flatMapLatest {
        observe()
            .map<InventoryOverview, InventoryOverview?> { it }
            .flowOn(computation)
            // Una falla de lectura no cierra la pantalla: muestra el error y deja reintentar.
            .catch { emit(null) }
    }

    val uiState: StateFlow<InventoryUiState> =
        combine(chip, overview) { chosen, read ->
            if (read == null) return@combine InventoryUiState(chosen, InventoryContent.ReadError)
            val shown =
                if (chosen == InventoryChip.NEGATIVE_STOCK && read.negative.isEmpty()) {
                    InventoryChip.LOW_STOCK
                } else {
                    chosen
                }
            InventoryUiState(shown, contentOf(read, shown))
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = InventoryUiState(initialChip),
        )

    /** Lo que cambia el estado. Abrir, reponer, ajustar y crear los resuelve la ruta. */
    fun onAction(action: InventoryAction) {
        when (action) {
            is InventoryAction.ChipChanged -> chip.value = action.chip

            InventoryAction.Retry -> attempts.update { it + 1 }

            is InventoryAction.OpenProduct, is InventoryAction.Restock, is InventoryAction.Adjust,
            InventoryAction.CreateProduct,
            InventoryAction.OpenNotificationSettings,
            -> Unit
        }
    }

    private fun contentOf(overview: InventoryOverview, shown: InventoryChip): InventoryContent {
        if (!overview.hasProducts) return InventoryContent.EmptyCatalog
        val products = when (shown) {
            InventoryChip.LOW_STOCK -> overview.lowStock
            InventoryChip.NEGATIVE_STOCK -> overview.negative
            InventoryChip.ALL -> overview.tracked
        }
        return InventoryContent.Ready(
            lowStockCount = overview.lowStock.size,
            negativeCount = overview.negative.size,
            value = overview.value,
            archivedValue = overview.archivedValue,
            hasStockWithoutCost = overview.hasStockWithoutCost,
            items = products.map { inventoryItemOf(it, overview.units) },
        )
    }

    private companion object {
        /** Lo que sigue viva la lectura al rotar la pantalla, para no releer el inventario. */
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
