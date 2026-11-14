package io.github.yesidrobayo.shopledger.ui.catalog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.catalog.Category
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.document.CatalogDocument
import io.github.yesidrobayo.shopledger.domain.model.document.ReceiptHeader
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceList
import io.github.yesidrobayo.shopledger.domain.rule.document.CatalogBuilder
import io.github.yesidrobayo.shopledger.domain.rule.document.CatalogFilter
import io.github.yesidrobayo.shopledger.domain.rule.document.CatalogOptions
import io.github.yesidrobayo.shopledger.domain.rule.pricing.SalePriceList
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/**
 * Lo que el catálogo lee del negocio: productos vigentes, categorías, listas de precios con los
 * precios propios por lista y producto, el encabezado y la fecha de hoy. [currencyConfirmed] es
 * `false` mientras la moneda sigue sin elegir.
 */
data class CatalogSources(
    val products: List<Product>,
    val categories: List<Category>,
    val lists: List<PriceList>,
    val ownPrices: Map<Long, Map<Long, Money>>,
    val header: ReceiptHeader,
    val today: BusinessDate,
    val currencyConfirmed: Boolean,
)

/** Lo que la persona eligió. No se guarda: se elige cada vez. */
data class CatalogChoices(
    val filter: CatalogFilter = CatalogFilter.All,
    val listId: Long? = null,
    val showPhotos: Boolean = false,
    val groupByCategory: Boolean = false,
    val includeOutOfStock: Boolean = false,
    val title: String = "",
)

/** Lo que muestra el catálogo compartible. */
sealed interface CatalogUiState {
    data object Loading : CatalogUiState

    data object ReadFailed : CatalogUiState

    /** Ningún producto vigente tiene precio: no hay catálogo que armar. */
    data object NoPricedProducts : CatalogUiState

    /** La moneda sigue sin elegir: los precios no se pueden escribir con su símbolo. */
    data object CurrencyUnconfirmed : CatalogUiState

    /**
     * El catálogo con lo elegido. [lists] son las listas que se pueden elegir, y [listId] la
     * elegida. [hasPhotos] dice si algún producto tiene foto. [sharing] y [shareFailed] siguen la
     * creación del archivo.
     */
    data class Ready(
        val document: CatalogDocument,
        val choices: CatalogChoices,
        val categories: List<Category>,
        val lists: List<PriceList>,
        val listId: Long,
        val hasPhotos: Boolean,
        val sharing: CatalogOutput? = null,
        val shareFailed: Boolean = false,
    ) : CatalogUiState
}

/** Qué se hace con el PDF. */
enum class CatalogOutput {
    /** Compartirlo con el selector del sistema. */
    SHARE,

    /** Guardarlo donde la persona elija, con el selector de documentos. */
    SAVE,
}

/** Lo que la persona hace en el catálogo. */
sealed interface CatalogAction {
    data class Filter(val filter: CatalogFilter) : CatalogAction

    data class PriceListChosen(val listId: Long) : CatalogAction

    data class ShowPhotos(val on: Boolean) : CatalogAction

    data class GroupByCategory(val on: Boolean) : CatalogAction

    data class IncludeOutOfStock(val on: Boolean) : CatalogAction

    data class TitleChanged(val title: String) : CatalogAction

    data class Create(val output: CatalogOutput) : CatalogAction

    /** La ruta terminó de crear el archivo, o no pudo. */
    data class Created(val ok: Boolean) : CatalogAction

    data object Retry : CatalogAction

    data object Back : CatalogAction

    data object OpenProducts : CatalogAction

    data object ConfirmCurrency : CatalogAction
}

/**
 * El catálogo compartible: los productos con precio, filtrados y ordenados como pidió la persona,
 * listos para el PDF. Sale de [CatalogBuilder], que no tiene costo, margen, ganancia ni stock.
 *
 * Observa los productos y los precios: un cambio rehace el catálogo. Crear el archivo lo hace la
 * ruta; aquí solo se anota qué se pidió y cómo terminó, de a uno.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CatalogViewModel(
    private val observeSources: () -> Flow<CatalogSources>,
    computation: CoroutineDispatcher,
) : ViewModel() {
    private val attempts = MutableStateFlow(0)
    private val choices = MutableStateFlow(CatalogChoices())
    private val output = MutableStateFlow(Output())

    private val content: Flow<CatalogUiState> = attempts.flatMapLatest {
        combine(observeSources(), choices) { sources, chosen -> stateOf(sources, chosen) }
            .flowOn(computation)
            // Una falla de lectura no cierra la pantalla: muestra el error y deja reintentar.
            .catch { emit(CatalogUiState.ReadFailed) }
    }

    val uiState: StateFlow<CatalogUiState> =
        combine(content, output) { state, shown ->
            if (state is CatalogUiState.Ready) {
                state.copy(sharing = shown.working, shareFailed = shown.failed)
            } else {
                state
            }
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            CatalogUiState.Loading,
        )

    fun onAction(action: CatalogAction) {
        when (action) {
            is CatalogAction.Filter -> choices.update { it.copy(filter = action.filter) }

            is CatalogAction.PriceListChosen -> choices.update { it.copy(listId = action.listId) }

            is CatalogAction.ShowPhotos -> choices.update { it.copy(showPhotos = action.on) }

            is CatalogAction.GroupByCategory ->
                choices.update { it.copy(groupByCategory = action.on) }

            is CatalogAction.IncludeOutOfStock ->
                choices.update { it.copy(includeOutOfStock = action.on) }

            is CatalogAction.TitleChanged -> choices.update { it.copy(title = action.title) }

            is CatalogAction.Create -> output.update {
                // Un archivo a la vez: un segundo toque no crea otro.
                if (it.working != null) it else Output(working = action.output)
            }

            is CatalogAction.Created -> output.update { Output(failed = !action.ok) }

            CatalogAction.Retry -> attempts.update { it + 1 }

            // Los resuelve la ruta: navegan.
            CatalogAction.Back, CatalogAction.OpenProducts, CatalogAction.ConfirmCurrency -> Unit
        }
    }

    private fun stateOf(sources: CatalogSources, chosen: CatalogChoices): CatalogUiState {
        if (!sources.currencyConfirmed) return CatalogUiState.CurrencyUnconfirmed
        val lists = sources.lists.filter { it.archivedAt == null }
        val default = lists.firstOrNull { it.isDefault } ?: lists.firstOrNull()
            ?: return CatalogUiState.NoPricedProducts
        val listId = chosen.listId?.takeIf { id -> lists.any { it.id == id } } ?: default.id
        val prices = SalePriceList(listId, sources.lists.associateBy { it.id }, sources.ownPrices)
        val everything = CatalogBuilder.of(
            sources.products,
            sources.categories,
            CatalogOptions(CatalogFilter.All, prices, false, false, true, ""),
            sources.header,
            sources.today,
        )
        if (everything.productCount == 0) return CatalogUiState.NoPricedProducts
        val document = CatalogBuilder.of(
            sources.products,
            sources.categories,
            CatalogOptions(
                filter = chosen.filter,
                prices = prices,
                showPhotos = chosen.showPhotos,
                groupByCategory = chosen.groupByCategory,
                includeOutOfStock = chosen.includeOutOfStock,
                title = chosen.title,
            ),
            sources.header,
            sources.today,
        )
        return CatalogUiState.Ready(
            document = document,
            choices = chosen,
            categories = sources.categories.sortedBy { it.details.normalizedName },
            lists = lists,
            listId = listId,
            // Un insumo no va en el catálogo: su foto no cuenta.
            hasPhotos = sources.products.any {
                !it.isArchived && it.details.usage.isSold && it.details.photoMediaId != null
            },
        )
    }

    private data class Output(val working: CatalogOutput? = null, val failed: Boolean = false)

    private companion object {
        /** Lo que sigue viva la lectura al rotar la pantalla. */
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
