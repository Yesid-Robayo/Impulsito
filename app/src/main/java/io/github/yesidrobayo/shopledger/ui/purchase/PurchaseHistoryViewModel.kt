package io.github.yesidrobayo.shopledger.ui.purchase

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseHistoryCursor
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseHistoryFilter
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseHistoryRow
import io.github.yesidrobayo.shopledger.domain.model.purchase.SupplierFilter
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendar
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.PurchaseHistoryReader
import io.github.yesidrobayo.shopledger.ui.components.next
import io.github.yesidrobayo.shopledger.ui.components.previous
import io.github.yesidrobayo.shopledger.ui.sale.HistoryPeriodKind
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * El historial de compras, leído por tramos: sin límite de años, la lista nunca carga todo. Cada
 * tramo trae [PAGE_SIZE] compras, y el siguiente se pide al acercarse al final de lo leído.
 *
 * - Las compras van de la más nueva a la más antigua, agrupadas por fecha de negocio. Cada día lleva
 *   lo comprado en compras activas: una anulada se ve, pero no suma.
 * - Cambiar un filtro vuelve a leer desde el principio.
 * - Una compra nueva o anulada en otra pantalla se ve sola: el historial vuelve a leer lo que ya
 *   tenía, sin perder el lugar.
 *
 * @param suppliers los proveedores que se pueden elegir en el filtro, con su nombre.
 * @param calendar el calendario del negocio, para los períodos.
 * @param initialSupplier el proveedor con que se abrió, como desde su detalle: la lista ya filtrada.
 */
class PurchaseHistoryViewModel(
    private val reader: PurchaseHistoryReader,
    private val suppliers: Flow<List<SupplierChoice>>,
    private val calendar: Flow<BusinessCalendarResult>,
    private val computation: CoroutineDispatcher,
    initialSupplier: SupplierFilter = SupplierFilter.Any,
    private val openCredit: Flow<Set<Long>> = emptyFlow(),
) : ViewModel() {
    private val state = MutableStateFlow(
        PurchaseHistoryUiState(filters = PurchaseFilters(supplier = initialSupplier)),
    )
    val uiState: StateFlow<PurchaseHistoryUiState> = state.asStateFlow()

    private var businessCalendar: BusinessCalendar? = null
    private var rows: List<PurchaseHistoryRow> = emptyList()
    private var totals: Map<BusinessDate, Money> = emptyMap()
    private var endReached = false
    private var loading: Job? = null

    init {
        // Los filtros, el calendario y los avisos de cambios no cierran la pantalla si fallan: sin
        // ellos falta un filtro o la fecha de hoy, y la lista lo dice con su propio error.
        viewModelScope.launch {
            suppliers.catch {}.collect { list ->
                state.update { it.copy(filters = it.filters.copy(suppliers = list)) }
            }
        }
        viewModelScope.launch {
            calendar.catch {}.collect { result ->
                val ready = when (result) {
                    is BusinessCalendarResult.Ready -> result.calendar
                    is BusinessCalendarResult.UnknownZone -> null
                }
                businessCalendar = ready
                state.update {
                    it.copy(
                        filters = it.filters.copy(weekStartDay = ready?.weekStartDay),
                        today = ready?.businessDateOf(reader.now()),
                    )
                }
            }
        }
        // Qué compras a plazo siguen abiertas cambia con cada pago: la etiqueta de la fila lo sigue.
        viewModelScope.launch {
            openCredit.catch {}.collect { open -> state.update { it.copy(openCredit = open) } }
        }
        // Cada compra nueva o anulada vuelve a leer lo que ya se mostraba.
        viewModelScope.launch { reader.changes().catch {}.collect { reload(keep = rows.size) } }
    }

    fun onAction(action: PurchaseHistoryAction) {
        when (action) {
            is PurchaseHistoryAction.PeriodKindChange -> {
                state.update { it.copy(periodSheet = false) }
                changeFilters { it.copy(period = periodOf(action.kind)) }
            }

            PurchaseHistoryAction.PreviousPeriod -> movePeriod(forward = false)

            PurchaseHistoryAction.NextPeriod -> movePeriod(forward = true)

            PurchaseHistoryAction.OpenPeriods -> state.update { it.copy(periodSheet = true) }

            PurchaseHistoryAction.ClosePeriods -> state.update { it.copy(periodSheet = false) }

            PurchaseHistoryAction.OpenFilters -> state.update { it.copy(filterSheet = true) }

            // La hoja sigue abierta: la lista de detrás cambia al instante, y se puede elegir más.
            is PurchaseHistoryAction.SupplierChange ->
                changeFilters { it.copy(supplier = action.supplier) }

            PurchaseHistoryAction.CloseFilters -> state.update { it.copy(filterSheet = false) }

            PurchaseHistoryAction.ClearSheetFilters -> changeFilters {
                it.copy(supplier = SupplierFilter.Any, onlyVoided = false)
            }

            PurchaseHistoryAction.ToggleVoided ->
                changeFilters { it.copy(onlyVoided = !it.onlyVoided) }

            PurchaseHistoryAction.ClearFilters -> changeFilters {
                it.copy(period = null, supplier = SupplierFilter.Any, onlyVoided = false)
            }

            PurchaseHistoryAction.LoadMore -> loadMore()

            PurchaseHistoryAction.Retry -> reload(keep = 0)

            is PurchaseHistoryAction.OpenPurchase -> state.update {
                it.copy(selectedPurchaseId = action.purchaseId)
            }

            PurchaseHistoryAction.ClosePurchase -> state.update {
                it.copy(selectedPurchaseId = null)
            }

            // Comprar y volver a las ventas los resuelve la navegación.
            PurchaseHistoryAction.NewPurchase,
            PurchaseHistoryAction.ShowSales,
            PurchaseHistoryAction.ShowExpenses,
            -> Unit
        }
    }

    private fun periodOf(kind: HistoryPeriodKind?) = businessCalendar?.let { cal ->
        val today = cal.businessDateOf(reader.now())
        when (kind) {
            null -> null
            HistoryPeriodKind.DAY -> cal.day(today)
            HistoryPeriodKind.WEEK -> cal.week(today)
            HistoryPeriodKind.MONTH -> cal.month(today)
        }
    }

    private fun movePeriod(forward: Boolean) {
        val cal = businessCalendar ?: return
        changeFilters { filters ->
            val period = filters.period ?: return@changeFilters filters
            filters.copy(period = if (forward) cal.next(period) else cal.previous(period))
        }
    }

    private fun changeFilters(change: (PurchaseFilters) -> PurchaseFilters) {
        val before = state.value.filters
        val after = change(before)
        if (after == before) return
        state.update { it.copy(filters = after) }
        reload(keep = 0)
    }

    /**
     * Vuelve a leer desde el principio al menos [keep] compras: las que ya se veían, para no perder
     * el lugar, o un tramo si no había ninguna.
     */
    private fun reload(keep: Int) {
        loading?.cancel()
        val filter = filterOf(state.value.filters)
        loading = viewModelScope.launch {
            read(filter, after = null, limit = maxOf(keep, PAGE_SIZE), replace = true)
        }
    }

    private fun loadMore() {
        if (endReached || loading?.isActive == true || rows.isEmpty()) return
        val filter = filterOf(state.value.filters)
        val after = rows.last().cursor
        // Tras un tramo que falló, el pie vuelve a esperar mientras se reintenta.
        state.update { it.copy(content = contentOf(filter)) }
        loading = viewModelScope.launch { read(filter, after, PAGE_SIZE, replace = false) }
    }

    private suspend fun read(
        filter: PurchaseHistoryFilter,
        after: PurchaseHistoryCursor?,
        limit: Int,
        replace: Boolean,
    ) {
        try {
            val page = withContext(computation) { reader.page(filter, after, limit) }
            val dates = page.map { it.businessDate }.toSet()
            val dayTotals = withContext(computation) { reader.dayTotals(filter, dates) }
            rows = if (replace) page else rows + page
            totals = if (replace) dayTotals else totals + dayTotals
            endReached = page.size < limit
            state.update { it.copy(content = contentOf(filter)) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // Una falla de lectura no cierra la pantalla: muestra el error y deja reintentar.
            // El tramo siguiente que falla deja lo leído y ofrece reintentar en el pie de la lista.
            state.update {
                it.copy(
                    content = if (replace) {
                        PurchaseHistoryContent.ReadError
                    } else {
                        contentOf(
                            filter,
                            moreFailed = true,
                        )
                    },
                )
            }
        }
    }

    private fun contentOf(
        filter: PurchaseHistoryFilter,
        moreFailed: Boolean = false,
    ): PurchaseHistoryContent {
        if (rows.isEmpty()) {
            return if (filter.isFiltered) {
                PurchaseHistoryContent.EmptyFiltered
            } else {
                PurchaseHistoryContent.Empty
            }
        }
        val days = rows.groupBy { it.businessDate }.map { (date, dayRows) ->
            // Un día sin compras activas suma cero, como lo dice su encabezado.
            PurchaseDay(date, totals[date] ?: Money.ZERO, dayRows)
        }
        return PurchaseHistoryContent.Days(days, endReached, moreFailed)
    }

    private fun filterOf(filters: PurchaseFilters) = PurchaseHistoryFilter(
        from = filters.period?.start,
        to = filters.period?.end,
        supplier = filters.supplier,
        onlyVoided = filters.onlyVoided,
    )

    companion object {
        /** Cuántas compras trae cada tramo: varias pantallas llenas, sin pedir demasiado. */
        const val PAGE_SIZE = 50
    }
}
