package io.github.yesidrobayo.shopledger.ui.sale

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleHistoryRow
import io.github.yesidrobayo.shopledger.ui.components.ActionVariant
import io.github.yesidrobayo.shopledger.ui.components.AppBottomSheet
import io.github.yesidrobayo.shopledger.ui.components.AppTopBar
import io.github.yesidrobayo.shopledger.ui.components.CompactChipRow
import io.github.yesidrobayo.shopledger.ui.components.DelayedSkeleton
import io.github.yesidrobayo.shopledger.ui.components.EmptyState
import io.github.yesidrobayo.shopledger.ui.components.EmptyStateInline
import io.github.yesidrobayo.shopledger.ui.components.InlineTextButton
import io.github.yesidrobayo.shopledger.ui.components.MoneySize
import io.github.yesidrobayo.shopledger.ui.components.MoneyText
import io.github.yesidrobayo.shopledger.ui.components.MoneyTone
import io.github.yesidrobayo.shopledger.ui.components.PeriodSelector
import io.github.yesidrobayo.shopledger.ui.components.PrimaryActionButton
import io.github.yesidrobayo.shopledger.ui.components.SkeletonBlock
import io.github.yesidrobayo.shopledger.ui.components.TopBarNavigation
import io.github.yesidrobayo.shopledger.ui.components.canGoForward
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.purchase.EXPENSES_SEGMENT
import io.github.yesidrobayo.shopledger.ui.purchase.PURCHASES_SEGMENT
import io.github.yesidrobayo.shopledger.ui.purchase.RecordsSegments
import io.github.yesidrobayo.shopledger.ui.purchase.SALES_SEGMENT
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme

/** Un filtro de la fila de filtros. */
private sealed interface HistoryChip {
    data class Period(val kind: HistoryPeriodKind) : HistoryChip

    data object Method : HistoryChip

    data object Voided : HistoryChip
}

/**
 * El historial de ventas: la raíz de Registros. Encuentra una venta y muestra lo vendido por día.
 *
 * - Filtros arriba: período, método de pago y `Anuladas`. Sin período, el historial no tiene límite.
 * - La lista va por día, con un encabezado fijo con la fecha y lo vendido ese día en ventas activas.
 *   Cada fila dice la hora, el primer producto y cuántos más, el método o `Varios pagos`, y el total.
 *   Una venta anulada va apagada, tachada y con su etiqueta, y no suma en su día.
 * - Se lee por tramos: con años de ventas, desplazar no espera a cargarlo todo.
 * - En una ventana ancha, la venta elegida se abre a la derecha con [detail], sin salir de la lista.
 *
 * Es pura: recibe el estado y avisa cada acción.
 *
 * @param backupBanner el banner del respaldo bajo la barra, que solo aparece si el estado pide
 *   atención.
 */
@Composable
fun SaleHistoryScreen(
    uiState: SaleHistoryUiState,
    onAction: (SaleHistoryAction) -> Unit,
    modifier: Modifier = Modifier,
    detail: (@Composable (Long) -> Unit)? = null,
    backupBanner: @Composable () -> Unit = {},
) {
    Surface(
        modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = detail != null && maxWidth >= EXPANDED_WIDTH
            Column(Modifier.fillMaxSize()) {
                AppTopBar(
                    title = stringResource(R.string.nav_records),
                    navigation = TopBarNavigation.NONE,
                    onNavigate = {},
                    navigationLabel = null,
                )
                backupBanner()
                RecordsSegments(selected = SALES_SEGMENT) { index ->
                    when (index) {
                        PURCHASES_SEGMENT -> onAction(SaleHistoryAction.ShowPurchases)
                        EXPENSES_SEGMENT -> onAction(SaleHistoryAction.ShowExpenses)
                    }
                }
                Row(Modifier.weight(1f)) {
                    val listWidth = if (wide) {
                        Modifier.width(
                            AppTheme.sizes.maxContentWidth,
                        )
                    } else {
                        Modifier.weight(1f)
                    }
                    Column(listWidth.fillMaxHeight()) {
                        Filters(uiState, onAction)
                        HistoryList(uiState, onAction, Modifier.weight(1f))
                    }
                    val selected = uiState.selectedSaleId
                    if (wide && detail != null) {
                        VerticalDivider(
                            thickness = AppTheme.sizes.hairline,
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                        // El panel ya está bajo la barra de la lista: su propia barra no vuelve a
                        // dejar el alto de la barra de estado.
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .consumeWindowInsets(WindowInsets.statusBars),
                        ) {
                            if (selected != null) detail(selected)
                        }
                    }
                }
            }
        }
    }
    if (uiState.methodSheet) MethodSheet(uiState.filters, onAction)
}

/** Período, método y anuladas, en una fila de chips que pasa a varias líneas si no cabe. */
@Composable
private fun Filters(uiState: SaleHistoryUiState, onAction: (SaleHistoryAction) -> Unit) {
    val filters = uiState.filters
    val spacing = AppTheme.spacing
    val methodName = filters.methods.firstOrNull {
        it.id == filters.methodId
    }?.let { methodName(it) }
    val chips = listOf(
        HistoryChip.Period(HistoryPeriodKind.DAY),
        HistoryChip.Period(HistoryPeriodKind.WEEK),
        HistoryChip.Period(HistoryPeriodKind.MONTH),
        HistoryChip.Method,
        HistoryChip.Voided,
    )
    val selected = buildSet {
        filters.periodKind?.let { add(HistoryChip.Period(it)) }
        if (filters.methodId != null) add(HistoryChip.Method)
        if (filters.onlyVoided) add(HistoryChip.Voided)
    }
    Column(Modifier.padding(horizontal = spacing.s16)) {
        CompactChipRow(
            options = chips,
            label = { chip ->
                when (chip) {
                    is HistoryChip.Period -> stringResource(periodLabel(chip.kind))

                    HistoryChip.Method ->
                        methodName
                            ?: stringResource(R.string.filter_payment_method)

                    HistoryChip.Voided -> stringResource(R.string.filter_voided)
                }
            },
            selected = selected,
            onToggle = { chip ->
                when (chip) {
                    is HistoryChip.Period -> onAction(
                        SaleHistoryAction.PeriodKindChange(
                            chip.kind.takeIf { filters.periodKind != it },
                        ),
                    )

                    HistoryChip.Method -> onAction(SaleHistoryAction.OpenMethods)

                    HistoryChip.Voided -> onAction(SaleHistoryAction.ToggleVoided)
                }
            },
            multiple = true,
        )
        val period = filters.period
        val today = uiState.today
        if (period != null && today != null) {
            PeriodSelector(
                period = period,
                onPrevious = { onAction(SaleHistoryAction.PreviousPeriod) },
                onNext = { onAction(SaleHistoryAction.NextPeriod) },
                showNext = canGoForward(period, today),
                today = today,
            )
        }
    }
}

private fun periodLabel(kind: HistoryPeriodKind): Int = when (kind) {
    HistoryPeriodKind.DAY -> R.string.period_day
    HistoryPeriodKind.WEEK -> R.string.period_week
    HistoryPeriodKind.MONTH -> R.string.period_month
}

@Composable
private fun HistoryList(
    uiState: SaleHistoryUiState,
    onAction: (SaleHistoryAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sizes = AppTheme.sizes
    // Las raíces dejan libre el alto de la barra inferior, más lo que sobresale Vender.
    val bottom = sizes.bottomBar + sizes.sellButtonOverhang + AppTheme.spacing.s16
    Box(modifier.fillMaxWidth()) {
        when (val content = uiState.content) {
            HistoryContent.Loading -> DelayedSkeleton { HistorySkeleton() }

            HistoryContent.Empty -> CenteredHistoryState(bottom) {
                EmptyState(
                    icon = R.drawable.ic_receipt_long,
                    title = stringResource(R.string.empty_sales_title),
                    body = stringResource(R.string.empty_sales_body),
                ) {
                    PrimaryActionButton(
                        text = stringResource(R.string.empty_sales_action),
                        onClick = { onAction(SaleHistoryAction.Sell) },
                        variant = ActionVariant.TONAL,
                    )
                }
            }

            HistoryContent.EmptyFiltered -> CenteredHistoryState(bottom) {
                EmptyState(
                    icon = R.drawable.ic_search,
                    title = stringResource(R.string.empty_sales_filtered_title),
                ) {
                    PrimaryActionButton(
                        text = stringResource(R.string.action_clear_filters),
                        onClick = { onAction(SaleHistoryAction.ClearFilters) },
                        variant = ActionVariant.TONAL,
                    )
                }
            }

            HistoryContent.ReadError -> CenteredHistoryState(bottom) {
                EmptyState(
                    icon = R.drawable.ic_error,
                    title = stringResource(R.string.state_error_read),
                ) {
                    PrimaryActionButton(
                        text = stringResource(R.string.action_retry),
                        onClick = { onAction(SaleHistoryAction.Retry) },
                        variant = ActionVariant.TONAL,
                    )
                }
            }

            is HistoryContent.Days -> DayList(
                content,
                uiState.zone,
                uiState.selectedSaleId,
                onAction,
                bottom,
            )
        }
    }
}

@Composable
private fun DayList(
    content: HistoryContent.Days,
    zone: BusinessTimeZone?,
    selected: Long?,
    onAction: (SaleHistoryAction) -> Unit,
    bottom: Dp,
) {
    val listState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    // Al acercarse al final de lo leído, se pide el tramo siguiente.
    LaunchedEffect(listState, content.endReached, content.moreFailed) {
        if (content.endReached || content.moreFailed) return@LaunchedEffect
        snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= info.totalItemsCount - PREFETCH_ROWS
        }.collect { near -> if (near) onAction(SaleHistoryAction.LoadMore) }
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(bottom = bottom),
    ) {
        for (day in content.days) {
            stickyHeader(key = "day-${day.date}", contentType = DAY) { DayHeader(day) }
            items(day.rows, key = { it.id }, contentType = { ROW }) { row ->
                SaleRow(row, zone, row.id == selected) {
                    onAction(SaleHistoryAction.OpenSale(row.id))
                }
            }
        }
        if (!content.endReached) {
            item(key = MORE, contentType = MORE) {
                if (content.moreFailed) {
                    // El tramo siguiente no se pudo leer: lo leído sigue, y se puede reintentar.
                    EmptyStateInline(
                        title = stringResource(R.string.state_error_read),
                        modifier = Modifier.padding(horizontal = AppTheme.spacing.s16),
                    ) {
                        InlineTextButton(
                            stringResource(R.string.action_retry),
                            { onAction(SaleHistoryAction.LoadMore) },
                        )
                    }
                } else {
                    SkeletonBlock(
                        Modifier
                            .fillMaxWidth()
                            .padding(AppTheme.spacing.s16)
                            .heightIn(min = AppTheme.sizes.listRowSingle),
                    )
                }
            }
        }
    }
}

/** El encabezado fijo de un día: la fecha y lo vendido ese día, sobre el papel. */
@Composable
private fun DayHeader(day: HistoryDay) {
    val formats = LocalAppFormats.current
    Column(Modifier.fillMaxWidth()) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = AppTheme.sizes.touchTarget)
                    .padding(horizontal = AppTheme.spacing.s16, vertical = AppTheme.spacing.s8)
                    .semantics(mergeDescendants = true) { heading() },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    formats.date.weekdayDayMonth(day.date),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                MoneyText(day.total, MoneySize.ROW, tone = MoneyTone.MUTED)
            }
        }
        HorizontalDivider(
            thickness = AppTheme.sizes.hairline,
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

/**
 * Una venta: la hora, el primer producto y cuántos más, el método o `Varios pagos`, y el total a la
 * derecha. Anulada, va apagada, tachada y con su etiqueta.
 */
@Composable
private fun SaleRow(
    row: SaleHistoryRow,
    zone: BusinessTimeZone?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val spacing = AppTheme.spacing
    val formats = LocalAppFormats.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val text = if (row.voided) muted else MaterialTheme.colorScheme.onSurface
    val more = if (row.lineCount > 1) {
        stringResource(R.string.sale_more_items, row.lineCount - 1)
    } else {
        null
    }
    val method = if (row.paymentCount > 1) {
        stringResource(R.string.sale_multiple_payments)
    } else {
        row.methodName.orEmpty()
    }
    val scheme = MaterialTheme.colorScheme
    // La venta abierta a la derecha se marca en la lista, como la pestaña activa.
    Surface(color = if (selected) scheme.secondaryContainer else scheme.surface) {
        Column(Modifier.clickable(onClick = onClick)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = AppTheme.sizes.listRow)
                    .padding(horizontal = spacing.s16, vertical = spacing.s12),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.s12),
            ) {
                zone?.let {
                    Text(
                        formats.date.time(row.occurredAt, it),
                        style = MaterialTheme.typography.bodyMedium,
                        color = muted,
                    )
                }
                Column(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(spacing.s2),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(spacing.s4)) {
                        Text(
                            row.firstProductName.orEmpty(),
                            style = MaterialTheme.typography.titleSmall,
                            color = text,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        more?.let {
                            Text(it, style = MaterialTheme.typography.titleSmall, color = muted)
                        }
                    }
                    Text(method, style = MaterialTheme.typography.bodySmall, color = muted)
                }
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(spacing.s4),
                ) {
                    MoneyText(
                        row.total,
                        MoneySize.ROW,
                        tone = if (row.voided) MoneyTone.MUTED else MoneyTone.DEFAULT,
                        voided = row.voided,
                    )
                }
            }
            HorizontalDivider(
                modifier = Modifier.padding(start = spacing.s16),
                thickness = AppTheme.sizes.hairline,
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }
    }
}

/** Elegir el método del filtro, o ninguno para ver todas. */
@Composable
private fun MethodSheet(filters: HistoryFilters, onAction: (SaleHistoryAction) -> Unit) {
    AppBottomSheet(
        onDismissRequest = { onAction(SaleHistoryAction.CloseMethods) },
        title = stringResource(R.string.filter_payment_method),
    ) {
        CompactChipRow(
            options = filters.methods,
            label = { methodName(it) },
            selected = filters.methods.filter { it.id == filters.methodId }.toSet(),
            onToggle = { method ->
                onAction(
                    SaleHistoryAction.MethodChange(
                        method.id.takeIf {
                            it != filters.methodId
                        },
                    ),
                )
            },
            multiple = false,
        )
    }
}

@Composable
private fun CenteredHistoryState(bottom: Dp, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = bottom),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}

/** Ocho filas de esqueleto con la forma de la lista. */
@Composable
private fun HistorySkeleton() {
    Column(
        Modifier.fillMaxWidth().padding(AppTheme.spacing.s16),
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12),
    ) {
        repeat(SKELETON_ROWS) {
            SkeletonBlock(Modifier.fillMaxWidth().heightIn(min = AppTheme.sizes.listRowSingle))
        }
    }
}

// Desde 840 dp, lista y detalle lado a lado.
private val EXPANDED_WIDTH = 840.dp

/** Cuántas filas antes del final se pide el tramo siguiente, para que nunca se vea el borde. */
private const val PREFETCH_ROWS = 15
private const val SKELETON_ROWS = 8
private const val DAY = "day"
private const val ROW = "row"
private const val MORE = "more"
