package io.github.yesidrobayo.shopledger.ui.stock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceReview
import io.github.yesidrobayo.shopledger.domain.model.stock.Location
import io.github.yesidrobayo.shopledger.domain.rule.stock.LocationRules
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
import kotlinx.coroutines.flow.distinctUntilChanged
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
 * `Revisar` aparece mientras haya algo que revisar: costo nuevo, margen bajo o costo viejo; vacía,
 * como el chip de stock negativo, muestra lo que se está acabando.
 *
 * @param initialChip el chip con que se abre: desde Inicio, el de stock negativo.
 * @param review la lista `Revisar`, ya ordenada, con los motivos de cada producto.
 * @param dismissReview saca un producto de la cola de costo nuevo sin cambiar su precio.
 * @param locations las ubicaciones vigentes. Con más de una, el selector deja ver el inventario de
 *   una sola; empieza en todas.
 * @param observeAt el inventario de una ubicación: lo que hay en ella, y lo que allí se acaba.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InventoryViewModel(
    observe: () -> Flow<InventoryOverview>,
    computation: CoroutineDispatcher,
    initialChip: InventoryChip = InventoryChip.LOW_STOCK,
    review: () -> Flow<List<PriceReview>> = { flowOf(emptyList()) },
    private val dismissReview: suspend (Long) -> DismissPriceReviewResult =
        { DismissPriceReviewResult.NotPending },
    locations: () -> Flow<List<Location>> = { flowOf(emptyList()) },
    observeAt: (Long) -> Flow<InventoryOverview> = { observe() },
) : ViewModel() {
    private val chip = MutableStateFlow(initialChip)
    private val attempts = MutableStateFlow(0)
    private val message = MutableStateFlow<InventoryMessage?>(null)
    private val location = MutableStateFlow<Long?>(null)

    // Sin ubicaciones leídas, el inventario sigue: solo no hay selector.
    private val choices: Flow<List<Location>> = attempts.flatMapLatest {
        locations().flowOn(computation).catch { emit(emptyList()) }
    }

    // La elegida, mientras siga vigente y haya más de una: si se archiva, vuelve a todas.
    private val shownLocation: Flow<Long?> = combine(location, choices) { chosen, list ->
        chosen?.takeIf { id -> LocationRules.showsLocations(list.size) && list.any { it.id == id } }
    }.distinctUntilChanged()

    // La lista no cierra la pantalla si falla: sin ella, `Revisar` no aparece.
    private val queue: Flow<List<PriceReview>> = attempts.flatMapLatest {
        review().flowOn(computation).catch { emit(emptyList()) }
    }

    private val overview: Flow<InventoryOverview?> = combine(attempts, shownLocation) { _, at ->
        at
    }.flatMapLatest { at ->
        (if (at == null) observe() else observeAt(at))
            .map<InventoryOverview, InventoryOverview?> { it }
            .flowOn(computation)
            // Una falla de lectura no cierra la pantalla: muestra el error y deja reintentar.
            .catch { emit(null) }
    }

    private val place: Flow<Pair<List<Location>, Long?>> =
        combine(choices, shownLocation) { list, at ->
            list.takeIf { LocationRules.showsLocations(it.size) }.orEmpty() to at
        }

    val uiState: StateFlow<InventoryUiState> =
        combine(chip, overview, queue, message, place) { chosen, read, pending, shownMessage, at ->
            val (shownLocations, locationId) = at
            if (read == null) {
                return@combine InventoryUiState(
                    chosen,
                    InventoryContent.ReadError,
                    shownMessage,
                    shownLocations,
                    locationId,
                )
            }
            val empty = when (chosen) {
                InventoryChip.NEGATIVE_STOCK -> read.negative.isEmpty()
                InventoryChip.REVIEW -> pending.isEmpty()
                InventoryChip.LOW_STOCK, InventoryChip.ALL -> false
            }
            val shown = if (empty) InventoryChip.LOW_STOCK else chosen
            InventoryUiState(
                shown,
                contentOf(read, shown, pending),
                shownMessage,
                shownLocations,
                locationId,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = InventoryUiState(initialChip),
        )

    /** Lo que cambia el estado. Abrir, reponer, ajustar y crear los resuelve la ruta. */
    fun onAction(action: InventoryAction) {
        when (action) {
            is InventoryAction.ChipChanged -> chip.value = action.chip

            is InventoryAction.LocationChanged -> location.value = action.locationId

            InventoryAction.Retry -> attempts.update { it + 1 }

            is InventoryAction.DismissReview -> dismiss(action.productId)

            InventoryAction.MessageShown -> message.value = null

            is InventoryAction.OpenProduct, is InventoryAction.Restock, is InventoryAction.Adjust,
            is InventoryAction.ChangePrice,
            InventoryAction.ReviewAll,
            InventoryAction.CreateProduct,
            InventoryAction.OpenNotificationSettings,
            InventoryAction.ManageLocations,
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
        pending: List<PriceReview>,
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
            items = if (shown == InventoryChip.ALL) {
                groupedItems(overview)
            } else {
                products.map { inventoryItemOf(it, overview.units) }
            },
            reviewItems = pending.map(::reviewItemOf),
        )
    }

    /**
     * Todos los productos con stock, en orden de nombre, con cada producto con variantes como una
     * fila con la suma de las suyas, seguida de ellas.
     */
    private fun groupedItems(overview: InventoryOverview): List<InventoryItem> {
        val parents = overview.families.mapTo(mutableSetOf()) { it.parent.id }
        val singles = overview.tracked.filter { it.details.parentProductId !in parents }
            .map { it.details.normalizedName to listOf(inventoryItemOf(it, overview.units)) }
        val families = overview.families.map { family ->
            val total = inventoryItemOf(family.parent.copy(onHand = family.onHand), overview.units)
            family.parent.details.normalizedName to
                listOf(total) + family.variants.map { inventoryItemOf(it, overview.units) }
        }
        return (singles + families).sortedBy { it.first }.flatMap { it.second }
    }

    /**
     * La fila de [review]: el margen es el de la regla, sin impuesto, para que la cifra y el
     * motivo digan lo mismo.
     */
    private fun reviewItemOf(review: PriceReview): PriceReviewItem {
        val product = review.product
        return PriceReviewItem(
            id = product.id,
            name = product.details.name,
            trailing = ProductListItems.trailingOf(product).copy(margin = review.margin),
            reasons = review.reasons,
            costSince = review.costSince,
        )
    }

    private companion object {
        /** Lo que sigue viva la lectura al rotar la pantalla, para no releer el inventario. */
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
