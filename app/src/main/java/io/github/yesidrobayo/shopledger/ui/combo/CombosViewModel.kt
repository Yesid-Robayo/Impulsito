package io.github.yesidrobayo.shopledger.ui.combo

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yesidrobayo.shopledger.domain.model.Margin
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.rule.MarginCalculator
import io.github.yesidrobayo.shopledger.domain.rule.catalog.ProductSearch
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ComboSummary
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
 * Lo que muestra la lista de combos.
 *
 * @param loading la lista todavía no llega: la pantalla dibuja un esqueleto si tarda.
 * @param loadFailed la lectura falló: se dice, con `Reintentar`.
 * @param hasCombos hay algún combo, aunque la búsqueda no encuentre ninguno.
 * @param items los combos que coinciden con [query], por nombre.
 */
@Immutable
data class CombosUiState(
    val loading: Boolean = true,
    val loadFailed: Boolean = false,
    val query: String = "",
    val hasCombos: Boolean = false,
    val items: List<ComboItemUi> = emptyList(),
)

/**
 * Un combo en la lista: su nombre, cuántos componentes lleva, cuántos alcanzan, su precio y su
 * margen con el costo de hoy. [available] es `null` si ningún componente lleva stock.
 */
@Immutable
data class ComboItemUi(
    val id: Long,
    val name: String,
    val componentCount: Int,
    val available: Long?,
    val price: Money,
    val margin: Margin,
    val hasCost: Boolean,
)

/** Lo que la persona hace en la lista de combos. */
sealed interface CombosAction {
    data class QueryChange(val query: String) : CombosAction

    data class Open(val comboId: Long) : CombosAction

    data object NewCombo : CombosAction

    data object Retry : CombosAction

    data object Back : CombosAction
}

/**
 * La lista de combos y recetas: los combos vigentes por nombre, con lo que alcanza y el margen de
 * cada uno, y una búsqueda. Abrir un combo y crear uno los resuelve la navegación.
 *
 * @param combos los combos con sus cifras; se vuelve a pedir al reintentar.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CombosViewModel(
    private val combos: () -> Flow<List<ComboSummary>>,
    computation: CoroutineDispatcher,
) : ViewModel() {
    private val query = MutableStateFlow("")
    private val attempts = MutableStateFlow(0)

    private val loaded: Flow<List<ComboSummary>?> = attempts.flatMapLatest {
        combos()
            .map<List<ComboSummary>, List<ComboSummary>?> { it }
            // Una falla de lectura no cierra la lista: muestra el error y deja reintentar.
            .catch { emit(null) }
    }

    val uiState: StateFlow<CombosUiState> = combine(loaded, query) { all, text ->
        if (all == null) {
            CombosUiState(loading = false, loadFailed = true, query = text)
        } else {
            val byProduct = all.associateBy { it.product.id }
            val shown = ProductSearch.filter(all.map { it.product }, text)
            CombosUiState(
                loading = false,
                query = text,
                hasCombos = all.isNotEmpty(),
                items = shown.map { itemOf(byProduct.getValue(it.id)) },
            )
        }
    }.flowOn(computation)
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            CombosUiState(),
        )

    fun onAction(action: CombosAction) {
        when (action) {
            is CombosAction.QueryChange -> query.value = action.query

            CombosAction.Retry -> attempts.update { it + 1 }

            // Abrir, crear y volver los resuelve la navegación.
            is CombosAction.Open, CombosAction.NewCombo, CombosAction.Back -> Unit
        }
    }

    private fun itemOf(summary: ComboSummary): ComboItemUi {
        val price = summary.product.salePrice
        val cost = summary.figures.unitCost
        return ComboItemUi(
            id = summary.product.id,
            name = summary.product.details.name,
            componentCount = summary.componentCount,
            available = summary.figures.availability?.units,
            price = price,
            margin = cost?.let { MarginCalculator.margin(price, it) } ?: Margin.NoData,
            hasCost = cost != null && cost > Money.ZERO && !summary.figures.hasUncostedLeaf,
        )
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
