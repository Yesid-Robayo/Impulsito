package io.github.yesidrobayo.shopledger.ui.report

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.report.ManagementSummary
import io.github.yesidrobayo.shopledger.domain.model.report.SummaryContent
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendar
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import io.github.yesidrobayo.shopledger.domain.rule.report.ManagementSummaryRules
import io.github.yesidrobayo.shopledger.domain.rule.report.PeriodNavigation
import kotlin.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.yearMonth

/**
 * El resumen de gestión: el período, sus cifras, lo que se incluye y la generación del paquete.
 * Todo se calcula en el teléfono: nada espera a la red.
 *
 * - Abre en el último mes completo, según la zona del negocio. Avanza hasta el mes en curso, que
 *   lleva su aviso, y retrocede sin límite. `Elegir fechas` cambia a un rango propio.
 * - Al cambiar de período, lo anterior se conserva hasta tener lo nuevo.
 * - Ventas y compras van encendidas al abrir; el inventario y los clientes y proveedores, que solo
 *   se ofrecen si hay datos, apagados. Lo que la persona cambia se recuerda al moverse de período.
 * - Generar es de a uno: mientras escribe, otro toque no hace nada. El paquete lo escribe la
 *   navegación con [job], y avisa con [SummaryAction.Finished].
 *
 * @param calendar el calendario del negocio, para el mes de hoy.
 * @param now el instante actual, del reloj inyectado.
 * @param observe las cifras de un período, o `null` si una no cabe.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ManagementSummaryViewModel(
    calendar: Flow<BusinessCalendarResult>,
    private val now: () -> Instant,
    private val observe: (ReportPeriod, BusinessCalendar) -> Flow<ManagementSummary?>,
) : ViewModel() {
    private val state = MutableStateFlow(ManagementSummaryUiState())
    val uiState: StateFlow<ManagementSummaryUiState> = state.asStateFlow()

    private val pending = MutableStateFlow<SummaryJob?>(null)

    /** El paquete que falta escribir, o `null`. Lo resuelve la navegación. */
    val job: StateFlow<SummaryJob?> = pending.asStateFlow()

    /** El período, el calendario y el número de intento: cambiar cualquiera vuelve a leer. */
    private val query = MutableStateFlow<Query?>(null)

    /** Lo que la persona encendió o apagó a mano, por grupo. */
    private var toggles: Map<SummaryContent, Boolean> = emptyMap()
    private var attempts = 0L

    init {
        viewModelScope.launch {
            // Sin calendario legible, la pantalla lo trata como una zona que no conoce.
            calendar.catch {}.collect { result ->
                when (result) {
                    is BusinessCalendarResult.Ready -> showCalendar(result.calendar)

                    is BusinessCalendarResult.UnknownZone ->
                        state.update { it.copy(content = SummaryScreenContent.UnknownZone) }
                }
            }
        }
        viewModelScope.launch {
            query.filterNotNull().flatMapLatest { current ->
                observe(current.period, current.calendar)
                    .map<ManagementSummary?, SummaryScreenContent> { summary ->
                        if (summary == null) {
                            SummaryScreenContent.ReadError
                        } else {
                            SummaryScreenContent.Ready(summary)
                        }
                    }
                    .catch { emit(SummaryScreenContent.ReadError) }
            }.collect { content -> state.update { it.withContent(content) } }
        }
    }

    private fun showCalendar(calendar: BusinessCalendar) {
        val today = calendar.businessDateOf(now())
        val current = query.value
        // Si cambia el calendario, el período elegido se conserva; al abrir, el último mes entero.
        val period = current?.period
            ?: PeriodNavigation.previous(ReportPeriod.Month(today.date.yearMonth))
        state.update {
            it.copy(
                period = period,
                today = today,
                weekStartDay = calendar.weekStartDay,
                canGoNext = today > period.end,
            )
        }
        query.value = Query(period, calendar, current?.attempt ?: 0)
    }

    fun onAction(action: SummaryAction) {
        when (action) {
            SummaryAction.PreviousPeriod -> move { PeriodNavigation.previous(it) }

            SummaryAction.NextPeriod -> if (state.value.canGoNext) {
                move { PeriodNavigation.next(it) }
            }

            SummaryAction.ChooseRange -> state.update { it.copy(choosingRange = true) }

            SummaryAction.DismissRange -> state.update { it.copy(choosingRange = false) }

            is SummaryAction.ShowRange -> {
                val from = minOf(action.from, action.to)
                val to = maxOf(action.from, action.to)
                state.update { it.copy(choosingRange = false) }
                move { ReportPeriod.Range(from, to) }
            }

            is SummaryAction.Include -> {
                toggles = toggles + (action.content to action.on)
                state.update { it.copy(chosen = chosenOf(it.summary)) }
            }

            SummaryAction.Retry -> {
                state.update { it.copy(content = SummaryScreenContent.Loading) }
                query.update { it?.copy(attempt = it.attempt + 1) }
            }

            SummaryAction.Confirmed -> start()

            is SummaryAction.Finished -> {
                pending.value = null
                val generation = when (val outcome = action.outcome) {
                    is SummaryOutcome.Saved ->
                        SummaryGeneration.Ready(outcome.fileName, outcome.sizeBytes)

                    SummaryOutcome.NoSpace -> SummaryGeneration.NoSpace

                    SummaryOutcome.Failed -> SummaryGeneration.Failed
                }
                state.update { it.copy(generation = generation) }
            }

            SummaryAction.DismissResult ->
                state.update { it.copy(generation = SummaryGeneration.Idle) }

            // Los resuelve la navegación.
            SummaryAction.Back,
            SummaryAction.ChooseZone,
            SummaryAction.Generate,
            SummaryAction.Share,
            SummaryAction.SaveToFolder,
            -> Unit
        }
    }

    /** Empieza a escribir el paquete con las cifras y lo encendido que se ven ahora. */
    private fun start() {
        val current = state.value
        val summary = current.summary ?: return
        val today = current.today ?: return
        if (!current.canGenerate) return
        attempts += 1
        state.update { it.copy(generation = SummaryGeneration.Working) }
        pending.value = SummaryJob(summary, current.chosen, today, attempts)
    }

    /** Va al período que da [step] desde el elegido. */
    private fun move(step: (ReportPeriod) -> ReportPeriod) {
        val current = query.value ?: return
        val today = state.value.today ?: return
        val period = step(current.period)
        state.update { it.copy(period = period, canGoNext = today > period.end) }
        query.value = current.copy(period = period)
    }

    private fun ManagementSummaryUiState.withContent(
        content: SummaryScreenContent,
    ): ManagementSummaryUiState {
        val summary = (content as? SummaryScreenContent.Ready)?.summary
        return copy(content = content, chosen = summary?.let(::chosenOf) ?: chosen)
    }

    /** Lo encendido entre lo que [summary] ofrece: lo de siempre, con lo que la persona cambió. */
    private fun chosenOf(summary: ManagementSummary?): Set<SummaryContent> {
        val offered = summary?.contents ?: return emptySet()
        val defaults = ManagementSummaryRules.defaultChoice(offered)
        return offered.filter { toggles[it] ?: (it in defaults) }.toSet()
    }

    private data class Query(
        val period: ReportPeriod,
        val calendar: BusinessCalendar,
        val attempt: Int,
    )
}
