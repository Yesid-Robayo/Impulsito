package io.github.yesidrobayo.shopledger.ui.purchase

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseHistoryRow
import io.github.yesidrobayo.shopledger.domain.model.purchase.SupplierFilter
import io.github.yesidrobayo.shopledger.ui.components.ActionVariant
import io.github.yesidrobayo.shopledger.ui.components.AppTopBar
import io.github.yesidrobayo.shopledger.ui.components.CheckboxRow
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
import io.github.yesidrobayo.shopledger.ui.components.SegmentedTabs
import io.github.yesidrobayo.shopledger.ui.components.SettingsButton
import io.github.yesidrobayo.shopledger.ui.components.SkeletonBlock
import io.github.yesidrobayo.shopledger.ui.components.StatusTag
import io.github.yesidrobayo.shopledger.ui.components.StatusTone
import io.github.yesidrobayo.shopledger.ui.components.TonalFab
import io.github.yesidrobayo.shopledger.ui.components.TopBarNavigation
import io.github.yesidrobayo.shopledger.ui.components.bottomBarClearance
import io.github.yesidrobayo.shopledger.ui.components.canGoForward
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.sale.FilterSheetLabel
import io.github.yesidrobayo.shopledger.ui.sale.HistoryFilterBar
import io.github.yesidrobayo.shopledger.ui.sale.HistoryFilterSheet
import io.github.yesidrobayo.shopledger.ui.sale.HistoryPeriodSheet
import io.github.yesidrobayo.shopledger.ui.sale.LocalRecordsExtraSegments
import io.github.yesidrobayo.shopledger.ui.sale.RecordsTab
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme

/**
 * El historial de compras: el segmento `Compras` de Registros. Encuentra una compra y muestra lo
 * comprado por día.
 *
 * - Filtros arriba: el período a la izquierda, con sus flechas debajo, y `Filtros` a la derecha,
 *   con el proveedor y `Solo anuladas` en su hoja. Sin período, el historial no tiene límite.
 * - La lista va por día, con un encabezado fijo con la fecha y lo comprado ese día en compras
 *   activas. Cada fila dice el proveedor o `Sin proveedor`, cuántos productos y la referencia del
 *   documento, y el total. Una compra anulada va apagada, y su total tachado lleva la etiqueta
 *   `Anulada`; no suma en su día.
 * - Se lee por tramos: con años de compras, desplazar no espera a cargarlo todo.
 * - En una ventana ancha, la compra elegida se abre a la derecha con [detail].
 * - Los totales son costos: con el modo discreto se ocultan.
 *
 * Es pura: recibe el estado y avisa cada acción.
 *
 * @param asTab si va como segmento de la raíz de Registros: con la misma barra de arriba que
 *   `Ventas`, los segmentos y la barra inferior libre. Si no, es un destino propio, como desde un
 *   proveedor: con atrás, el título `Compras` y sin segmentos.
 * @param backupBanner el banner del respaldo bajo la barra, que solo aparece si el estado pide
 *   atención.
 */
@Composable
fun PurchaseHistoryScreen(
    uiState: PurchaseHistoryUiState,
    onAction: (PurchaseHistoryAction) -> Unit,
    modifier: Modifier = Modifier,
    detail: (@Composable (Long) -> Unit)? = null,
    asTab: Boolean = true,
    backupBanner: @Composable () -> Unit = {},
) {
    val sizes = AppTheme.sizes
    // Como segmento, el botón y el final de la lista dejan libre la barra inferior y Vender.
    val barClearance = if (asTab) bottomBarClearance() else 0.dp
    Surface(
        modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = detail != null && maxWidth >= EXPANDED_WIDTH
            Column(Modifier.fillMaxSize()) {
                RecordsTopBar(
                    asTab = asTab,
                    title = R.string.records_tab_purchases,
                    onBack = { onAction(PurchaseHistoryAction.ShowSales) },
                )
                backupBanner()
                if (asTab) {
                    RecordsSegments(selected = PURCHASES_SEGMENT) { index ->
                        when (index) {
                            SALES_SEGMENT -> onAction(PurchaseHistoryAction.ShowSales)
                            EXPENSES_SEGMENT -> onAction(PurchaseHistoryAction.ShowExpenses)
                        }
                    }
                }
                Row(Modifier.weight(1f)) {
                    val listWidth = if (wide) {
                        Modifier.width(minOf(AppTheme.sizes.maxContentWidth, maxWidth * 2 / 5))
                    } else {
                        Modifier.weight(1f)
                    }
                    Box(listWidth.fillMaxHeight()) {
                        Column(Modifier.fillMaxSize()) {
                            Filters(uiState, onAction)
                            HistoryList(uiState, onAction, barClearance, Modifier.weight(1f))
                        }
                        TonalFab(
                            text = stringResource(R.string.empty_purchases_action),
                            onClick = { onAction(PurchaseHistoryAction.NewPurchase) },
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(bottom = barClearance)
                                .navigationBarsPadding()
                                .padding(AppTheme.spacing.s16),
                        )
                    }
                    val selected = uiState.selectedPurchaseId
                    if (wide && detail != null) {
                        VerticalDivider(
                            thickness = AppTheme.sizes.hairline,
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
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
    if (uiState.periodSheet) {
        HistoryPeriodSheet(
            selected = uiState.filters.periodKind,
            onSelect = { onAction(PurchaseHistoryAction.PeriodKindChange(it)) },
            onDismiss = { onAction(PurchaseHistoryAction.ClosePeriods) },
        )
    }
    if (uiState.filterSheet) FilterSheet(uiState.filters, onAction)
}

/**
 * La barra de arriba de un segmento de Registros. Como segmento es la de la raíz: `Registros`,
 * sin atrás y con los ajustes al borde, igual en los tres, para que cambiar de segmento no mueva
 * nada. Como destino propio lleva atrás y el nombre del segmento, [title].
 *
 * @param actions las acciones propias del segmento, antes de los ajustes.
 */
@Composable
internal fun RecordsTopBar(
    asTab: Boolean,
    @StringRes title: Int,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
) {
    if (asTab) {
        AppTopBar(
            title = stringResource(R.string.nav_records),
            navigation = TopBarNavigation.NONE,
            onNavigate = {},
            navigationLabel = null,
        ) {
            actions()
            SettingsButton()
        }
    } else {
        AppTopBar(
            title = stringResource(title),
            navigation = TopBarNavigation.BACK,
            onNavigate = onBack,
            navigationLabel = stringResource(R.string.a11y_back),
            actions = actions,
        )
    }
}

/**
 * Los segmentos de Registros: `Ventas`, `Compras` y `Gastos`, y detrás los que solo aparecen con su
 * herramienta en uso, como `Cotizaciones`. Elegir uno de los tres de siempre avisa su posición a
 * [onSelect]; uno de los de detrás lo elige Registros directamente.
 */
@Composable
internal fun RecordsSegments(selected: Int, onSelect: (Int) -> Unit) {
    val extra = LocalRecordsExtraSegments.current
    val base = listOf(
        stringResource(R.string.records_tab_sales),
        stringResource(R.string.records_tab_purchases),
        stringResource(R.string.records_tab_expenses),
    )
    SegmentedTabs(
        options = base + extra.tabs.map { stringResource(recordsTabLabel(it)) },
        selectedIndex = selected,
        onSelect = { index ->
            if (index < base.size) onSelect(index) else extra.select(extra.tabs[index - base.size])
        },
        modifier = Modifier.padding(horizontal = AppTheme.spacing.s16),
    )
}

/** El nombre de un segmento de Registros. */
@StringRes
internal fun recordsTabLabel(tab: RecordsTab): Int = when (tab) {
    RecordsTab.SALES -> R.string.records_tab_sales
    RecordsTab.PURCHASES -> R.string.records_tab_purchases
    RecordsTab.EXPENSES -> R.string.records_tab_expenses
    RecordsTab.QUOTES -> R.string.quote_tab
    RecordsTab.ORDERS -> R.string.order_tab
}

/**
 * El período y el botón de filtros en una barra, y debajo las flechas del período elegido. Con un
 * solo filtro puesto, el botón dice cuál: el proveedor, `Sin proveedor` o `Anuladas`. Así, abierta
 * desde un proveedor, la lista dice desde el principio por quién está filtrada.
 */
@Composable
private fun Filters(uiState: PurchaseHistoryUiState, onAction: (PurchaseHistoryAction) -> Unit) {
    val filters = uiState.filters
    val supplierLabel = when (filters.supplier) {
        SupplierFilter.Any -> null
        SupplierFilter.None -> stringResource(R.string.purchase_no_supplier)
        is SupplierFilter.Of -> filters.supplierName
    }
    val single = when {
        filters.sheetFilterCount != 1 -> null
        filters.supplier != SupplierFilter.Any -> supplierLabel
        else -> stringResource(R.string.filter_voided)
    }
    Column(Modifier.padding(horizontal = AppTheme.spacing.s16)) {
        HistoryFilterBar(
            periodKind = filters.periodKind,
            onOpenPeriods = { onAction(PurchaseHistoryAction.OpenPeriods) },
            sheetFilterCount = filters.sheetFilterCount,
            singleFilter = single,
            onOpenFilters = { onAction(PurchaseHistoryAction.OpenFilters) },
        )
        val period = filters.period
        val today = uiState.today
        if (period != null && today != null) {
            PeriodSelector(
                period = period,
                onPrevious = { onAction(PurchaseHistoryAction.PreviousPeriod) },
                onNext = { onAction(PurchaseHistoryAction.NextPeriod) },
                showNext = canGoForward(period, today),
                today = today,
            )
        }
    }
}

@Composable
private fun HistoryList(
    uiState: PurchaseHistoryUiState,
    onAction: (PurchaseHistoryAction) -> Unit,
    barClearance: Dp,
    modifier: Modifier = Modifier,
) {
    // Deja libre lo que ocupa el botón flotante, para que no tape la última fila, y la barra
    // inferior si se ve.
    val bottom = AppTheme.sizes.touchTarget + AppTheme.spacing.s40 + barClearance
    Box(modifier.fillMaxWidth()) {
        when (val content = uiState.content) {
            PurchaseHistoryContent.Loading -> DelayedSkeleton { HistorySkeleton() }

            PurchaseHistoryContent.Empty -> CenteredState {
                EmptyState(
                    icon = R.drawable.ic_inventory_2,
                    title = stringResource(R.string.empty_purchases_title),
                    body = stringResource(R.string.empty_purchases_body),
                ) {
                    PrimaryActionButton(
                        text = stringResource(R.string.empty_purchases_action),
                        onClick = { onAction(PurchaseHistoryAction.NewPurchase) },
                        variant = ActionVariant.TONAL,
                    )
                }
            }

            PurchaseHistoryContent.EmptyFiltered -> CenteredState {
                EmptyState(
                    icon = R.drawable.ic_search,
                    title = stringResource(R.string.empty_purchases_filtered_title),
                ) {
                    PrimaryActionButton(
                        text = stringResource(R.string.action_clear_filters),
                        onClick = { onAction(PurchaseHistoryAction.ClearFilters) },
                        variant = ActionVariant.TONAL,
                    )
                }
            }

            PurchaseHistoryContent.ReadError -> CenteredState {
                EmptyState(
                    icon = R.drawable.ic_error,
                    title = stringResource(R.string.state_error_read),
                ) {
                    PrimaryActionButton(
                        text = stringResource(R.string.action_retry),
                        onClick = { onAction(PurchaseHistoryAction.Retry) },
                        variant = ActionVariant.TONAL,
                    )
                }
            }

            is PurchaseHistoryContent.Days ->
                DayList(content, uiState.selectedPurchaseId, onAction, bottom, uiState.openCredit)
        }
    }
}

@Composable
private fun DayList(
    content: PurchaseHistoryContent.Days,
    selected: Long?,
    onAction: (PurchaseHistoryAction) -> Unit,
    bottom: androidx.compose.ui.unit.Dp,
    openCredit: Set<Long>?,
) {
    val listState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    // Al acercarse al final de lo leído, se pide el tramo siguiente.
    LaunchedEffect(listState, content.endReached, content.moreFailed) {
        if (content.endReached || content.moreFailed) return@LaunchedEffect
        snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= info.totalItemsCount - PREFETCH_ROWS
        }.collect { near -> if (near) onAction(PurchaseHistoryAction.LoadMore) }
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(bottom = bottom),
    ) {
        for (day in content.days) {
            stickyHeader(key = "day-${day.date}", contentType = DAY) { DayHeader(day) }
            items(day.rows, key = { it.id }, contentType = { ROW }) { row ->
                PurchaseRow(row, row.id == selected, creditTagOf(row, openCredit)) {
                    onAction(PurchaseHistoryAction.OpenPurchase(row.id))
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
                            { onAction(PurchaseHistoryAction.LoadMore) },
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

/** El encabezado fijo de un día: la fecha y lo comprado ese día, sobre el papel. */
@Composable
private fun DayHeader(day: PurchaseDay) {
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
                MoneyText(day.total, MoneySize.ROW, tone = MoneyTone.MUTED, sensitive = true)
            }
        }
        HorizontalDivider(
            thickness = AppTheme.sizes.hairline,
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

/**
 * Una compra: el proveedor o `Sin proveedor`, cuántos productos y la referencia, y el total a la
 * derecha. Anulada, va apagada, tachada y con su etiqueta.
 */
@Composable
private fun PurchaseRow(
    row: PurchaseHistoryRow,
    selected: Boolean,
    creditTag: CreditTag?,
    onClick: () -> Unit,
) {
    val spacing = AppTheme.spacing
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val text = if (row.voided) muted else MaterialTheme.colorScheme.onSurface
    val products =
        pluralStringResource(R.plurals.sale_summary_products, row.lineCount, row.lineCount)
    val detail = row.invoiceReference?.let {
        stringResource(R.string.purchase_row_detail, products, it)
    } ?: products
    val scheme = MaterialTheme.colorScheme
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
                Column(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(spacing.s2),
                ) {
                    Text(
                        row.supplierName ?: stringResource(R.string.purchase_no_supplier),
                        style = MaterialTheme.typography.titleSmall,
                        color = text,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(detail, style = MaterialTheme.typography.bodySmall, color = muted)
                    creditTag?.let { CreditStatusTag(it) }
                }
                MoneyText(
                    row.total,
                    MoneySize.ROW,
                    tone = if (row.voided) MoneyTone.MUTED else MoneyTone.DEFAULT,
                    voided = row.voided,
                    sensitive = true,
                )
            }
            HorizontalDivider(
                modifier = Modifier.padding(start = spacing.s16),
                thickness = AppTheme.sizes.hairline,
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }
    }
}

/** Cómo va una compra a plazo: con su vencimiento, a plazo sin vencimiento, o ya pagada. */
internal sealed interface CreditTag {
    data class DueOn(val date: BusinessDate) : CreditTag

    data object Open : CreditTag

    data object Paid : CreditTag
}

/**
 * La etiqueta de [row]: solo una compra a plazo vigente la lleva, y solo cuando ya se sabe qué
 * sigue abierto. Una que ya no tiene nada por pagar dice `Pagada`.
 */
internal fun creditTagOf(row: PurchaseHistoryRow, openCredit: Set<Long>?): CreditTag? {
    if (!row.onCredit || row.voided || openCredit == null) return null
    if (row.id !in openCredit) return CreditTag.Paid
    return row.dueDate?.let { CreditTag.DueOn(it) } ?: CreditTag.Open
}

@Composable
private fun CreditStatusTag(tag: CreditTag) {
    val formats = LocalAppFormats.current
    when (tag) {
        is CreditTag.DueOn -> StatusTag(
            stringResource(R.string.purchase_due_status, formats.date.dayMonthYear(tag.date)),
            icon = R.drawable.ic_schedule,
            tone = StatusTone.INFO,
        )

        CreditTag.Open -> StatusTag(
            stringResource(R.string.purchase_payment_credit),
            icon = R.drawable.ic_schedule,
            tone = StatusTone.INFO,
        )

        CreditTag.Paid -> StatusTag(
            stringResource(R.string.purchase_payment_paid),
            icon = R.drawable.ic_check,
            tone = StatusTone.PROFIT,
        )
    }
}

/**
 * La hoja de filtros: el proveedor, `Sin proveedor` o ninguno para ver todas, y `Solo anuladas`.
 * Tocar el proveedor elegido lo quita. Cada elección se aplica al tocarla.
 */
@Composable
private fun FilterSheet(filters: PurchaseFilters, onAction: (PurchaseHistoryAction) -> Unit) {
    val options = listOf<SupplierFilter>(SupplierFilter.None) +
        filters.suppliers.map { SupplierFilter.Of(it.id) }
    val names = filters.suppliers.associate { it.id to it.name }
    val noSupplier = stringResource(R.string.purchase_no_supplier)
    HistoryFilterSheet(
        activeCount = filters.sheetFilterCount,
        onClear = { onAction(PurchaseHistoryAction.ClearSheetFilters) },
        onDismiss = { onAction(PurchaseHistoryAction.CloseFilters) },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8)) {
            FilterSheetLabel(stringResource(R.string.filter_supplier))
            CompactChipRow(
                options = options,
                label = { option ->
                    when (option) {
                        SupplierFilter.Any, SupplierFilter.None -> noSupplier
                        is SupplierFilter.Of -> names[option.supplierId].orEmpty()
                    }
                },
                selected = setOf(filters.supplier),
                onToggle = { option ->
                    // Tocar el elegido lo quita: vuelven todas las compras.
                    val next = if (option == filters.supplier) SupplierFilter.Any else option
                    onAction(PurchaseHistoryAction.SupplierChange(next))
                },
                multiple = false,
            )
            CheckboxRow(
                text = stringResource(R.string.filter_only_voided),
                checked = filters.onlyVoided,
                onCheckedChange = { onAction(PurchaseHistoryAction.ToggleVoided) },
            )
        }
    }
}

@Composable
private fun CenteredState(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
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

/** El segmento de ventas, el de compras y el de gastos en [RecordsSegments]. */
internal const val SALES_SEGMENT = 0
internal const val PURCHASES_SEGMENT = 1
internal const val EXPENSES_SEGMENT = 2

// Desde 840 dp, lista y detalle lado a lado.
private val EXPANDED_WIDTH = 840.dp

/** Cuántas filas antes del final se pide el tramo siguiente, para que nunca se vea el borde. */
private const val PREFETCH_ROWS = 15
private const val SKELETON_ROWS = 8
private const val DAY = "day"
private const val ROW = "row"
private const val MORE = "more"
