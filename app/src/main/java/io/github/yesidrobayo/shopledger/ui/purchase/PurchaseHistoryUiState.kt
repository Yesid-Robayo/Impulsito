package io.github.yesidrobayo.shopledger.ui.purchase

import androidx.compose.runtime.Immutable
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseHistoryRow
import io.github.yesidrobayo.shopledger.domain.model.purchase.SupplierFilter
import io.github.yesidrobayo.shopledger.ui.sale.HistoryPeriodKind
import kotlinx.datetime.DayOfWeek

/**
 * El estado del historial de compras: los filtros, las compras leídas hasta ahora agrupadas por día
 * y la compra abierta junto a la lista en una ventana ancha.
 */
@Immutable
data class PurchaseHistoryUiState(
    val filters: PurchaseFilters = PurchaseFilters(),
    val content: PurchaseHistoryContent = PurchaseHistoryContent.Loading,
    val today: BusinessDate? = null,
    val selectedPurchaseId: Long? = null,
    val supplierSheet: Boolean = false,
)

/**
 * Los filtros del historial. [period] es el período elegido, o `null` para todo el historial.
 * [supplier] es el proveedor elegido, `Sin proveedor` o cualquiera, y [suppliers] los que se pueden
 * elegir, con su nombre.
 */
@Immutable
data class PurchaseFilters(
    val period: ReportPeriod? = null,
    val supplier: SupplierFilter = SupplierFilter.Any,
    val onlyVoided: Boolean = false,
    val suppliers: List<SupplierChoice> = emptyList(),
    val weekStartDay: DayOfWeek? = null,
) {
    val isFiltered: Boolean
        get() = period != null || supplier != SupplierFilter.Any || onlyVoided

    val periodKind: HistoryPeriodKind?
        get() = when (period) {
            null -> null
            is ReportPeriod.Day -> HistoryPeriodKind.DAY
            is ReportPeriod.Week -> HistoryPeriodKind.WEEK
            is ReportPeriod.Month -> HistoryPeriodKind.MONTH
        }

    /** El nombre del proveedor del filtro, o `null` sin un proveedor concreto. */
    val supplierName: String?
        get() = (supplier as? SupplierFilter.Of)?.let { chosen ->
            suppliers.firstOrNull { it.id == chosen.supplierId }?.name
        }
}

/** Un proveedor que se puede elegir en el filtro. */
data class SupplierChoice(val id: Long, val name: String)

/** Lo que muestra la lista del historial. */
sealed interface PurchaseHistoryContent {
    /** Leyendo el primer tramo. El esqueleto solo aparece si tarda. */
    data object Loading : PurchaseHistoryContent

    /** Todavía no hay compras: se invita a registrar la primera. */
    data object Empty : PurchaseHistoryContent

    /** Hay compras, pero ninguna con estos filtros. */
    data object EmptyFiltered : PurchaseHistoryContent

    /** No se pudo leer. Se puede reintentar. */
    data object ReadError : PurchaseHistoryContent

    /**
     * Las compras leídas, por día, de la más nueva a la más antigua. Con [endReached] no hay más
     * que leer; si no, se lee el tramo siguiente al acercarse al final.
     */
    data class Days(val days: List<PurchaseDay>, val endReached: Boolean) : PurchaseHistoryContent
}

/** Un día del historial, con lo comprado ese día en compras activas: una anulada no suma. */
@Immutable
data class PurchaseDay(val date: BusinessDate, val total: Money, val rows: List<PurchaseHistoryRow>)

/** Lo que la persona hace en el historial de compras. */
sealed interface PurchaseHistoryAction {
    data class PeriodKindChange(val kind: HistoryPeriodKind?) : PurchaseHistoryAction

    data object PreviousPeriod : PurchaseHistoryAction

    data object NextPeriod : PurchaseHistoryAction

    data object OpenSuppliers : PurchaseHistoryAction

    data class SupplierChange(val supplier: SupplierFilter) : PurchaseHistoryAction

    data object CloseSuppliers : PurchaseHistoryAction

    data object ToggleVoided : PurchaseHistoryAction

    data object ClearFilters : PurchaseHistoryAction

    /** La lista se acercó al final de lo leído: toca leer el tramo siguiente. */
    data object LoadMore : PurchaseHistoryAction

    data object Retry : PurchaseHistoryAction

    data class OpenPurchase(val purchaseId: Long) : PurchaseHistoryAction

    data object ClosePurchase : PurchaseHistoryAction

    /** Ir a la compra nueva: lo resuelve la navegación. */
    data object NewPurchase : PurchaseHistoryAction

    /** Volver a las ventas, el otro segmento de Registros: lo resuelve la navegación. */
    data object ShowSales : PurchaseHistoryAction
}
