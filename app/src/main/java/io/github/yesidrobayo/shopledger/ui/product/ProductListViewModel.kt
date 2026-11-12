package io.github.yesidrobayo.shopledger.ui.product

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ObserveProductCatalogUseCase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/**
 * La lista de productos vigentes y su búsqueda.
 *
 * Lo escrito en la búsqueda se ve al instante, y la lista se rearma cuando la persona deja de
 * escribir un momento: así cada tecla no rearma 5000 filas. Borrar la búsqueda muestra todo sin
 * esperar. El armado corre en [computation], nunca en el hilo principal, y cada cambio del catálogo
 * confirmado en otra pantalla llega solo, porque la lista observa la base.
 *
 * Abrir un producto o crear uno lo resuelve la pantalla con la navegación: aquí no hay estado que
 * cambie por eso.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class ProductListViewModel(
    observeCatalog: ObserveProductCatalogUseCase,
    computation: CoroutineDispatcher,
) : ViewModel() {
    private val query = MutableStateFlow("")
    private val attempts = MutableStateFlow(0)

    private val content = attempts.flatMapLatest {
        combine(
            observeCatalog(),
            query.debounce { if (it.isBlank()) 0L else SEARCH_PAUSE_MILLIS },
        ) { catalog, typed -> ProductListItems.build(catalog.products, catalog.units, typed) }
            .flowOn(computation)
            // Una falla de lectura no cierra la pantalla: muestra el error y deja reintentar.
            .catch { emit(ProductListContent.ReadError) }
    }

    val uiState: StateFlow<ProductListUiState> =
        combine(query, content) { typed, shown -> ProductListUiState(typed, shown) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = ProductListUiState(),
            )

    /** Lo que cambia el estado de la lista. La navegación la resuelve la pantalla. */
    fun onAction(action: ProductListAction) {
        when (action) {
            is ProductListAction.QueryChange -> query.value = action.query

            ProductListAction.Retry -> attempts.update { it + 1 }

            is ProductListAction.OpenProduct, is ProductListAction.CreateProduct,
            ProductListAction.UpdatePrices,
            -> Unit
        }
    }

    companion object {
        /** La pausa al escribir tras la que se busca: dentro de lo que pide la búsqueda fluida. */
        const val SEARCH_PAUSE_MILLIS = 100L

        /** Lo que sigue viva la lectura al rotar la pantalla, para no releer el catálogo. */
        private const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
