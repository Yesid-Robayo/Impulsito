package io.github.yesidrobayo.shopledger.ui.pricing

import androidx.compose.runtime.Immutable
import io.github.yesidrobayo.shopledger.domain.model.Margin
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceList
import io.github.yesidrobayo.shopledger.domain.rule.pricing.ListPriceOrigin

/**
 * Una lista como la muestra su fila: su nombre ya traducido si es la inicial, cuántos productos tienen
 * precio propio en ella y, si es relativa, el nombre de su base.
 */
@Immutable
data class PriceListSummary(
    val list: PriceList,
    val name: String,
    val ownPrices: Int,
    val baseName: String?,
)

/**
 * Un producto en el detalle de una lista: su precio en ella y de dónde sale. [price] nulo es un
 * precio que no se puede calcular, como uno que el ajuste lleva sobre el tope; entonces [origin]
 * también es nulo. [margin] se mide contra el costo del producto, y [hasCost] dice si lo tiene.
 */
@Immutable
data class ListProductRow(
    val productId: Long,
    val name: String,
    val salePrice: Money,
    val price: Money?,
    val origin: ListPriceOrigin?,
    val margin: Margin,
    val hasCost: Boolean,
)

/** Lo que se confirma antes de hacerlo. */
sealed interface PriceListsDialog {
    /** Archivar [listId]: [customers] clientes la tienen y volverán a la inicial. */
    data class Archive(val listId: Long, val name: String, val customers: Int) : PriceListsDialog
}

/** Un aviso breve, abajo, tras una acción. */
sealed interface PriceListsMessage {
    data object ListSaved : PriceListsMessage

    data object PriceSaved : PriceListsMessage

    /** Reactivar no se pudo: una vigente ya usa su nombre. */
    data object NameTaken : PriceListsMessage

    data class Failed(val outOfSpace: Boolean) : PriceListsMessage
}

/**
 * El estado de la pantalla de listas de precios.
 *
 * [loading] hasta que llegan las listas, y [readFailed] si una lectura falló: se dice, con
 * `Reintentar`. [active] y [archived] van en el orden en que se crearon, la inicial primero.
 * [selectedId] es la lista abierta en el detalle, y [rows] sus productos, ya filtrados por [query].
 * Las hojas abiertas llegan aparte, con su propio estado.
 */
@Immutable
data class PriceListsUiState(
    val loading: Boolean = true,
    val readFailed: Boolean = false,
    val active: List<PriceListSummary> = emptyList(),
    val archived: List<PriceListSummary> = emptyList(),
    val selectedId: Long? = null,
    val query: String = "",
    val rows: List<ListProductRow> = emptyList(),
    val rowsLoading: Boolean = true,
    val dialog: PriceListsDialog? = null,
    val message: PriceListsMessage? = null,
) {
    /** La lista abierta en el detalle, si sigue existiendo. */
    val selected: PriceListSummary?
        get() = selectedId?.let { id -> (active + archived).firstOrNull { it.list.id == id } }

    /** Solo existe la inicial: la lista invita a crear la primera. */
    val onlyDefault: Boolean get() = archived.isEmpty() && active.all { it.list.isDefault }
}

/** Lo que la persona hace en la pantalla, fuera de las hojas. */
sealed interface PriceListsAction {
    data object Back : PriceListsAction

    data object Retry : PriceListsAction

    data object NewList : PriceListsAction

    data class Open(val listId: Long) : PriceListsAction

    /** Cierra el detalle y vuelve a la lista de listas. */
    data object CloseDetail : PriceListsAction

    data object EditSelected : PriceListsAction

    data object AskArchiveSelected : PriceListsAction

    data class Reactivate(val listId: Long) : PriceListsAction

    data class Search(val query: String) : PriceListsAction

    data class EditPrice(val productId: Long) : PriceListsAction

    data object ConfirmDialog : PriceListsAction

    data object DismissDialog : PriceListsAction

    data object MessageShown : PriceListsAction
}
