package io.github.yesidrobayo.shopledger.ui.extra

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.rule.catalog.ModifierRules
import io.github.yesidrobayo.shopledger.domain.rule.catalog.ProductSearch
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ModifierEditorData
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveModifiersResult
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
import kotlinx.coroutines.launch

/**
 * Lo que muestra la pantalla de extras de un producto.
 *
 * @param loading la lectura todavía no llega: la pantalla dibuja un esqueleto si tarda.
 * @param missing el producto ya no existe.
 * @param loadFailed la lectura falló: se dice, con `Reintentar`.
 * @param productName el producto cuyos extras se eligen, en la barra de arriba.
 * @param extras los extras guardados, en el orden en que se ofrecen al vender.
 * @param picker la hoja para agregar uno, abierta, o `null`.
 * @param saving un cambio se está guardando: no se acepta otro hasta que termine.
 * @param saveFailed el último cambio no se guardó; los extras siguen como estaban.
 */
@Immutable
data class ExtrasUiState(
    val loading: Boolean = true,
    val missing: Boolean = false,
    val loadFailed: Boolean = false,
    val productName: String = "",
    val extras: List<ExtraRowUi> = emptyList(),
    val picker: ExtraPicker? = null,
    val saving: Boolean = false,
    val saveFailed: Boolean = false,
) {
    /** Si todavía cabe otro extra. */
    val canAdd: Boolean get() = extras.size < ModifierRules.MAX_MODIFIERS
}

/**
 * Un extra guardado: su nombre y su precio de venta, que es lo que cobra su línea. Uno archivado
 * lleva su marca: al vender ya no se ofrece, y aquí se puede quitar.
 */
@Immutable
data class ExtraRowUi(
    val productId: Long,
    val name: String,
    val price: Money,
    val archived: Boolean,
)

/** La hoja para agregar un extra: lo escrito y los productos que coinciden, por nombre. */
@Immutable
data class ExtraPicker(val query: String, val options: List<ExtraOption>)

/** Un producto que se puede agregar como extra. */
@Immutable
data class ExtraOption(val id: Long, val name: String)

/** Lo que la persona hace en la pantalla de extras. */
sealed interface ExtrasAction {
    data object OpenPicker : ExtrasAction

    data object ClosePicker : ExtrasAction

    data class QueryChange(val query: String) : ExtrasAction

    data class Add(val productId: Long) : ExtrasAction

    data class Remove(val productId: Long) : ExtrasAction

    /** Vuelve a leer si la lectura falló. */
    data object Retry : ExtrasAction

    data object Back : ExtrasAction
}

/**
 * Los extras de un producto: los que se ofrecen al venderlo, en su orden, con `Agregar extra` y
 * `Quitar`. Cada cambio se guarda al momento, entero y en una sola transacción, así que no hay nada
 * que descartar al salir. Si un cambio falla, los extras siguen como estaban y se dice.
 *
 * @param editorSource el producto, sus extras y lo que se puede agregar; se vuelve a pedir al
 *   reintentar.
 * @param save guarda la lista entera de extras, en su orden.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExtrasViewModel(
    private val editorSource: () -> Flow<ModifierEditorData?>,
    private val save: suspend (List<Long>) -> SaveModifiersResult,
    computation: CoroutineDispatcher,
) : ViewModel() {
    private val attempts = MutableStateFlow(0)
    private val overlay = MutableStateFlow(Overlay())

    private val loaded: Flow<Loaded> = attempts.flatMapLatest {
        editorSource()
            .map<ModifierEditorData?, Loaded> { data ->
                if (data == null) Loaded.Missing else Loaded.Ready(data)
            }
            // Una falla de lectura no cierra la pantalla: muestra el error y deja reintentar.
            .catch { emit(Loaded.Failed) }
    }

    val uiState: StateFlow<ExtrasUiState> = combine(loaded, overlay) { load, shown ->
        when (load) {
            Loaded.Missing -> ExtrasUiState(loading = false, missing = true)
            Loaded.Failed -> ExtrasUiState(loading = false, loadFailed = true)
            is Loaded.Ready -> stateOf(load.data, shown)
        }
    }.flowOn(computation)
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            ExtrasUiState(),
        )

    fun onAction(action: ExtrasAction) {
        when (action) {
            ExtrasAction.OpenPicker -> overlay.update { it.copy(query = "", picking = true) }

            ExtrasAction.ClosePicker -> overlay.update { it.copy(picking = false) }

            is ExtrasAction.QueryChange -> overlay.update { it.copy(query = action.query) }

            is ExtrasAction.Add -> {
                overlay.update { it.copy(picking = false) }
                change { it + action.productId }
            }

            is ExtrasAction.Remove -> change { ids -> ids.filterNot { it == action.productId } }

            ExtrasAction.Retry -> attempts.update { it + 1 }

            // Volver lo resuelve la navegación.
            ExtrasAction.Back -> Unit
        }
    }

    /** Guarda los extras de ahora cambiados por [edit]. Un cambio a la vez. */
    private fun change(edit: (List<Long>) -> List<Long>) {
        val current = (uiState.value.takeIf { !it.loading && !it.saving } ?: return).extras
        overlay.update { it.copy(saving = true, saveFailed = false) }
        viewModelScope.launch {
            val saved = when (save(edit(current.map { it.productId }))) {
                SaveModifiersResult.Saved -> true

                // El producto o un extra cambiaron mientras se elegía: la lista se vuelve a leer
                // sola, y lo que no se guardó se dice igual que una falla.
                SaveModifiersResult.NotFound,
                is SaveModifiersResult.Invalid,
                is SaveModifiersResult.StorageFailed,
                -> false
            }
            overlay.update { it.copy(saving = false, saveFailed = !saved) }
        }
    }

    private fun stateOf(data: ModifierEditorData, shown: Overlay): ExtrasUiState {
        val extras = data.extras.map {
            ExtraRowUi(it.id, it.details.name, it.salePrice, it.isArchived)
        }
        val picker = if (shown.picking) {
            ExtraPicker(
                query = shown.query,
                options = ProductSearch.filter(data.candidates, shown.query)
                    .map { ExtraOption(it.id, it.details.name) },
            )
        } else {
            null
        }
        return ExtrasUiState(
            loading = false,
            productName = data.product.details.name,
            extras = extras,
            picker = picker,
            saving = shown.saving,
            saveFailed = shown.saveFailed,
        )
    }

    /** Lo que llegó de la lectura. */
    private sealed interface Loaded {
        data class Ready(val data: ModifierEditorData) : Loaded

        data object Missing : Loaded

        data object Failed : Loaded
    }

    /** Lo que está abierto encima de la lista, y cómo va el último cambio. */
    private data class Overlay(
        val picking: Boolean = false,
        val query: String = "",
        val saving: Boolean = false,
        val saveFailed: Boolean = false,
    )

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
