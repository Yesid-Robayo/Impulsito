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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.report.RealComparison
import io.github.yesidrobayo.shopledger.domain.model.report.RealValue
import io.github.yesidrobayo.shopledger.domain.usecase.report.ObserveRealComparisonUseCase
import io.github.yesidrobayo.shopledger.format.NO_VALUE_TEXT
import io.github.yesidrobayo.shopledger.navigation.AppNavKey
import io.github.yesidrobayo.shopledger.navigation.AppNavigator
import io.github.yesidrobayo.shopledger.navigation.ScreenModel
import io.github.yesidrobayo.shopledger.navigation.rememberAppViewModel
import io.github.yesidrobayo.shopledger.ui.components.CompactChipRow
import io.github.yesidrobayo.shopledger.ui.components.LedgerLineLayout
import io.github.yesidrobayo.shopledger.ui.components.MoneySize
import io.github.yesidrobayo.shopledger.ui.components.MoneyText
import io.github.yesidrobayo.shopledger.ui.components.NoticeCard
import io.github.yesidrobayo.shopledger.ui.components.NoticeVariant
import io.github.yesidrobayo.shopledger.ui.components.SectionHeader
import io.github.yesidrobayo.shopledger.ui.components.StatusTag
import io.github.yesidrobayo.shopledger.ui.components.periodLabel
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.priceindex.missingText
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import kotlin.math.abs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.datetime.YearMonth

// `Comparar`, en `Números`: lo vendido del mes frente a su comparable, en moneda corriente o a
// precios del mes del comparable, con el índice que escribe la persona. Nada se descarga, y la
// cifra real se rotula como estimación.

/** Cómo se comparan las ventas. Las unidades y la moneda de referencia llegan con su tarea. */
enum class CompareMode {
    /** En moneda corriente, como se vendió. */
    NOMINAL,

    /** A precios del mes del comparable, con el índice de precios. */
    REAL,
}

/** Lo que muestra `Comparar`. */
sealed interface ComparisonUiState {
    /** Todavía no llega: no se dibuja nada. */
    data object Loading : ComparisonUiState

    /** No es un mes, o no hay ventas en ninguno de los dos: no aparece. */
    data object Hidden : ComparisonUiState

    /** No se pudo leer: lo dice y deja reintentar. */
    data object ReadError : ComparisonUiState

    /** La comparación del mes, con el modo elegido. */
    data class Ready(val comparison: RealComparison, val mode: CompareMode) : ComparisonUiState
}

/**
 * El estado de `Comparar`, aparte del de `Números`: la pantalla le dice qué período muestra, y
 * este vuelve a leer con cada venta, devolución o cambio del índice. El modo elegido se conserva
 * al cambiar de mes.
 *
 * @param comparison la comparación de un período, o `null` si no es un mes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RealComparisonViewModel(
    private val comparison: (ReportPeriod, BusinessDate) -> Flow<RealComparison?>,
) : ViewModel() {
    private val shown = MutableStateFlow<Shown?>(null)
    private val mode = MutableStateFlow(CompareMode.NOMINAL)
    private val attempts = MutableStateFlow(0)

    val uiState: StateFlow<ComparisonUiState> =
        combine(shown, attempts) { current, _ -> current }
            .flatMapLatest { current ->
                if (current == null) {
                    flowOf<ComparisonUiState>(ComparisonUiState.Loading)
                } else {
                    stateOf(current)
                }
            }.stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(STOP_AFTER_MS),
                ComparisonUiState.Loading,
            )

    private fun stateOf(current: Shown): Flow<ComparisonUiState> =
        combine(comparison(current.period, current.today), mode) { found, chosen ->
            val state: ComparisonUiState =
                if (found == null || (found.base == Money.ZERO && found.nominal == Money.ZERO)) {
                    ComparisonUiState.Hidden
                } else {
                    ComparisonUiState.Ready(found, chosen)
                }
            state
        }.catch { emit(ComparisonUiState.ReadError) }

    /** Muestra la comparación de [period], con [today] para su comparable. */
    fun show(period: ReportPeriod, today: BusinessDate) {
        shown.value = Shown(period, today)
    }

    /** Elige cómo se comparan las ventas. */
    fun select(chosen: CompareMode) {
        mode.value = chosen
    }

    /** Vuelve a leer tras un error. */
    fun retry() {
        attempts.value += 1
    }

    private data class Shown(val period: ReportPeriod, val today: BusinessDate)

    private companion object {
        /** Tras girar la pantalla, la lectura sigue viva este tiempo y no se repite. */
        const val STOP_AFTER_MS = 5_000L
    }
}

/**
 * `Comparar` dentro de `Números`, con su propio ViewModel en la misma entrada de la navegación. Al
 * verse toma el pedido de `Índice de precios`, si lo hay, y elige `Real`.
 */
@Composable
internal fun StatisticsComparison(content: StatisticsUiState.Content, navigator: AppNavigator) {
    val model = rememberAppViewModel(key = COMPARISON_KEY) { dependencies ->
        val ports = dependencies.ports
        val observe = ObserveRealComparisonUseCase(ports.periodReports, ports.priceIndex)
        RealComparisonViewModel { period, today -> observe(period, today) }
    }
    when (model) {
        ScreenModel.Opening, ScreenModel.Unavailable -> Unit

        is ScreenModel.Ready -> {
            val viewModel = model.viewModel
            LaunchedEffect(viewModel) {
                if (navigator.realComparison.take()) viewModel.select(CompareMode.REAL)
            }
            LaunchedEffect(viewModel, content.period, content.today) {
                viewModel.show(content.period, content.today)
            }
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            ComparisonSection(
                state = state,
                today = content.today,
                onSelect = viewModel::select,
                onRetry = viewModel::retry,
                onOpenIndex = { navigator.open(AppNavKey.PriceIndex) },
            )
        }
    }
}

/**
 * `Comparar`, sin ViewModel: los chips `Normal` y `Real`, lo vendido en el comparable y en el mes,
 * y la variación. En `Real`, la cifra del mes a precios del comparable, rotulada como estimación,
 * o qué meses del índice faltan, sin suponer ninguno. Los montos se ocultan en modo discreto; los
 * porcentajes no son montos.
 */
@Composable
internal fun ComparisonSection(
    state: ComparisonUiState,
    today: BusinessDate,
    onSelect: (CompareMode) -> Unit,
    onRetry: () -> Unit,
    onOpenIndex: () -> Unit,
) {
    val rows = Modifier.padding(horizontal = AppTheme.spacing.s16)
    when (state) {
        ComparisonUiState.Loading, ComparisonUiState.Hidden -> Unit

        ComparisonUiState.ReadError -> {
            SectionHeader(stringResource(R.string.stats_compare))
            NoticeCard(
                text = stringResource(R.string.state_error_read),
                variant = NoticeVariant.ERROR,
                modifier = rows,
                actionLabel = stringResource(R.string.action_retry),
                onAction = onRetry,
            )
        }

        is ComparisonUiState.Ready -> {
            SectionHeader(stringResource(R.string.stats_compare))
            Column(rows, verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8)) {
                CompactChipRow(
                    options = CompareMode.entries,
                    label = { modeLabel(it) },
                    selected = setOf(state.mode),
                    onToggle = onSelect,
                    multiple = false,
                )
                val compared = state.comparison
                Column {
                    AmountRow(periodLabel(compared.comparedTo, today), compared.base)
                    AmountRow(periodLabel(compared.period, today), compared.nominal)
                }
                when (state.mode) {
                    CompareMode.NOMINAL -> Variation(compared, compared.nominalVariation, today)
                    CompareMode.REAL -> Real(compared, today, onOpenIndex)
                }
            }
        }
    }
}

/** La parte `Real`: la estimación con su variación, o qué meses faltan. */
@Composable
private fun Real(compared: RealComparison, today: BusinessDate, onOpenIndex: () -> Unit) {
    when (val real = compared.real) {
        is RealValue.Estimated -> {
            val month = monthOf(compared.comparedTo.start)
            AmountRow(
                stringResource(
                    R.string.stats_compare_real_value,
                    periodLabel(compared.period, today),
                    LocalAppFormats.current.date.monthYear(month),
                ),
                real.value,
                estimated = true,
            )
            Variation(compared, real.variation, today)
            Text(
                stringResource(R.string.stats_compare_real_help),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        is RealValue.MissingMonths ->
            NoticeCard(
                text = missingText(real.months),
                variant = NoticeVariant.WARNING,
                actionLabel = stringResource(R.string.index_add_action),
                onAction = onOpenIndex,
            )

        RealValue.OutOfRange -> {
            // TalkBack lee `Sin dato`, no el guion.
            val noValue = stringResource(R.string.a11y_no_value)
            Text(
                NO_VALUE_TEXT,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.semantics { contentDescription = noValue },
            )
        }
    }
}

/**
 * `Variación +6,8 %`. Sin ventas en el comparable no hay con qué comparar, y se dice: nunca un
 * cero que parezca medido.
 */
@Composable
private fun Variation(compared: RealComparison, variation: BasisPoints?, today: BusinessDate) {
    val percent = LocalAppFormats.current.percent
    val text =
        if (variation == null) {
            stringResource(
                R.string.stats_compare_no_base,
                periodLabel(compared.comparedTo, today),
            )
        } else {
            stringResource(R.string.stats_compare_variation, percent.formatChange(variation))
        }
    // TalkBack dice la dirección en palabras, `Variación sube 6,8 %`: el signo puede no leerse.
    val spoken = variation?.let { change ->
        val magnitude = percent.format(BasisPoints(abs(change.bp)))
        when {
            magnitude == percent.format(BasisPoints.ZERO) -> null
            change > BasisPoints.ZERO -> stringResource(R.string.a11y_change_rises, magnitude)
            else -> stringResource(R.string.a11y_change_falls, magnitude)
        }
    }?.let { stringResource(R.string.stats_compare_variation, it) }
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = if (spoken == null) {
            Modifier
        } else {
            Modifier.semantics { contentDescription = spoken }
        },
    )
}

/** Una fila con su etiqueta y su monto; la estimada lleva su rótulo debajo de la etiqueta. */
@Composable
private fun AmountRow(label: String, amount: Money, estimated: Boolean = false) {
    LedgerLineLayout(
        gap = AppTheme.spacing.s16,
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = AppTheme.sizes.touchTarget)
                .semantics(mergeDescendants = true) {},
        leading = {
            Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s4)) {
                Text(label, style = MaterialTheme.typography.bodyLarge)
                if (estimated) StatusTag(stringResource(R.string.stats_compare_estimate))
            }
        },
        trailing = { MoneyText(amount, MoneySize.ROW, sensitive = true) },
    )
}

@Composable
private fun modeLabel(mode: CompareMode): String = stringResource(
    when (mode) {
        CompareMode.NOMINAL -> R.string.stats_compare_nominal
        CompareMode.REAL -> R.string.stats_compare_real
    },
)

/** El mes de [date]. */
private fun monthOf(date: BusinessDate): YearMonth = YearMonth(date.date.year, date.date.month)

/** La clave del ViewModel de `Comparar` dentro de la entrada de `Números`. */
private const val COMPARISON_KEY = "statistics-comparison"
