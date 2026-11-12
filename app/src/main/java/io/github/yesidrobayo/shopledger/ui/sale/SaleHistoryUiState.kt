package io.github.yesidrobayo.shopledger.ui.sale

import androidx.compose.runtime.Immutable
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleHistoryRow
import kotlinx.datetime.DayOfWeek

/**
 * El estado del historial de ventas: los filtros, las ventas leídas hasta ahora agrupadas por día y
 * la venta abierta junto a la lista en una ventana ancha.
 */
@Immutable
data class SaleHistoryUiState(
    val filters: HistoryFilters = HistoryFilters(),
    val content: HistoryContent = HistoryContent.Loading,
    val today: BusinessDate? = null,
    val zone: BusinessTimeZone? = null,
    val selectedSaleId: Long? = null,
    val methodSheet: Boolean = false,
)

/** El tipo de período del filtro: sin límite, o un día, una semana o un mes del negocio. */
enum class HistoryPeriodKind {
    DAY,
    WEEK,
    MONTH,
}

/**
 * Los filtros del historial. [period] es el período elegido, o `null` para todo el historial: sin
 * límite de años. [methods] son los métodos que se pueden elegir, también los archivados.
 */
@Immutable
data class HistoryFilters(
    val period: ReportPeriod? = null,
    val methodId: Long? = null,
    val onlyVoided: Boolean = false,
    val methods: List<SaleMethod> = emptyList(),
    val weekStartDay: DayOfWeek? = null,
) {
    val isFiltered: Boolean get() = period != null || methodId != null || onlyVoided

    val periodKind: HistoryPeriodKind?
        get() = when (period) {
            null -> null
            is ReportPeriod.Day -> HistoryPeriodKind.DAY
            is ReportPeriod.Week -> HistoryPeriodKind.WEEK
            is ReportPeriod.Month -> HistoryPeriodKind.MONTH
        }
}

/** Lo que muestra la lista del historial. */
sealed interface HistoryContent {
    /** Leyendo el primer tramo. El esqueleto solo aparece si tarda. */
    data object Loading : HistoryContent

    /** Todavía no hay ventas: se invita a registrar la primera. */
    data object Empty : HistoryContent

    /** Hay ventas, pero ninguna con estos filtros. */
    data object EmptyFiltered : HistoryContent

    /** No se pudo leer. Se puede reintentar. */
    data object ReadError : HistoryContent

    /**
     * Las ventas leídas, por día, de la más nueva a la más antigua. Con [endReached] no hay más que
     * leer; si no, se lee el tramo siguiente al acercarse al final.
     */
    data class Days(val days: List<HistoryDay>, val endReached: Boolean) : HistoryContent
}

/**
 * Un día del historial, con lo vendido ese día en ventas activas: una anulada no suma. [total] falta
 * mientras se lee.
 */
@Immutable
data class HistoryDay(val date: BusinessDate, val total: Money?, val rows: List<SaleHistoryRow>)

/** Lo que la persona hace en el historial. */
sealed interface SaleHistoryAction {
    data class PeriodKindChange(val kind: HistoryPeriodKind?) : SaleHistoryAction

    data object PreviousPeriod : SaleHistoryAction

    data object NextPeriod : SaleHistoryAction

    data object OpenMethods : SaleHistoryAction

    data class MethodChange(val methodId: Long?) : SaleHistoryAction

    data object CloseMethods : SaleHistoryAction

    data object ToggleVoided : SaleHistoryAction

    data object ClearFilters : SaleHistoryAction

    /** La lista se acercó al final de lo leído: toca leer el tramo siguiente. */
    data object LoadMore : SaleHistoryAction

    data object Retry : SaleHistoryAction

    data class OpenSale(val saleId: Long) : SaleHistoryAction

    data object CloseSale : SaleHistoryAction

    data object Sell : SaleHistoryAction

    /** Ir al segmento `Compras` de Registros: lo resuelve la navegación. */
    data object ShowPurchases : SaleHistoryAction

    /** Ir al segmento `Gastos` de Registros: lo resuelve la navegación. */
    data object ShowExpenses : SaleHistoryAction
}
