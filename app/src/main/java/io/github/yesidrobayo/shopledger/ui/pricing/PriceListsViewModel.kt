package io.github.yesidrobayo.shopledger.ui.pricing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Margin
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.catalog.PricingContext
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceList
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceListDraft
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceListPricing
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceListsOverview
import io.github.yesidrobayo.shopledger.domain.rule.TextNormalizer
import io.github.yesidrobayo.shopledger.domain.rule.catalog.ProductPricing
import io.github.yesidrobayo.shopledger.domain.rule.pricing.ListPriceResult
import io.github.yesidrobayo.shopledger.domain.rule.pricing.ListPriceRules
import io.github.yesidrobayo.shopledger.domain.usecase.pricing.PriceListLifecycleResult
import io.github.yesidrobayo.shopledger.domain.usecase.pricing.SavePriceListResult
import io.github.yesidrobayo.shopledger.domain.usecase.pricing.SetListPriceResult
import io.github.yesidrobayo.shopledger.ui.ScreenReads
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Lo que la pantalla de listas de precios le pide al dominio. Es una interfaz para que las pruebas
 * del ViewModel den respuestas fijas sin una base.
 */
interface PriceListsOperations {
    fun lists(): Flow<PriceListsOverview>

    /** Los precios propios: por lista, y dentro, por producto. */
    fun ownPrices(): Flow<Map<Long, Map<Long, Money>>>

    /** Cuántos clientes vigentes tiene cada lista. */
    fun customerCounts(): Flow<Map<Long, Int>>

    /** Los productos vigentes: los que se venden, y por eso tienen precio en cada lista. */
    fun products(): Flow<List<Product>>

    /** Lo que el negocio fija para medir el margen. */
    fun pricing(): Flow<PricingContext>

    suspend fun saveList(editingId: Long?, draft: PriceListDraft): SavePriceListResult

    suspend fun archive(id: Long): PriceListLifecycleResult

    suspend fun reactivate(id: Long): PriceListLifecycleResult

    suspend fun setPrice(listId: Long, productId: Long, price: Money?): SetListPriceResult
}

/** El nombre de una lista en el idioma de la interfaz: el propio, o el traducido de la inicial. */
fun interface PriceListNames {
    fun name(list: PriceList): String
}

/**
 * La pantalla de listas de precios: las listas, con su tipo y cuántos precios propios tienen; el
 * detalle de una, con el precio de cada producto en ella y de dónde sale; y las hojas para crear o
 * editar una lista y para cambiar un precio.
 *
 * Los precios de una lista se recalculan fuera del hilo principal con cada cambio, porque una lista
 * puede tener miles de productos. La inicial no se renombra ni se archiva. Archivar pregunta antes,
 * con cuántos clientes vuelven a la inicial.
 */
class PriceListsViewModel(
    private val operations: PriceListsOperations,
    private val names: PriceListNames,
    numbers: PriceListNumberFormat,
    formatAdjustment: (BasisPoints) -> String,
    formatPrice: (Money) -> String,
    computation: CoroutineDispatcher,
) : ViewModel() {
    private val state = MutableStateFlow(PriceListsUiState())
    val uiState: StateFlow<PriceListsUiState> = state.asStateFlow()

    private val reads = ScreenReads()

    /** La lista abierta y lo buscado: el detalle se recalcula con cada cambio de los dos. */
    private val selection = MutableStateFlow<Pair<Long?, String>>(null to "")
    private var catalog = Catalog()
    private var customers: Map<Long, Int> = emptyMap()

    /** La hoja de una lista, abierta o no. */
    val listEditor =
        PriceListEditorModel(
            scope = viewModelScope,
            numbers = numbers,
            formatAdjustment = formatAdjustment,
            save = operations::saveList,
            onSaved = { id ->
                select(id, "")
                show(PriceListsMessage.ListSaved)
            },
        )

    /** La hoja del precio de un producto, abierta o no. */
    val priceEditor =
        ListPriceEditorModel(
            scope = viewModelScope,
            numbers = numbers,
            formatPrice = formatPrice,
            set = operations::setPrice,
            onSaved = { show(PriceListsMessage.PriceSaved) },
        )

    init {
        viewModelScope.launch {
            reads.guard {
                combine(operations.lists(), operations.ownPrices()) { overview, prices ->
                    overview to prices
                }
            }.collect { (overview, prices) ->
                val all = overview.active + overview.archived
                val byId = all.associateBy { it.id }
                fun summary(list: PriceList) = PriceListSummary(
                    list = list,
                    name = names.name(list),
                    ownPrices = prices[list.id]?.size ?: 0,
                    baseName = (list.pricing as? PriceListPricing.Relative)
                        ?.let { byId[it.baseId] }
                        ?.let(names::name),
                )
                state.update {
                    it.copy(
                        loading = false,
                        active = overview.active.map(::summary),
                        archived = overview.archived.map(::summary),
                    )
                }
            }
        }
        viewModelScope.launch {
            reads.guard {
                combine(
                    operations.lists(),
                    operations.ownPrices(),
                    operations.products(),
                    operations.pricing(),
                    selection,
                ) { overview, prices, products, pricing, (selectedId, query) ->
                    val loaded = Catalog(overview, prices, products, pricing)
                    Shown(loaded, selectedId, query, selectedId?.let { rowsOf(loaded, it, query) })
                }.flowOn(computation)
            }.collect { shown ->
                catalog = shown.catalog
                state.update {
                    // Una búsqueda más nueva ya está en camino: esta no se muestra.
                    if (it.selectedId != shown.selectedId || it.query != shown.query) {
                        it
                    } else {
                        it.copy(rows = shown.rows.orEmpty(), rowsLoading = false)
                    }
                }
            }
        }
        viewModelScope.launch {
            reads.guard { operations.customerCounts() }.collect { counts -> customers = counts }
        }
        viewModelScope.launch {
            reads.failed.collect { failed ->
                state.update { it.copy(readFailed = failed, loading = it.loading && !failed) }
            }
        }
    }

    fun onAction(action: PriceListsAction) {
        when (action) {
            // La pantalla vuelve atrás: aquí no cambia nada.
            PriceListsAction.Back -> Unit

            PriceListsAction.Retry -> {
                state.update { it.copy(loading = true) }
                reads.retry()
            }

            PriceListsAction.NewList -> listEditor.openNew(state.value.active)

            is PriceListsAction.Open -> select(action.listId, "")

            PriceListsAction.CloseDetail -> select(null, "")

            PriceListsAction.EditSelected ->
                state.value.selected
                    ?.takeUnless { it.list.isDefault }
                    ?.let { listEditor.openEdit(it.list, it.name, state.value.active) }

            PriceListsAction.AskArchiveSelected ->
                state.value.selected
                    ?.takeUnless { it.list.isDefault || it.list.isArchived }
                    ?.let { selected ->
                        val count = customers[selected.list.id] ?: 0
                        // Sin clientes no hay nada que advertir, y archivar se deshace con `Reactivar`.
                        if (count == 0) {
                            lifecycle { operations.archive(selected.list.id) }
                        } else {
                            state.update {
                                it.copy(
                                    dialog = PriceListsDialog.Archive(
                                        selected.list.id,
                                        selected.name,
                                        count,
                                    ),
                                )
                            }
                        }
                    }

            is PriceListsAction.Reactivate -> lifecycle { operations.reactivate(action.listId) }

            is PriceListsAction.Search -> select(state.value.selectedId, action.query)

            is PriceListsAction.EditPrice -> openPrice(action.productId)

            PriceListsAction.ConfirmDialog -> confirmDialog()

            PriceListsAction.DismissDialog -> state.update { it.copy(dialog = null) }

            PriceListsAction.MessageShown -> state.update { it.copy(message = null) }
        }
    }

    /** Abre el detalle de [listId], o lo cierra con `null`, y busca [query] en él. */
    private fun select(listId: Long?, query: String) {
        val sameList = state.value.selectedId == listId
        state.update {
            it.copy(
                selectedId = listId,
                query = query,
                rows = if (sameList) it.rows else emptyList(),
                rowsLoading = listId != null && !sameList,
            )
        }
        selection.value = listId to query
    }

    /** Abre la hoja del precio, solo en la inicial o en una fija vigente: una relativa se calcula. */
    private fun openPrice(productId: Long) {
        val selected = state.value.selected ?: return
        val list = selected.list
        if (list.isArchived || list.pricing is PriceListPricing.Relative) return
        val row = state.value.rows.firstOrNull { it.productId == productId } ?: return
        val product = catalog.products?.firstOrNull { it.id == productId } ?: return
        val context = catalog.pricing ?: return
        val hasOwn = !list.isDefault && catalog.prices[list.id]?.containsKey(productId) == true
        priceEditor.open(list.id, row, hasOwn, ProductPricing.of(product, context))
    }

    private fun confirmDialog() {
        val dialog = state.value.dialog ?: return
        state.update { it.copy(dialog = null) }
        when (dialog) {
            is PriceListsDialog.Archive -> lifecycle { operations.archive(dialog.listId) }
        }
    }

    private fun lifecycle(block: suspend () -> PriceListLifecycleResult) {
        viewModelScope.launch {
            val message =
                when (val result = block()) {
                    // La lista ya muestra el cambio: no hace falta un aviso.
                    PriceListLifecycleResult.Done, PriceListLifecycleResult.Unchanged,
                    PriceListLifecycleResult.NotFound,
                    -> null

                    PriceListLifecycleResult.NameTaken -> PriceListsMessage.NameTaken

                    // La pantalla no los ofrece; si llegan, cambió algo por debajo y no se hizo nada.
                    PriceListLifecycleResult.Protected, PriceListLifecycleResult.InUse ->
                        PriceListsMessage.Failed(outOfSpace = false)

                    is PriceListLifecycleResult.StorageFailed ->
                        PriceListsMessage.Failed(result.failure.isOutOfSpace)
                }
            if (message != null) show(message)
        }
    }

    private fun show(message: PriceListsMessage) {
        state.update { it.copy(message = message) }
    }

    /** Lo leído para armar el detalle: listas, precios propios, productos y lo del margen. */
    private data class Catalog(
        val overview: PriceListsOverview? = null,
        val prices: Map<Long, Map<Long, Money>> = emptyMap(),
        val products: List<Product>? = null,
        val pricing: PricingContext? = null,
    )

    /** Lo que se muestra del detalle: [rows] nulo sin lista abierta. */
    private class Shown(
        val catalog: Catalog,
        val selectedId: Long?,
        val query: String,
        val rows: List<ListProductRow>?,
    )

    private companion object {
        /**
         * Los productos de la lista [listId] con su precio en ella, filtrados por [query] sin
         * mayúsculas ni tildes, en el orden del catálogo.
         */
        fun rowsOf(catalog: Catalog, listId: Long, query: String): List<ListProductRow> {
            val overview = catalog.overview ?: return emptyList()
            val products = catalog.products ?: return emptyList()
            val context = catalog.pricing ?: return emptyList()
            val lists = (overview.active + overview.archived).associateBy { it.id }
            val wanted = TextNormalizer.normalize(query.trim())
            val byProduct = mutableMapOf<Long, MutableMap<Long, Money>>()
            catalog.prices.forEach { (list, ofList) ->
                ofList.forEach { (product, price) ->
                    byProduct.getOrPut(product) { mutableMapOf() }[list] = price
                }
            }
            return products
                .filter { wanted.isEmpty() || wanted in it.details.normalizedName }
                .map { product ->
                    val result = ListPriceRules.resolve(
                        listId,
                        lists,
                        product.salePrice,
                        byProduct[product.id].orEmpty(),
                    )
                    val priced = result as? ListPriceResult.Priced
                    val pricing = ProductPricing.of(product, context)
                    ListProductRow(
                        productId = product.id,
                        name = product.details.name,
                        salePrice = product.salePrice,
                        price = priced?.price,
                        origin = priced?.origin,
                        margin = priced?.price?.let(pricing::marginOf) ?: Margin.NoData,
                        hasCost = pricing.cost != null,
                    )
                }
        }
    }
}
