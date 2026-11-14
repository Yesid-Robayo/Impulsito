package io.github.yesidrobayo.shopledger.ui.reorder

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.Category
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.catalog.Supplier
import io.github.yesidrobayo.shopledger.domain.model.stock.Location
import io.github.yesidrobayo.shopledger.domain.rule.stock.ReorderSupplierFilter

/** Por qué la lista está vacía: sin ventas que medir, o con ventas y nada que reponer. */
enum class ReorderEmpty {
    NEEDS_SALES,
    NOTHING_TO_RESTOCK,
}

/**
 * Lo que muestra la reposición sugerida.
 *
 * [coverText] es lo escrito en `Cubrir`; mientras no sea un número de días válido, la lista sigue
 * con el último que lo fue, y [coverTooHigh] lo marca. [locations] solo trae algo con más de una
 * ubicación vigente: con una sola no hay selector.
 */
data class ReorderUiState(
    val loading: Boolean = true,
    val loadFailed: Boolean = false,
    val coverText: String = "",
    val coverTooHigh: Boolean = false,
    val supplierFilter: ReorderSupplierFilter = ReorderSupplierFilter.All,
    val categoryId: Long? = null,
    val locationId: Long? = null,
    val suppliers: List<Supplier> = emptyList(),
    val categories: List<Category> = emptyList(),
    val locations: List<Location> = emptyList(),
    val groups: List<ReorderGroupUi> = emptyList(),
    val empty: ReorderEmpty? = null,
)

/**
 * Un grupo de la lista: el proveedor habitual, o `Sin proveedor` con [supplierId] nulo.
 * [estimatedCost] es lo que costarían las cantidades de ahora, o `null` si la cuenta no cabe.
 */
data class ReorderGroupUi(
    val supplierId: Long?,
    val supplierName: String?,
    val lines: List<ReorderLineUi>,
    val estimatedCost: Money?,
) {
    /** La llave estable del grupo en la lista. */
    val key: String get() = supplierId?.let { "supplier-$it" } ?: "no-supplier"

    /** Lo que entra a la compra y a la lista compartida: lo que no va en cero. */
    val toBuy: List<ReorderLineUi> get() = lines.filter { it.quantity.milli > 0L }
}

/**
 * Una fila: el producto, lo que queda, cuánto alcanza y la cantidad a comprar, ya editable.
 *
 * [unit] es la unidad base. Con [purchaseUnit], la cantidad se muestra en esa unidad y en la base,
 * y cada paso suma o resta una de ellas, de [purchaseFactor] milésimas; sin ella, cada paso es una
 * unidad base. [daysLeft] es `null` sin velocidad que medir.
 */
data class ReorderLineUi(
    val productId: Long,
    val name: String,
    val unit: MeasureUnit?,
    val onHand: QuantityMilli,
    val daysLeft: Long?,
    val recentSales: Boolean,
    val soldOut: Boolean,
    val quantity: QuantityMilli,
    val purchaseUnit: MeasureUnit? = null,
    val purchaseFactor: Long? = null,
)

sealed interface ReorderAction {
    data object Back : ReorderAction

    data object Retry : ReorderAction

    data class CoverChange(val text: String) : ReorderAction

    data class SupplierFilterChange(val filter: ReorderSupplierFilter) : ReorderAction

    data class CategoryChange(val categoryId: Long?) : ReorderAction

    data class LocationChange(val locationId: Long?) : ReorderAction

    data object ManageLocations : ReorderAction

    data class Increment(val productId: Long) : ReorderAction

    data class Decrement(val productId: Long) : ReorderAction

    /** Comparte la lista del grupo de [supplierId], o la de `Sin proveedor` si es nulo. */
    data class Share(val supplierId: Long?) : ReorderAction

    /** Abre la compra del grupo de [supplierId], o una sin proveedor si es nulo. */
    data class CreatePurchase(val supplierId: Long?) : ReorderAction
}
