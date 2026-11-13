package io.github.yesidrobayo.shopledger.ui.stock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.usecase.pricing.DismissPriceReviewResult
import io.github.yesidrobayo.shopledger.domain.usecase.stock.InventoryOverview
import io.github.yesidrobayo.shopledger.ui.product.ProductListItems
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
 * `Revisar` aparece mientras haya algo en la cola `Costo nuevo sin revisar`; vacía, como el chip de
 * stock negativo, muestra lo que se está acabando.
 *
 * @param initialChip el chip con que se abre: desde Inicio, el de stock negativo.
 * @param reviewQueue la cola `Costo nuevo sin revisar`, el más antiguo primero.
 * @param dismissReview saca un producto de la cola sin cambiar su precio.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InventoryViewModel(
    observe: () -> Flow<InventoryOverview>,
    computation: CoroutineDispatcher,
    initialChip: InventoryChip = InventoryChip.LOW_STOCK,
    reviewQueue: () -> Flow<List<Product>> = { flowOf(emptyList()) },
    private val dismissReview: suspend (Long) -> DismissPriceReviewResult =
        { DismissPriceReviewResult.NotPending },
) : ViewModel() {
    private val chip = MutableStateFlow(initialChip)
    private val attempts = MutableStateFlow(0)
    private val message = MutableStateFlow<InventoryMessage?>(null)

    // La cola no cierra la pantalla si falla: sin ella, `Revisar` no aparece.
    private val queue: Flow<List<Product>> = attempts.flatMapLatest {
        reviewQueue().flowOn(computation).catch { emit(emptyList()) }
    }

    private val overview: Flow<InventoryOverview?> = attempts.flatMapLatest {
        observe()
            .map<InventoryOverview, InventoryOverview?> { it }
            .flowOn(computation)
            // Una falla de lectura no cierra la pantalla: muestra el error y deja reintentar.
            .catch { emit(null) }
    }

    val uiState: StateFlow<InventoryUiState> =
        combine(chip, overview, queue, message) { chosen, read, pending, shownMessage ->
            if (read == null) {
                return@combine InventoryUiState(chosen, InventoryContent.ReadError, shownMessage)
            }
            val empty = when (chosen) {
                InventoryChip.NEGATIVE_STOCK -> read.negative.isEmpty()
                InventoryChip.REVIEW -> pending.isEmpty()
                InventoryChip.LOW_STOCK, InventoryChip.ALL -> false
            }
            val shown = if (empty) InventoryChip.LOW_STOCK else chosen
            InventoryUiState(shown, contentOf(read, shown, pending), shownMessage)
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

            is InventoryAction.DismissReview -> dismiss(action.productId)

            InventoryAction.MessageShown -> message.value = null

            is InventoryAction.OpenProduct, is InventoryAction.Restock, is InventoryAction.Adjust,
            is InventoryAction.ChangePrice,
            InventoryAction.ReviewAll,
            InventoryAction.CreateProduct,
            InventoryAction.OpenNotificationSettings,
            -> Unit
        }
    }

    /** Saca el producto de la cola. Si no se pudo, sigue en ella y un aviso lo dice. */
    private fun dismiss(productId: Long) {
        viewModelScope.launch {
            when (val result = dismissReview(productId)) {
                // La cola observa la base: la fila se va sola.
                DismissPriceReviewResult.Dismissed, DismissPriceReviewResult.NotPending -> Unit

                is DismissPriceReviewResult.StorageFailed ->
                    message.value = InventoryMessage.DismissFailed(result.failure.isOutOfSpace)
            }
        }
    }

    private fun contentOf(
        overview: InventoryOverview,
        shown: InventoryChip,
        pending: List<Product>,
    ): InventoryContent {
        if (!overview.hasProducts) return InventoryContent.EmptyCatalog
        val products = when (shown) {
            InventoryChip.LOW_STOCK -> overview.lowStock

            InventoryChip.NEGATIVE_STOCK -> overview.negative

            InventoryChip.ALL -> overview.tracked

            // `Revisar` lleva sus propias filas.
            InventoryChip.REVIEW -> emptyList()
        }
        return InventoryContent.Ready(
            lowStockCount = overview.lowStock.size,
            negativeCount = overview.negative.size,
            value = overview.value,
            archivedValue = overview.archivedValue,
            hasStockWithoutCost = overview.hasStockWithoutCost,
            items = products.map { inventoryItemOf(it, overview.units) },
            reviewItems = pending.map {
                PriceReviewItem(it.id, it.details.name, ProductListItems.trailingOf(it))
            },
        )
    }

    private companion object {
        /** Lo que sigue viva la lectura al rotar la pantalla, para no releer el inventario. */
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
