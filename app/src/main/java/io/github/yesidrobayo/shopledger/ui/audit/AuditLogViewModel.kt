package io.github.yesidrobayo.shopledger.ui.audit

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.audit.AuditEvent
import io.github.yesidrobayo.shopledger.domain.usecase.audit.ObserveAuditLogUseCase
import io.github.yesidrobayo.shopledger.ui.ScreenReads
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

/** Qué eventos muestra el registro: todos, o solo los que terminaron en fallo. */
enum class AuditFilter {
    ALL,
    FAILURES,
}

/** Los eventos de un día del negocio, del más nuevo al más viejo. */
@Immutable
data class AuditDay(val date: BusinessDate, val events: List<AuditEvent>)

/** Lo que muestra `Registro de auditoría`. */
sealed interface AuditLogUiState {
    data object Loading : AuditLogUiState

    data object ReadFailed : AuditLogUiState

    /**
     * @property days los días con eventos, del más nuevo al más viejo. Vacío con [AuditFilter.ALL]
     *   es que no hay eventos todavía; con [AuditFilter.FAILURES], que ninguno falló.
     * @property zone la zona con que se lee la hora de cada evento.
     * @property canLoadMore si hay más eventos que el tramo cargado.
     */
    @Immutable
    data class Ready(
        val filter: AuditFilter,
        val days: List<AuditDay>,
        val zone: BusinessTimeZone,
        val canLoadMore: Boolean,
    ) : AuditLogUiState
}

/** Lo que la persona hace en el registro. Ningún evento se edita, borra ni exporta. */
sealed interface AuditLogAction {
    data object Back : AuditLogAction

    data class Filter(val filter: AuditFilter) : AuditLogAction

    /** Llegó al final de la lista: trae el tramo siguiente. */
    data object LoadMore : AuditLogAction

    data object Retry : AuditLogAction
}

/**
 * El registro de auditoría, solo de lectura. Carga por tramos de
 * [ObserveAuditLogUseCase.PAGE_SIZE] eventos hasta los que la tabla conserva, y los agrupa por el
 * día del negocio en que pasaron. Cada evento nuevo llega solo, sin recargar.
 *
 * @param observe los eventos más nuevos hasta un tope, todos o solo los fallos.
 * @param zone la zona del negocio, o `null` mientras no se configuró: entonces la hora va en UTC.
 * @param computation donde se agrupan los eventos, fuera del hilo principal.
 */
class AuditLogViewModel(
    private val observe: (limit: Int, failuresOnly: Boolean) -> Flow<List<AuditEvent>>,
    zone: Flow<BusinessTimeZone?>,
    computation: CoroutineDispatcher,
) : ViewModel() {
    private val filter = MutableStateFlow(AuditFilter.ALL)
    private val limit = MutableStateFlow(ObserveAuditLogUseCase.PAGE_SIZE)
    private val reads = ScreenReads()

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<AuditLogUiState> =
        combine(filter, limit, ::Pair)
            .flatMapLatest { (shown, size) ->
                val events = reads.guard { observe(size, shown == AuditFilter.FAILURES) }
                combine(events, zone) { list, businessZone ->
                    ready(shown, list, businessZone ?: UTC, size)
                }.map<AuditLogUiState.Ready, AuditLogUiState.Ready?> { it }.onStart { emit(null) }
            }
            .combine(reads.failed) { ready, failed ->
                when {
                    failed -> AuditLogUiState.ReadFailed
                    ready == null -> AuditLogUiState.Loading
                    else -> ready
                }
            }
            .flowOn(computation)
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                AuditLogUiState.Loading,
            )

    fun onAction(action: AuditLogAction) {
        when (action) {
            // Atrás lo resuelve la navegación.
            AuditLogAction.Back -> Unit

            is AuditLogAction.Filter -> if (filter.value != action.filter) {
                filter.value = action.filter
                limit.value = ObserveAuditLogUseCase.PAGE_SIZE
            }

            AuditLogAction.LoadMore -> {
                val ready = uiState.value as? AuditLogUiState.Ready
                if (ready?.canLoadMore == true) {
                    limit.value =
                        (limit.value + ObserveAuditLogUseCase.PAGE_SIZE)
                            .coerceAtMost(ObserveAuditLogUseCase.MAX_EVENTS)
                }
            }

            AuditLogAction.Retry -> reads.retry()
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L

        val UTC: BusinessTimeZone = checkNotNull(BusinessTimeZone.of("UTC"))

        /**
         * Agrupa por día, en el orden del registro: un día aparece donde está su evento más nuevo.
         * Un reloj que saltó no rompe la lista, porque cada día aparece una sola vez.
         */
        fun ready(
            filter: AuditFilter,
            events: List<AuditEvent>,
            zone: BusinessTimeZone,
            limit: Int,
        ): AuditLogUiState.Ready {
            val days =
                events
                    .groupBy { BusinessDate.of(it.occurredAt, zone) }
                    .map { (date, inDay) -> AuditDay(date, inDay) }
            val full = events.size >= limit && limit < ObserveAuditLogUseCase.MAX_EVENTS
            return AuditLogUiState.Ready(filter, days, zone, canLoadMore = full)
        }
    }
}
