package io.github.yesidrobayo.shopledger.ui.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.report.CustomerSales
import io.github.yesidrobayo.shopledger.domain.model.report.IdleProduct
import io.github.yesidrobayo.shopledger.domain.model.report.IdleProducts
import io.github.yesidrobayo.shopledger.domain.model.report.PeriodAnalysis
import io.github.yesidrobayo.shopledger.domain.model.report.ProfitConcentration
import io.github.yesidrobayo.shopledger.domain.rule.report.ProfitConcentrationRules
import io.github.yesidrobayo.shopledger.domain.usecase.report.ObservePeriodAnalysisUseCase
import io.github.yesidrobayo.shopledger.format.NO_VALUE_TEXT
import io.github.yesidrobayo.shopledger.navigation.ScreenModel
import io.github.yesidrobayo.shopledger.navigation.rememberAppViewModel
import io.github.yesidrobayo.shopledger.ui.components.LedgerLineLayout
import io.github.yesidrobayo.shopledger.ui.components.MoneySize
import io.github.yesidrobayo.shopledger.ui.components.MoneyText
import io.github.yesidrobayo.shopledger.ui.components.NoticeCard
import io.github.yesidrobayo.shopledger.ui.components.NoticeVariant
import io.github.yesidrobayo.shopledger.ui.components.RankingRow
import io.github.yesidrobayo.shopledger.ui.components.SectionHeader
import io.github.yesidrobayo.shopledger.ui.components.quantityText
import io.github.yesidrobayo.shopledger.ui.components.unitLabel
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

// `Análisis`, una herramienta de `Números`: los productos que dejan el 80 % de la
// ganancia en ventas, los que tienen stock y no se vendieron, y los mejores clientes del período.

/** Lo que muestra `Análisis`. */
sealed interface AnalysisUiState {
    /** Todavía no llega: no se dibuja nada, porque queda debajo de lo que se ve al abrir. */
    data object Loading : AnalysisUiState

    /** El período no tiene nada que analizar: la herramienta no aparece. */
    data object Hidden : AnalysisUiState

    /** No se pudo leer: lo dice y deja reintentar, sin tocar el resto de la pantalla. */
    data object ReadError : AnalysisUiState

    /** El análisis del período. */
    data class Ready(val analysis: PeriodAnalysis) : AnalysisUiState
}

/**
 * El estado de `Análisis`, aparte del de `Números`: la pantalla le dice qué período muestra con
 * [show], y este vuelve a leer con cada cambio confirmado. Al cambiar de período conserva lo
 * anterior hasta tener lo nuevo, como el resto de la pantalla.
 *
 * @param analysis el análisis de un período.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AnalysisViewModel(private val analysis: (ReportPeriod) -> Flow<PeriodAnalysis>) :
    ViewModel() {
    private val period = MutableStateFlow<ReportPeriod?>(null)
    private val attempts = MutableStateFlow(0)

    val uiState: StateFlow<AnalysisUiState> =
        combine(period, attempts) { shown, _ -> shown }
            .flatMapLatest { shown ->
                if (shown == null) {
                    flowOf<AnalysisUiState>(AnalysisUiState.Loading)
                } else {
                    stateOf(shown)
                }
            }.stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(STOP_AFTER_MS),
                AnalysisUiState.Loading,
            )

    private fun stateOf(shown: ReportPeriod): Flow<AnalysisUiState> =
        analysis(shown)
            .map<PeriodAnalysis, AnalysisUiState> { found ->
                if (found.isEmpty) AnalysisUiState.Hidden else AnalysisUiState.Ready(found)
            }
            // Una falla de lectura no cierra la pantalla: lo dice aquí y deja reintentar.
            .catch { emit(AnalysisUiState.ReadError) }

    /** Muestra el análisis de [shown]. Repetir el mismo período no vuelve a leer. */
    fun show(shown: ReportPeriod) {
        period.value = shown
    }

    /** Vuelve a leer tras un error. */
    fun retry() {
        attempts.value += 1
    }

    private companion object {
        /** Tras girar la pantalla, la lectura sigue viva este tiempo y no se repite. */
        const val STOP_AFTER_MS = 5_000L
    }
}

/**
 * `Análisis` dentro de `Números`, con su propio ViewModel en la misma entrada de la navegación. Si
 * la base todavía no abre, o no abre sin riesgo, no muestra nada: la pantalla ya lo dice.
 */
@Composable
internal fun StatisticsAnalysis(content: StatisticsUiState.Content) {
    val model = rememberAppViewModel(key = ANALYSIS_KEY) { dependencies ->
        val ports = dependencies.ports
        val observe = ObservePeriodAnalysisUseCase(ports.periodBreakdowns, ports.analysisReports)
        AnalysisViewModel { period -> observe(period) }
    }
    when (model) {
        ScreenModel.Opening, ScreenModel.Unavailable -> Unit

        is ScreenModel.Ready -> {
            val viewModel = model.viewModel
            LaunchedEffect(viewModel, content.period) { viewModel.show(content.period) }
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            AnalysisSection(state, content.units, onRetry = viewModel::retry)
        }
    }
}

/**
 * `Análisis`, sin ViewModel. Cada parte aparece solo si tiene algo que decir: los que dejan el
 * 80 %, los que no se movieron y los mejores clientes. Los montos se ocultan en modo discreto; los
 * nombres y las cantidades no.
 *
 * @param units las unidades por id, para nombrar el stock de los productos quietos.
 */
@Composable
internal fun AnalysisSection(
    state: AnalysisUiState,
    units: Map<Long, MeasureUnit>,
    onRetry: () -> Unit,
) {
    val rows = Modifier.padding(horizontal = AppTheme.spacing.s16)
    when (state) {
        AnalysisUiState.Loading, AnalysisUiState.Hidden -> Unit

        AnalysisUiState.ReadError -> {
            SectionHeader(stringResource(R.string.stats_tool_analysis))
            NoticeCard(
                text = stringResource(R.string.state_error_read),
                variant = NoticeVariant.ERROR,
                modifier = rows,
                actionLabel = stringResource(R.string.action_retry),
                onAction = onRetry,
            )
        }

        is AnalysisUiState.Ready -> {
            SectionHeader(stringResource(R.string.stats_tool_analysis))
            Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8)) {
                Leaders(state.analysis.concentration, rows)
                Idle(state.analysis.idle, units, rows)
                Customers(state.analysis.customers, rows)
            }
        }
    }
}

/** El título de una parte del análisis, con su línea de ayuda debajo si la tiene. */
@Composable
private fun PartTitle(title: String, help: String?, modifier: Modifier) {
    Column(modifier.padding(top = AppTheme.spacing.s8).semantics(mergeDescendants = true) {}) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        help?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * `Dejan el 80 %`: cuántos de los productos vendidos llegan juntos al 80 % de la ganancia, y cada
 * uno con lo que dejó. Sin ganancia en el período, `—` y por qué: nunca un cero.
 */
@Composable
private fun Leaders(concentration: ProfitConcentration, modifier: Modifier) {
    val percent = LocalAppFormats.current.percent
    val title =
        stringResource(
            R.string.stats_analysis_leaders_title,
            percent.format(ProfitConcentrationRules.THRESHOLD),
        )
    when (concentration) {
        ProfitConcentration.NoSales -> Unit

        ProfitConcentration.NoProfit -> {
            PartTitle(title, null, modifier)
            Text(NO_VALUE_TEXT, style = MaterialTheme.typography.bodyLarge, modifier = modifier)
            NoticeCard(
                stringResource(R.string.stats_analysis_no_profit),
                NoticeVariant.INFO,
                modifier,
            )
        }

        ProfitConcentration.OutOfRange -> {
            PartTitle(title, null, modifier)
            Text(NO_VALUE_TEXT, style = MaterialTheme.typography.bodyLarge, modifier = modifier)
        }

        is ProfitConcentration.Leaders -> {
            val count = concentration.leaders.size
            val counts = LocalAppFormats.current.quantity
            PartTitle(
                title,
                pluralStringResource(
                    R.plurals.stats_analysis_leaders_summary,
                    count,
                    counts.formatCount(count.toLong()),
                    counts.formatCount(concentration.soldProducts.toLong()),
                    percent.format(concentration.reachedShare),
                ),
                modifier,
            )
            val leader = concentration.leaders.firstOrNull()?.profit ?: return
            Column {
                concentration.leaders.forEach {
                    RankingRow(it.name, it.profit, leader, sensitive = true)
                }
            }
        }
    }
}

/**
 * `Sin movimiento`: los productos con stock que no se vendieron en el período, del de más valor al
 * de menos, con lo que hay y lo que vale. Si son más de los que se listan, cuántos faltan.
 */
@Composable
private fun Idle(idle: IdleProducts, units: Map<Long, MeasureUnit>, modifier: Modifier) {
    if (idle.total == 0L) return
    PartTitle(
        stringResource(R.string.stats_analysis_idle_title),
        stringResource(R.string.stats_analysis_idle_help),
        modifier,
    )
    Column(modifier) {
        idle.products.forEach { IdleRow(it, units[it.baseUnitId]) }
    }
    val more = idle.total - idle.products.size
    if (more > 0L) {
        val count = more.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        Text(
            pluralStringResource(
                R.plurals.stats_analysis_idle_more,
                count,
                LocalAppFormats.current.quantity.formatCount(more),
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier,
        )
    }
}

/** Un producto quieto: el nombre y lo que hay en su unidad, y lo que vale ese stock. */
@Composable
private fun IdleRow(product: IdleProduct, unit: MeasureUnit?) {
    LedgerLineLayout(
        gap = AppTheme.spacing.s16,
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = AppTheme.sizes.touchTarget)
                .semantics(mergeDescendants = true) {},
        leading = {
            Column {
                Text(product.name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (unit == null) {
                        LocalAppFormats.current.quantity.format(product.onHand, true)
                    } else {
                        quantityText(product.onHand, unitLabel(unit))
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        trailing = { MoneyText(product.stockValue, MoneySize.ROW, sensitive = true) },
    )
}

/** `Mejores clientes`: por lo vendido sin impuesto en el período, de mayor a menor. */
@Composable
private fun Customers(customers: List<CustomerSales>, modifier: Modifier) {
    val leader = customers.firstOrNull()?.netSales ?: return
    PartTitle(stringResource(R.string.stats_analysis_customers_title), null, modifier)
    Column {
        customers.forEach { customer ->
            RankingRow(
                customer.name ?: stringResource(R.string.customer_anonymous),
                customer.netSales,
                leader,
                sensitive = true,
            )
        }
    }
}

/** La clave del ViewModel de `Análisis` dentro de la entrada de `Números`. */
private const val ANALYSIS_KEY = "statistics-analysis"
