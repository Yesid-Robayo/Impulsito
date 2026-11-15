package io.github.yesidrobayo.shopledger.ui.stats

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.report.CashFlowProjection
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.domain.usecase.report.ObserveCashFlowUseCase
import io.github.yesidrobayo.shopledger.navigation.ScreenModel
import io.github.yesidrobayo.shopledger.navigation.rememberAppViewModel
import io.github.yesidrobayo.shopledger.ui.components.LedgerLineLayout
import io.github.yesidrobayo.shopledger.ui.components.MoneySize
import io.github.yesidrobayo.shopledger.ui.components.MoneyText
import io.github.yesidrobayo.shopledger.ui.components.MoneyTone
import io.github.yesidrobayo.shopledger.ui.components.NoticeCard
import io.github.yesidrobayo.shopledger.ui.components.NoticeVariant
import io.github.yesidrobayo.shopledger.ui.components.SectionHeader
import io.github.yesidrobayo.shopledger.ui.components.StatusTag
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/** Lo que muestra `Flujo proyectado`. */
sealed interface CashFlowUiState {
    /** Leyendo. No dibuja nada: queda debajo de lo que se ve al abrir. */
    data object Loading : CashFlowUiState

    /** Sin cuentas en uso o sin zona conocida: la herramienta no aparece. */
    data object Hidden : CashFlowUiState

    /** No se pudo leer: queda en su lugar con `Reintentar`, y el resto de la pantalla sigue. */
    data object Failed : CashFlowUiState

    data class Ready(val projection: CashFlowProjection) : CashFlowUiState
}

/**
 * El flujo de caja proyectado de `Números`, aparte de la pantalla: siempre parte de hoy, sin
 * importar el período elegido. Al volver a primer plano y al reintentar vuelve a leer, y con eso a
 * mirar qué día es. Mientras tanto se conserva lo que había.
 *
 * @param observe el flujo de hoy.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CashFlowViewModel(
    observe: () -> Flow<CashFlowProjection?>,
) : ViewModel() {
    /** Cada vuelta a primer plano y cada reintento vuelve a leer. */
    private val checks = MutableStateFlow(0)

    val uiState: StateFlow<CashFlowUiState> = checks
        .flatMapLatest {
            observe()
                .map<CashFlowProjection?, CashFlowUiState> { projection ->
                    projection?.let { CashFlowUiState.Ready(it) } ?: CashFlowUiState.Hidden
                }
                .catch { emit(CashFlowUiState.Failed) }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, CashFlowUiState.Loading)

    /** La pantalla volvió a primer plano: el día pudo cambiar. */
    fun onReturnToForeground() = checks.update { it + 1 }

    fun retry() = checks.update { it + 1 }
}

/**
 * `Flujo proyectado` en `Números`, con su propio ViewModel. Sin base, como en las vistas previas,
 * no dibuja nada.
 */
@Composable
fun StatisticsCashFlow() {
    val model =
        rememberAppViewModel(key = "cash-flow") { dependencies ->
            val ports = dependencies.ports
            val observe = ObserveCashFlowUseCase(
                source = ports.cashFlow,
                recurring = ports.recurringExpenses,
                usage = ports.moduleUsage,
                calendar = BusinessCalendarSource(ports.settings),
                clock = dependencies.container.clock,
            )
            CashFlowViewModel(observe = { observe() })
        }
    if (model !is ScreenModel.Ready) return
    val viewModel = model.viewModel
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LifecycleResumeEffect(viewModel) {
        viewModel.onReturnToForeground()
        onPauseOrDispose {}
    }
    CashFlowTool(state, onRetry = viewModel::retry)
}

/**
 * La herramienta: lo disponible hoy y lo proyectado a 7 y 30 días, en barras y como filas,
 * rotulada como estimación. Las cifras se ocultan en modo discreto; las barras conservan su
 * forma. Es pura.
 */
@Composable
fun CashFlowTool(state: CashFlowUiState, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    when (state) {
        CashFlowUiState.Loading, CashFlowUiState.Hidden -> Unit

        CashFlowUiState.Failed -> Column(modifier) {
            SectionHeader(stringResource(R.string.stats_tool_cash_flow))
            NoticeCard(
                text = stringResource(R.string.state_error_read),
                variant = NoticeVariant.ERROR,
                modifier = Modifier.padding(horizontal = AppTheme.spacing.s16),
                actionLabel = stringResource(R.string.action_retry),
                onAction = onRetry,
            )
        }

        is CashFlowUiState.Ready -> Column(modifier) { Projection(state.projection) }
    }
}

@Composable
private fun ColumnScope.Projection(projection: CashFlowProjection) {
    val spacing = AppTheme.spacing
    SectionHeader(stringResource(R.string.stats_tool_cash_flow))
    Column(
        Modifier.padding(horizontal = spacing.s16),
        verticalArrangement = Arrangement.spacedBy(spacing.s8),
    ) {
        StatusTag(stringResource(R.string.cash_flow_estimate_tag))
        val values = listOf(projection.availableToday) + projection.horizons.map { it.projected }
        CashFlowBars(values)
        Column {
            CashFlowRow(R.string.cash_flow_available_today, projection.availableToday)
            projection.horizons.forEach { horizon ->
                CashFlowRow(horizonLabel(horizon.days), horizon.projected)
            }
        }
        Text(
            stringResource(R.string.cash_flow_help),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@StringRes
private fun horizonLabel(days: Int): Int =
    if (days <= SHORT_HORIZON_DAYS) R.string.cash_flow_in_7_days else R.string.cash_flow_in_30_days

/** Una fila: la etiqueta y su cifra, en rojo con signo si queda por debajo de cero. */
@Composable
private fun CashFlowRow(@StringRes label: Int, amount: Money) {
    LedgerLineLayout(
        gap = AppTheme.spacing.s16,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = AppTheme.sizes.touchTarget)
            .semantics(mergeDescendants = true) {},
        leading = { Text(stringResource(label), style = MaterialTheme.typography.bodyLarge) },
        trailing = {
            MoneyText(
                amount,
                MoneySize.ROW,
                tone = if (amount < Money.ZERO) MoneyTone.LOSS else MoneyTone.DEFAULT,
                sensitive = true,
            )
        },
    )
}

/**
 * `CashFlowBars`: una barra por cifra, lo disponible primero, desde una línea en cero. Lo que
 * queda por debajo de cero va hacia abajo, en el color de pérdida. Solo dibuja: las cifras están
 * en las filas, que son el camino accesible, así que TalkBack no la lee.
 */
@Composable
private fun CashFlowBars(values: List<Money>) {
    val positive = AppTheme.colors.chartBar
    val negative = AppTheme.colors.chartLoss
    val grid = AppTheme.colors.chartGrid
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(AppTheme.sizes.chartArea / 2)
            .clearAndSetSemantics {},
    ) {
        val top = maxOf(values.maxOf { it.minor }, 0L)
        val bottom = minOf(values.minOf { it.minor }, 0L)
        val span = (top - bottom).takeIf { it > 0L } ?: 1L
        // Solo para dibujar: la proporción de cada barra en píxeles, no un cálculo de dinero.
        val zero = size.height * (top.toFloat() / span.toFloat())
        val slot = size.width / values.size
        val width = slot * BAR_SHARE
        values.forEachIndexed { index, value ->
            val height = size.height * (value.minor.toFloat() / span.toFloat())
            val left = slot * index + (slot - width) / 2
            val upward = value.minor >= 0L
            drawRect(
                color = if (upward) positive else negative,
                topLeft = Offset(left, if (upward) zero - height else zero),
                size = Size(width, if (upward) height else -height),
            )
        }
        drawLine(grid, Offset(0f, zero), Offset(size.width, zero), strokeWidth = 1f)
    }
}

/** El horizonte que se rotula `En 7 días`; el otro es `En 30 días`. */
private const val SHORT_HORIZON_DAYS = 7

/** La parte de su lugar que ocupa cada barra. */
private const val BAR_SHARE = 0.5f
