package io.github.yesidrobayo.shopledger.ui.quote

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.document.QuoteStatus
import io.github.yesidrobayo.shopledger.ui.components.ActionVariant
import io.github.yesidrobayo.shopledger.ui.components.CompactChipRow
import io.github.yesidrobayo.shopledger.ui.components.DelayedSkeleton
import io.github.yesidrobayo.shopledger.ui.components.DocumentRow
import io.github.yesidrobayo.shopledger.ui.components.EmptyState
import io.github.yesidrobayo.shopledger.ui.components.PrimaryActionButton
import io.github.yesidrobayo.shopledger.ui.components.SkeletonBlock
import io.github.yesidrobayo.shopledger.ui.components.StatusTone
import io.github.yesidrobayo.shopledger.ui.components.TonalFab
import io.github.yesidrobayo.shopledger.ui.components.bottomBarClearance
import io.github.yesidrobayo.shopledger.ui.components.keepsMessagesAbove
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.purchase.RecordsSegments
import io.github.yesidrobayo.shopledger.ui.purchase.RecordsTopBar
import io.github.yesidrobayo.shopledger.ui.sale.LocalRecordsExtraSegments
import io.github.yesidrobayo.shopledger.ui.sale.RecordsTab
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme

/**
 * Las cotizaciones (`Cotizaciones` en Registros, o desde el centro `Negocio`).
 *
 * - Chips `Abiertas`, `Convertidas` y `Canceladas`, con `Abiertas` elegido al entrar.
 * - Una fila por cotización: número, cliente o `Sin cliente`, vigencia y total, con su etiqueta si
 *   está vencida, convertida o cancelada.
 * - `Nueva cotización` abre la venta en modo cotización.
 * - En una ventana ancha, la cotización elegida se abre a la derecha, sin salir de la lista.
 * - Una cotización no lleva costo, margen ni ganancia: el modo discreto no cambia nada.
 *
 * Es pura: recibe el estado y avisa cada acción.
 *
 * @param asTab si va como segmento de Registros, con su barra y los segmentos. Si no, es un destino
 *   propio, con atrás y el título `Cotizaciones`.
 * @param onSegment elige otro segmento de Registros por su posición.
 * @param detail el detalle de una cotización, para la ventana ancha.
 */
@Composable
fun QuotesScreen(
    uiState: QuotesUiState,
    onAction: (QuotesAction) -> Unit,
    modifier: Modifier = Modifier,
    asTab: Boolean = false,
    onSegment: (Int) -> Unit = {},
    backupBanner: @Composable () -> Unit = {},
    detail: @Composable (Long) -> Unit = {},
) {
    val sizes = AppTheme.sizes
    val barClearance = if (asTab) bottomBarClearance() else 0.dp
    Surface(
        modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= EXPANDED_WIDTH
            Column(Modifier.fillMaxSize()) {
                RecordsTopBar(
                    asTab = asTab,
                    title = R.string.quote_title,
                    onBack = { onAction(QuotesAction.Back) },
                )
                if (asTab) {
                    backupBanner()
                    val extra = LocalRecordsExtraSegments.current
                    val selected = BASE_SEGMENTS + extra.tabs.indexOf(RecordsTab.QUOTES)
                    RecordsSegments(selected = selected, onSelect = onSegment)
                }
                Row(Modifier.weight(1f)) {
                    val listWidth = if (wide) {
                        Modifier.width(sizes.maxContentWidth)
                    } else {
                        Modifier.weight(1f)
                    }
                    Box(listWidth.fillMaxHeight()) {
                        Column(Modifier.fillMaxSize()) {
                            StatusChips(uiState.filter, onAction)
                            QuoteList(uiState, onAction, barClearance, Modifier.weight(1f))
                        }
                        TonalFab(
                            text = stringResource(R.string.quote_new_action),
                            onClick = { onAction(QuotesAction.New) },
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(bottom = barClearance)
                                .navigationBarsPadding()
                                .padding(AppTheme.spacing.s16)
                                .keepsMessagesAbove(),
                        )
                    }
                    val open = uiState.selectedId
                    if (wide && open != null) {
                        VerticalDivider(
                            thickness = sizes.hairline,
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .consumeWindowInsets(WindowInsets.statusBars),
                        ) { detail(open) }
                    }
                }
            }
        }
    }
}

/** Los chips de estado, de una sola elección. */
@Composable
private fun StatusChips(filter: QuoteStatus, onAction: (QuotesAction) -> Unit) {
    CompactChipRow(
        options = QuoteStatus.entries,
        label = { stringResource(filterLabel(it)) },
        selected = setOf(filter),
        onToggle = { onAction(QuotesAction.SelectFilter(it)) },
        multiple = false,
        modifier = Modifier.padding(
            horizontal = AppTheme.spacing.s16,
            vertical = AppTheme.spacing.s8,
        ),
    )
}

@Composable
private fun QuoteList(
    uiState: QuotesUiState,
    onAction: (QuotesAction) -> Unit,
    barClearance: Dp,
    modifier: Modifier = Modifier,
) {
    // Deja libre lo que ocupa el botón flotante, para que no tape la última fila.
    val bottom = AppTheme.sizes.touchTarget + AppTheme.spacing.s40 + barClearance
    Box(modifier.fillMaxWidth()) {
        when (val content = uiState.content) {
            QuotesContent.Loading -> DelayedSkeleton { ListSkeleton() }

            QuotesContent.Failed -> CenteredState {
                EmptyState(
                    icon = R.drawable.ic_error,
                    title = stringResource(R.string.state_error_read),
                ) {
                    PrimaryActionButton(
                        text = stringResource(R.string.action_retry),
                        onClick = { onAction(QuotesAction.Retry) },
                        variant = ActionVariant.TONAL,
                    )
                }
            }

            QuotesContent.Empty -> CenteredState {
                EmptyState(
                    icon = R.drawable.ic_draft,
                    title = stringResource(R.string.quote_empty_title),
                    body = stringResource(R.string.quote_empty_body),
                ) {
                    PrimaryActionButton(
                        text = stringResource(R.string.quote_new_action),
                        onClick = { onAction(QuotesAction.New) },
                        variant = ActionVariant.TONAL,
                    )
                }
            }

            QuotesContent.FilterEmpty -> CenteredState {
                EmptyState(
                    icon = R.drawable.ic_search,
                    title = stringResource(R.string.quote_filter_empty),
                ) {
                    PrimaryActionButton(
                        text = stringResource(R.string.action_clear_filters),
                        onClick = { onAction(QuotesAction.SelectFilter(QuoteStatus.OPEN)) },
                        variant = ActionVariant.TONAL,
                    )
                }
            }

            is QuotesContent.Rows -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = bottom),
            ) {
                items(content.items, key = { it.id }) { item ->
                    QuoteRowItem(item) { onAction(QuotesAction.Open(item.id)) }
                }
            }
        }
    }
}

@Composable
private fun QuoteRowItem(item: QuoteItem, onClick: () -> Unit) {
    val formats = LocalAppFormats.current
    val (status, tone) = when {
        item.expired -> stringResource(R.string.quote_status_expired) to StatusTone.WARNING

        item.status == QuoteStatus.CONVERTED ->
            stringResource(R.string.quote_status_converted) to StatusTone.NEUTRAL

        item.status == QuoteStatus.CANCELLED ->
            stringResource(R.string.quote_status_cancelled) to StatusTone.NEUTRAL

        else -> null to StatusTone.NEUTRAL
    }
    DocumentRow(
        title = quoteTitle(item.id),
        customer = item.customerName ?: stringResource(R.string.quote_no_customer),
        detail = item.validUntil?.let {
            stringResource(R.string.quote_valid_until_row, formats.date.dayMonth(it))
        },
        total = item.total,
        status = status,
        statusTone = tone,
        onClick = onClick,
    )
}

/** `Cotización 41`, con el número en el formato de la región. */
@Composable
internal fun quoteTitle(id: Long): String = stringResource(
    R.string.quote_detail_title,
    LocalAppFormats.current.quantity.format(QuantityMilli.ofUnits(id), allowsFraction = false),
)

/** El nombre del chip de cada estado. */
private fun filterLabel(status: QuoteStatus): Int = when (status) {
    QuoteStatus.OPEN -> R.string.quote_filter_open
    QuoteStatus.CONVERTED -> R.string.quote_filter_converted
    QuoteStatus.CANCELLED -> R.string.quote_filter_cancelled
}

@Composable
private fun CenteredState(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}

/** Seis filas de esqueleto con la forma de la lista. */
@Composable
private fun ListSkeleton() {
    Column(
        Modifier.fillMaxWidth().padding(AppTheme.spacing.s16),
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12),
    ) {
        repeat(SKELETON_ROWS) {
            SkeletonBlock(Modifier.fillMaxWidth().heightIn(min = AppTheme.sizes.listRow))
        }
    }
}

/** Los tres segmentos de siempre de Registros, que van antes de `Cotizaciones`. */
private const val BASE_SEGMENTS = 3

// Desde 840 dp, lista y detalle lado a lado.
private val EXPANDED_WIDTH = 840.dp
private const val SKELETON_ROWS = 6
