package io.github.yesidrobayo.shopledger.ui.sale

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleHistoryCursor
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleHistoryFilter
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleHistoryRow
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendar
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import io.github.yesidrobayo.shopledger.domain.usecase.sale.SaleHistoryReader
import io.github.yesidrobayo.shopledger.ui.components.next
import io.github.yesidrobayo.shopledger.ui.components.previous
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * El historial de ventas, leído por tramos: sin límite de años, la lista nunca carga todo. Cada
 * tramo trae [PAGE_SIZE] ventas, y el siguiente se pide al acercarse al final de lo leído.
 *
 * - Las ventas van de la más nueva a la más antigua, agrupadas por fecha de negocio. Cada día lleva
 *   lo vendido en ventas activas: una anulada se ve, pero no suma.
 * - Cambiar un filtro vuelve a leer desde el principio.
 * - Una venta nueva o anulada en otra pantalla se ve sola: el historial vuelve a leer lo que ya
 *   tenía, sin perder el lugar.
 *
 * @param methods los métodos que se pueden elegir en el filtro, con los archivados.
 * @param calendar el calendario del negocio, para los períodos y la hora de cada venta.
 */
class SaleHistoryViewModel(
    private val reader: SaleHistoryReader,
    private val methods: Flow<List<SaleMethod>>,
    private val calendar: Flow<BusinessCalendarResult>,
    private val computation: CoroutineDispatcher,
) : ViewModel() {
    private val state = MutableStateFlow(SaleHistoryUiState())
    val uiState: StateFlow<SaleHistoryUiState> = state.asStateFlow()

    private var businessCalendar: BusinessCalendar? = null
    private var rows: List<SaleHistoryRow> = emptyList()
    private var totals: Map<BusinessDate, Money> = emptyMap()
    private var endReached = false
    private var loading: Job? = null

    init {
        // Los filtros, el calendario y los avisos de cambios no cierran la pantalla si fallan: sin
        // ellos falta un filtro o la fecha de hoy, y la lista lo dice con su propio error.
        viewModelScope.launch {
            methods.catch {}.collect { list ->
                state.update { it.copy(filters = it.filters.copy(methods = list)) }
            }
        }
        viewModelScope.launch {
            calendar.catch {}.collect { result ->
                val ready = (result as? BusinessCalendarResult.Ready)?.calendar
                businessCalendar = ready
                state.update {
                    it.copy(
                        filters = it.filters.copy(weekStartDay = ready?.weekStartDay),
                        today = ready?.let { cal -> cal.businessDateOf(reader.now()) },
                        zone = ready?.zone,
                    )
                }
            }
        }
        // Cada venta nueva o anulada vuelve a leer lo que ya se mostraba.
        viewModelScope.launch { reader.changes().catch {}.collect { reload(keep = rows.size) } }
    }

    fun onAction(action: SaleHistoryAction) {
        when (action) {
            is SaleHistoryAction.PeriodKindChange -> changeFilters {
                it.copy(period = periodOf(action.kind))
            }

            SaleHistoryAction.PreviousPeriod -> movePeriod(forward = false)

            SaleHistoryAction.NextPeriod -> movePeriod(forward = true)

            SaleHistoryAction.OpenMethods -> state.update { it.copy(methodSheet = true) }

            is SaleHistoryAction.MethodChange -> {
                state.update { it.copy(methodSheet = false) }
                changeFilters { it.copy(methodId = action.methodId) }
            }

            SaleHistoryAction.CloseMethods -> state.update { it.copy(methodSheet = false) }

            SaleHistoryAction.ToggleVoided -> changeFilters { it.copy(onlyVoided = !it.onlyVoided) }

            SaleHistoryAction.ClearFilters ->
                changeFilters { it.copy(period = null, methodId = null, onlyVoided = false) }

            SaleHistoryAction.LoadMore -> loadMore()

            SaleHistoryAction.Retry -> reload(keep = 0)

            is SaleHistoryAction.OpenSale -> state.update {
                it.copy(selectedSaleId = action.saleId)
            }

            SaleHistoryAction.CloseSale -> state.update { it.copy(selectedSaleId = null) }

            // Vender y cambiar de segmento los resuelve la navegación.
            SaleHistoryAction.Sell,
            SaleHistoryAction.ShowPurchases,
            SaleHistoryAction.ShowExpenses,
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

    private fun changeFilters(change: (HistoryFilters) -> HistoryFilters) {
        val before = state.value.filters
        val after = change(before)
        if (after == before) return
        state.update { it.copy(filters = after) }
        reload(keep = 0)
    }

    /**
     * Vuelve a leer desde el principio al menos [keep] ventas: las que ya se veían, para no perder el
     * lugar, o un tramo si no había ninguna.
     */
    private fun reload(keep: Int) {
        loading?.cancel()
        val filter = filterOf(state.value.filters)
        loading = viewModelScope.launch {
            val limit = maxOf(keep, PAGE_SIZE)
            read(filter, after = null, limit = limit, replace = true)
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
        filter: SaleHistoryFilter,
        after: SaleHistoryCursor?,
        limit: Int,
        replace: Boolean,
    ) {
        try {
            val page = withContext(computation) { reader.page(filter, after, limit) }
            val merged = if (replace) page else rows + page
            // Los totales de cada día se piden para los días nuevos, y para todos al volver a leer.
            val dates = page.map { it.businessDate }.toSet()
            val dayTotals = withContext(computation) { reader.dayTotals(filter, dates) }
            rows = merged
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
                        HistoryContent.ReadError
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

    private fun contentOf(filter: SaleHistoryFilter, moreFailed: Boolean = false): HistoryContent {
        if (rows.isEmpty()) {
            return if (filter.isFiltered) HistoryContent.EmptyFiltered else HistoryContent.Empty
        }
        val days = rows.groupBy { it.businessDate }.map { (date, dayRows) ->
            // Un día sin ventas activas suma cero, como lo dice su encabezado.
            HistoryDay(date, totals[date] ?: Money.ZERO, dayRows)
        }
        return HistoryContent.Days(days, endReached, moreFailed)
    }

    private fun filterOf(filters: HistoryFilters) = SaleHistoryFilter(
        from = filters.period?.start,
        to = filters.period?.end,
        paymentMethodId = filters.methodId,
        onlyVoided = filters.onlyVoided,
    )

    companion object {
        /** Cuántas ventas trae cada tramo: varias pantallas llenas, sin pedir demasiado. */
        const val PAGE_SIZE = 50
    }
}
