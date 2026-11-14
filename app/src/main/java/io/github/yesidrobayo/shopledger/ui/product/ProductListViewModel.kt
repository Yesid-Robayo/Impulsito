package io.github.yesidrobayo.shopledger.ui.product

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.model.catalog.Category
import io.github.yesidrobayo.shopledger.domain.model.catalog.Supplier
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ObserveProductCatalogUseCase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/**
 * Las categorías y los proveedores que se pueden elegir como filtro. Es una interfaz para que las
 * pruebas den listas fijas sin una base.
 */
interface ProductFilterChoices {
    fun categories(): Flow<List<Category>>

    /** Los proveedores vigentes. */
    fun suppliers(): Flow<List<Supplier>>

    companion object {
        /** Sin categorías ni proveedores: la hoja solo ofrece el estado y el orden. */
        val NONE: ProductFilterChoices =
            object : ProductFilterChoices {
                override fun categories(): Flow<List<Category>> = flowOf(emptyList())

                override fun suppliers(): Flow<List<Supplier>> = flowOf(emptyList())
            }
    }
}

/**
 * La lista de productos, su búsqueda, sus filtros y su orden.
 *
 * Lo escrito en la búsqueda se ve al instante, y la lista se rearma cuando la persona deja de
 * escribir un momento: así cada tecla no rearma 5000 filas. Borrar la búsqueda muestra todo sin
 * esperar. Un filtro o un orden se aplican al tocarlos. El armado corre en [computation], nunca en
 * el hilo principal, y cada cambio del catálogo confirmado en otra pantalla llega solo, porque la
 * lista observa la base.
 *
 * Abrir un producto o crear uno lo resuelve la pantalla con la navegación: aquí no hay estado que
 * cambie por eso.
 *
 * @param tagIndex el nombre normalizado de las etiquetas de cada producto, para que la búsqueda
 *   también encuentre por ellas. Si no se puede leer, se busca sin etiquetas.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class ProductListViewModel(
    observeCatalog: ObserveProductCatalogUseCase,
    computation: CoroutineDispatcher,
    choices: ProductFilterChoices = ProductFilterChoices.NONE,
    hasCodes: Flow<Boolean> = flowOf(false),
    tagIndex: Flow<Map<Long, List<String>>> = flowOf(emptyMap()),
) : ViewModel() {
    /** Si algún producto tiene código: sin ninguno, la búsqueda no ofrece el escáner. */
    val canScan: StateFlow<Boolean> =
        hasCodes
            .catch { emit(false) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), false)

    private val tags = tagIndex.catch { emit(emptyMap()) }

    private val query = MutableStateFlow("")
    private val attempts = MutableStateFlow(0)
    private val filters = MutableStateFlow(ProductListFilters())
    private val filtersOpen = MutableStateFlow(false)

    private val content = attempts.flatMapLatest {
        // Los vigentes o los archivados, según el filtro, con el filtro para el que se leyeron: al
        // cambiarlo, la lista espera la lectura nueva en vez de mostrar la vieja filtrada.
        val catalog = filters
            .map { it.archived }
            .distinctUntilChanged()
            .flatMapLatest { archived ->
                val filter = if (archived) ArchiveFilter.ARCHIVED else ArchiveFilter.ACTIVE
                observeCatalog(filter).map { archived to it }
            }
        combine(
            catalog,
            query.debounce { if (it.isBlank()) 0L else SEARCH_PAUSE_MILLIS },
            filters,
            tags,
        ) { (archived, read), typed, chosen, index ->
            if (archived != chosen.archived) {
                ProductListContent.Loading
            } else {
                ProductListItems.build(read.products, read.units, typed, chosen, index)
            }
        }
            .flowOn(computation)
            // Una falla de lectura no cierra la pantalla: muestra el error y deja reintentar.
            .catch { emit(ProductListContent.ReadError) }
    }

    private val lists =
        combine(
            choices.categories().catch { emit(emptyList()) },
            choices.suppliers().catch { emit(emptyList()) },
        ) { categories, suppliers -> categories to suppliers }

    val uiState: StateFlow<ProductListUiState> =
        combine(query, content, filters, filtersOpen, lists) { typed, shown, chosen, open, sheet ->
            ProductListUiState(
                query = typed,
                content = shown,
                filters = chosen,
                filtersOpen = open,
                categories = sheet.first,
                suppliers = sheet.second,
            )
        }
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

            ProductListAction.OpenFilters -> filtersOpen.value = true

            ProductListAction.CloseFilters -> filtersOpen.value = false

            is ProductListAction.FilterCategory ->
                filters.update { it.copy(categoryId = action.categoryId) }

            is ProductListAction.FilterSupplier ->
                filters.update { it.copy(supplierId = action.supplierId) }

            is ProductListAction.FilterArchived ->
                filters.update { it.copy(archived = action.archived) }

            is ProductListAction.SortBy -> filters.update { it.copy(sort = action.sort) }

            // Quitar filtros conserva el orden elegido: no quita productos.
            ProductListAction.ClearFilters ->
                filters.update { ProductListFilters(sort = it.sort) }

            is ProductListAction.OpenProduct, is ProductListAction.CreateProduct,
            ProductListAction.UpdatePrices, ProductListAction.OpenLabels,
            ProductListAction.OpenImport,
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
