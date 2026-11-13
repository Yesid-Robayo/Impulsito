package io.github.yesidrobayo.shopledger.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.report.DayPoint
import io.github.yesidrobayo.shopledger.domain.model.report.PeriodKind
import io.github.yesidrobayo.shopledger.domain.model.report.ReplacementProfit
import io.github.yesidrobayo.shopledger.domain.model.tax.TaxEstimate
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendar
import io.github.yesidrobayo.shopledger.domain.rule.report.PeriodNavigation
import io.github.yesidrobayo.shopledger.domain.usecase.report.ComparedPeriod
import io.github.yesidrobayo.shopledger.domain.usecase.report.PeriodBreakdown
import io.github.yesidrobayo.shopledger.domain.usecase.report.PeriodReport
import io.github.yesidrobayo.shopledger.domain.usecase.report.ReportCalendar
import io.github.yesidrobayo.shopledger.domain.usecase.report.SoldAndCollectedView
import io.github.yesidrobayo.shopledger.domain.usecase.tax.TaxEstimateState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

/**
 * `Números`: las cifras de un día, una semana o un mes, que vuelven a calcularse con cada venta,
 * gasto o anulación confirmados, sin recargar la pantalla.
 *
 * Abre en el mes en curso. Recorre los períodos sin límite hacia atrás y, hacia adelante, hasta hoy
 * o hasta el último dato con fecha futura. El día se vuelve a mirar con [onReturnToForeground]: a
 * medianoche, el período en curso pasa al nuevo sin un trabajo en segundo plano.
 *
 * @param calendar el calendario de las estadísticas; recibe las veces que se vuelve a mirar la
 *   fecha.
 * @param lastActiveDate la última fecha con ventas o gastos, `null` si todavía no hay ninguno.
 * @param report las métricas de un período.
 * @param series la serie por día de un período, para la gráfica.
 * @param tax el impuesto estimado de un período.
 * @param comparison la variación de un período frente a su comparable, si lo tiene.
 * @param breakdown los destacados, los desgloses y los avisos de un período.
 * @param units las unidades, para nombrar las cantidades de los más vendidos.
 * @param otherIncome los otros ingresos de un período, que se muestran aparte de la ganancia.
 * @param collection lo vendido y lo cobrado de un período, o `null` si el negocio no vende a
 *   crédito.
 * @param replacement la ganancia si se repone de un período, o `null` si una cifra no cabe.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StatisticsViewModel(
    calendar: (Flow<Unit>) -> Flow<ReportCalendar>,
    lastActiveDate: Flow<BusinessDate?>,
    private val report: (ReportPeriod) -> Flow<PeriodReport>,
    private val series: (ReportPeriod) -> Flow<List<DayPoint>?>,
    private val tax: (ReportPeriod) -> Flow<TaxEstimateState>,
    private val comparison: (ReportPeriod, BusinessDate) -> Flow<ComparedPeriod?> = { _, _ ->
        flowOf(null)
    },
    private val breakdown: (ReportPeriod, BusinessCalendar) -> Flow<PeriodBreakdown?> = { _, _ ->
        flowOf(null)
    },
    units: Flow<List<MeasureUnit>> = flowOf(emptyList()),
    private val otherIncome: (ReportPeriod) -> Flow<Money> = { flowOf(Money.ZERO) },
    private val collection: (ReportPeriod) -> Flow<SoldAndCollectedView?> = { flowOf(null) },
    private val replacement: (ReportPeriod) -> Flow<ReplacementProfit?> = { flowOf(null) },
) : ViewModel() {
    private val unitsById = units.map { all -> all.associateBy { it.id } }.distinctUntilChanged()

    // Un contador, y no un evento: dos vueltas seguidas sin leer entre ellas valen por una.
    private val dayChecks = MutableStateFlow(0L)
    private val attempts = MutableStateFlow(0)

    /** Lo que eligió la persona: no depende de la base, y sobrevive a cada lectura nueva. */
    private val choice = MutableStateFlow(Choice())

    /**
     * El último calendario listo: con él, anterior, siguiente y el cambio de tamaño se calculan
     * desde la elección, sin esperar a que la pantalla muestre el período anterior. Dos toques
     * rápidos mueven dos períodos.
     */
    private var lastCalendar: ReportCalendar.Ready? = null

    private val calendars =
        calendar(dayChecks.map {}).distinctUntilChanged().onEach { calendar ->
            lastCalendar = calendar as? ReportCalendar.Ready
        }

    /** El período elegido, de nuevo cuando cambia la elección, el día o las semanas del negocio. */
    private val shown: Flow<Shown?> =
        combine(calendars, lastActiveDate, choice) { calendar, last, chosen ->
            when (calendar) {
                ReportCalendar.UnknownZone -> null

                is ReportCalendar.Ready -> {
                    val period = periodOf(chosen, calendar)
                    // Un rango propio no se recorre con flechas: se elige otro.
                    val ranged = chosen.range != null
                    Shown(
                        period = period,
                        today = calendar.today,
                        calendar = calendar.calendar,
                        canGoPrevious = !ranged && PeriodNavigation.hasPrevious(period),
                        canGoNext =
                            !ranged && PeriodNavigation.hasNext(period, calendar.forwardLimit),
                        hasData = last != null,
                    )
                }
            }
        }.distinctUntilChanged()

    val uiState: StateFlow<StatisticsUiState> = attempts.flatMapLatest { attempt ->
        shown.flatMapLatest { shown ->
            when {
                shown == null -> flowOf(StatisticsUiState.UnknownZone)
                !shown.hasData -> flowOf(StatisticsUiState.NoData)
                else -> figuresOf(shown)
            }
        }
            // Al reintentar, la pantalla deja el error mientras vuelve a leer.
            .onStart { if (attempt > 0) emit(StatisticsUiState.Loading) }
            // Una falla de lectura no cierra la pantalla: muestra el error y deja reintentar.
            .catch { emit(StatisticsUiState.ReadError) }
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_AFTER_MS),
        StatisticsUiState.Loading,
    )

    private fun figuresOf(shown: Shown): Flow<StatisticsUiState> {
        val period = shown.period
        // Un día es una cifra, y un rango largo no cabe en barras por día: ninguno lleva gráfica.
        val days =
            when (period) {
                is ReportPeriod.Week, is ReportPeriod.Month -> series(period)
                is ReportPeriod.Day, is ReportPeriod.Range -> flowOf(null)
            }
        val figures =
            combine(report(period), days, tax(period), replacement(period)) {
                    report,
                    points,
                    tax,
                    replaced,
                ->
                Figures(report, points, taxOf(tax), replaced)
            }
        val details =
            combine(
                comparison(period, shown.today),
                breakdown(period, shown.calendar),
                unitsById,
                otherIncome(period),
                collection(period),
            ) { compared, explained, units, income, collected ->
                Details(compared, explained, units, income, collected)
            }
        return combine(figures, details, choice) { figures, details, chosen ->
            StatisticsUiState.Content(
                kind = PeriodNavigation.kindOf(period),
                period = period,
                today = shown.today,
                canGoPrevious = shown.canGoPrevious,
                canGoNext = shown.canGoNext,
                // Sin ventas a crédito no hay `Cobrado`: se vuelve a las ventas.
                metric = chosen.metric.takeUnless {
                    it == ChartMetric.COLLECTED && details.collection == null
                } ?: ChartMetric.SALES,
                metrics = figures.report.metrics,
                series = figures.points,
                // Un día tocado solo vale dentro del período que lo muestra.
                selectedDay = chosen.selectedDay?.takeIf { it in period },
                showList = chosen.showList,
                tax = figures.tax,
                comparison = details.compared,
                breakdown = details.breakdown,
                units = details.units,
                // Solo con datos: sin otros ingresos en el período, la sección no aparece.
                otherIncome = details.otherIncome.takeIf { it != Money.ZERO },
                ranking = chosen.ranking,
                choosingRange = chosen.choosingRange,
                weekStartDay = shown.calendar.weekStartDay,
                collection = details.collection,
                // Sin ventas no hay nada que reponer: la herramienta no aparece.
                replacement = figures.replacement?.takeIf { it.netSales != Money.ZERO },
            )
        }
    }

    private fun taxOf(state: TaxEstimateState): TaxEstimate? = when (state) {
        TaxEstimateState.Hidden -> null
        is TaxEstimateState.Estimated -> state.estimate
    }

    fun onAction(action: StatisticsAction) {
        when (action) {
            is StatisticsAction.SelectKind -> choice.value = choice.value.withKind(action.kind)

            is StatisticsAction.SelectRanking ->
                choice.value = choice.value.copy(ranking = action.ranking)

            StatisticsAction.ChooseRange -> choice.value = choice.value.copy(choosingRange = true)

            StatisticsAction.DismissRange ->
                choice.value = choice.value.copy(choosingRange = false)

            is StatisticsAction.ShowRange -> showRange(action.from, action.to)

            StatisticsAction.Previous ->
                move(PeriodNavigation::previous) { period, _ ->
                    PeriodNavigation.hasPrevious(period)
                }

            StatisticsAction.Next ->
                move(PeriodNavigation::next) { period, calendar ->
                    PeriodNavigation.hasNext(period, calendar.forwardLimit)
                }

            is StatisticsAction.SelectMetric ->
                choice.value = choice.value.copy(metric = action.metric)

            is StatisticsAction.SelectDay ->
                choice.value =
                    choice.value.copy(
                        selectedDay = action.date.takeIf { it != choice.value.selectedDay },
                    )

            StatisticsAction.ToggleList ->
                choice.value = choice.value.copy(showList = !choice.value.showList)

            StatisticsAction.Retry -> attempts.value += 1

            // Lo resuelve la navegación.
            StatisticsAction.NewSale, StatisticsAction.ChooseZone -> Unit
        }
    }

    /** La pantalla volvió a primer plano: si cambió el día, el período en curso pasa al nuevo. */
    fun onReturnToForeground() {
        dayChecks.value += 1
    }

    /**
     * Va al período que da [step] desde el elegido, si [allowed] lo deja, y suelta la barra
     * tocada.
     */
    private fun move(
        step: (ReportPeriod) -> ReportPeriod,
        allowed: (ReportPeriod, ReportCalendar.Ready) -> Boolean,
    ) {
        val calendar = lastCalendar ?: return
        val current = choice.value
        if (current.range != null) return
        val period = periodOf(current, calendar)
        if (!allowed(period, calendar)) return
        choice.value = current.copy(anchor = step(period).start, selectedDay = null)
    }

    /** Al cambiar de tamaño, el período nuevo contiene el inicio del que se veía, o hoy. */
    private fun Choice.withKind(kind: PeriodKind): Choice {
        val calendar = lastCalendar ?: return copy(kind = kind, range = null, selectedDay = null)
        val period = periodOf(this, calendar)
        val anchor = if (PeriodNavigation.isCurrent(period, calendar.today)) null else period.start
        // Elegir día, semana o mes deja el rango propio y vuelve a los períodos.
        return copy(kind = kind, anchor = anchor, range = null, selectedDay = null)
    }

    /** Muestra el rango de las dos fechas, en cualquier orden: va de la primera a la última. */
    private fun showRange(first: BusinessDate, second: BusinessDate) {
        val range =
            if (first <= second) {
                ReportPeriod.Range(first, second)
            } else {
                ReportPeriod.Range(second, first)
            }
        choice.value = choice.value.copy(range = range, choosingRange = false, selectedDay = null)
    }

    private fun periodOf(chosen: Choice, calendar: ReportCalendar.Ready): ReportPeriod =
        chosen.range ?: PeriodNavigation.containing(
            chosen.kind,
            chosen.anchor ?: calendar.today,
            calendar.calendar,
        )

    /**
     * Lo que eligió la persona. [anchor] es un día del período que se ve, o `null` para el período
     * en curso, que sigue a hoy cuando cambia la fecha.
     */
    private data class Choice(
        val kind: PeriodKind = PeriodKind.MONTH,
        val anchor: BusinessDate? = null,
        val metric: ChartMetric = ChartMetric.SALES,
        val selectedDay: BusinessDate? = null,
        val showList: Boolean = false,
        val ranking: RankingKind = RankingKind.SALES,
        val range: ReportPeriod.Range? = null,
        val choosingRange: Boolean = false,
    )

    private data class Figures(
        val report: PeriodReport,
        val points: List<DayPoint>?,
        val tax: TaxEstimate?,
        val replacement: ReplacementProfit?,
    )

    private data class Details(
        val compared: ComparedPeriod?,
        val breakdown: PeriodBreakdown?,
        val units: Map<Long, MeasureUnit>,
        val otherIncome: Money,
        val collection: SoldAndCollectedView?,
    )

    private data class Shown(
        val period: ReportPeriod,
        val today: BusinessDate,
        val calendar: BusinessCalendar,
        val canGoPrevious: Boolean,
        val canGoNext: Boolean,
        val hasData: Boolean,
    )

    private companion object {
        /** Tras girar la pantalla, las lecturas siguen vivas este tiempo y no se repiten. */
        const val STOP_AFTER_MS = 5_000L
    }
}
