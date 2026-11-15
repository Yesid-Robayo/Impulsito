package io.github.yesidrobayo.shopledger.ui.reorder

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.Scale
import io.github.yesidrobayo.shopledger.domain.rule.stock.ReorderSupplierFilter
import io.github.yesidrobayo.shopledger.ui.components.ActionVariant
import io.github.yesidrobayo.shopledger.ui.components.AppBottomSheet
import io.github.yesidrobayo.shopledger.ui.components.AppTopBar
import io.github.yesidrobayo.shopledger.ui.components.CompactChipRow
import io.github.yesidrobayo.shopledger.ui.components.DelayedSkeleton
import io.github.yesidrobayo.shopledger.ui.components.EmptyState
import io.github.yesidrobayo.shopledger.ui.components.EmptyStateInline
import io.github.yesidrobayo.shopledger.ui.components.FilterBar
import io.github.yesidrobayo.shopledger.ui.components.InlineTextButton
import io.github.yesidrobayo.shopledger.ui.components.LocationSwitcher
import io.github.yesidrobayo.shopledger.ui.components.MenuChip
import io.github.yesidrobayo.shopledger.ui.components.MoneySize
import io.github.yesidrobayo.shopledger.ui.components.MoneyText
import io.github.yesidrobayo.shopledger.ui.components.PrimaryActionButton
import io.github.yesidrobayo.shopledger.ui.components.QuantityStepper
import io.github.yesidrobayo.shopledger.ui.components.SkeletonBlock
import io.github.yesidrobayo.shopledger.ui.components.StatusTag
import io.github.yesidrobayo.shopledger.ui.components.StatusTone
import io.github.yesidrobayo.shopledger.ui.components.TopBarNavigation
import io.github.yesidrobayo.shopledger.ui.components.UnitLabel
import io.github.yesidrobayo.shopledger.ui.components.WholeNumberField
import io.github.yesidrobayo.shopledger.ui.components.quantityText
import io.github.yesidrobayo.shopledger.ui.components.unitLabel
import io.github.yesidrobayo.shopledger.ui.stock.locationChoices
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme

/** La etiqueta de la lista, para las pruebas. */
const val REORDER_LIST_TAG = "reorder-list"

/**
 * La reposición sugerida: arriba, los filtros y los días a cubrir; debajo, un grupo por proveedor
 * habitual, con `Sin proveedor` al final. Es pura: todo llega en [uiState] y sale por [onAction].
 *
 * Cada fila dice lo que queda y para cuántos días alcanza, y trae la cantidad a comprar en un
 * selector que se cambia antes de comprar. Al final de cada grupo, `Compartir lista` y `Crear
 * compra`, que solo llevan lo que no va en cero. El costo estimado va en modo discreto; las
 * cantidades y los días no. Con una sola ubicación no aparece el selector de ubicación.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ReorderScreen(
    uiState: ReorderUiState,
    onAction: (ReorderAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    var sheet by rememberSaveable { mutableStateOf<String?>(null) }
    Surface(
        modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(Modifier.fillMaxSize()) {
            AppTopBar(
                title = stringResource(R.string.reorder_title),
                navigation = TopBarNavigation.BACK,
                onNavigate = { onAction(ReorderAction.Back) },
                navigationLabel = stringResource(R.string.a11y_back),
            )
            when {
                uiState.loadFailed -> EmptyState(
                    icon = R.drawable.ic_error,
                    title = stringResource(R.string.state_error_read),
                    modifier = Modifier.padding(top = AppTheme.spacing.s24),
                ) {
                    PrimaryActionButton(
                        text = stringResource(R.string.action_retry),
                        onClick = { onAction(ReorderAction.Retry) },
                        variant = ActionVariant.TONAL,
                    )
                }

                uiState.loading -> LoadingList()

                else -> ReorderList(uiState, onAction, onOpenSheet = { sheet = it })
            }
        }
    }
    when (sheet) {
        SUPPLIER_SHEET -> SupplierSheet(uiState, onAction) { sheet = null }
        CATEGORY_SHEET -> CategorySheet(uiState, onAction) { sheet = null }
    }
}

@Composable
private fun LoadingList() {
    DelayedSkeleton {
        Column(
            Modifier.padding(AppTheme.spacing.s16),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12),
        ) {
            repeat(SKELETON_ROWS) {
                SkeletonBlock(Modifier.fillMaxWidth().height(AppTheme.sizes.listRow))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReorderList(
    uiState: ReorderUiState,
    onAction: (ReorderAction) -> Unit,
    onOpenSheet: (String) -> Unit,
) {
    val spacing = AppTheme.spacing
    LazyColumn(
        Modifier
            .fillMaxSize()
            .wrapContentWidth()
            .widthIn(max = AppTheme.sizes.maxContentWidth)
            .fillMaxWidth()
            .testTag(REORDER_LIST_TAG),
        contentPadding = PaddingValues(bottom = spacing.s24),
    ) {
        item(key = FILTERS, contentType = FILTERS) { Filters(uiState, onAction, onOpenSheet) }
        when (uiState.empty) {
            ReorderEmpty.NEEDS_SALES -> item(key = EMPTY, contentType = EMPTY) {
                EmptyState(
                    icon = R.drawable.ic_inventory_2,
                    title = stringResource(R.string.reorder_empty_title),
                    body = stringResource(R.string.reorder_empty_body),
                    modifier = Modifier.padding(top = spacing.s24),
                )
            }

            ReorderEmpty.NOTHING_TO_RESTOCK -> item(key = EMPTY, contentType = EMPTY) {
                EmptyStateInline(
                    title = stringResource(R.string.reorder_nothing_title),
                    modifier = Modifier.padding(horizontal = spacing.s16),
                )
            }

            null -> Unit
        }
        for (group in uiState.groups) {
            stickyHeader(key = group.key, contentType = HEADER) { GroupHeader(group) }
            items(
                group.lines,
                key = { "${group.key}-${it.productId}" },
                contentType = { ROW },
            ) { line -> ReorderRow(line, onAction) }
            item(key = "${group.key}-actions", contentType = ACTIONS) {
                GroupActions(group, onAction)
            }
        }
    }
}

@Composable
private fun Filters(
    uiState: ReorderUiState,
    onAction: (ReorderAction) -> Unit,
    onOpenSheet: (String) -> Unit,
) {
    val spacing = AppTheme.spacing
    Column(
        Modifier.padding(horizontal = spacing.s16, vertical = spacing.s8),
        verticalArrangement = Arrangement.spacedBy(spacing.s12),
    ) {
        FilterBar {
            val supplierLabel = when (val filter = uiState.supplierFilter) {
                ReorderSupplierFilter.All -> stringResource(R.string.reorder_supplier_filter)

                ReorderSupplierFilter.Without -> stringResource(R.string.reorder_no_supplier)

                is ReorderSupplierFilter.Of ->
                    uiState.suppliers.firstOrNull { it.id == filter.supplierId }?.details?.name
                        ?: stringResource(R.string.reorder_supplier_filter)
            }
            MenuChip(
                label = supplierLabel,
                onClick = { onOpenSheet(SUPPLIER_SHEET) },
                active = uiState.supplierFilter != ReorderSupplierFilter.All,
            )
            if (uiState.categories.isNotEmpty()) {
                val category = uiState.categories.firstOrNull { it.id == uiState.categoryId }
                MenuChip(
                    label = category?.details?.name
                        ?: stringResource(R.string.reorder_category_filter),
                    onClick = { onOpenSheet(CATEGORY_SHEET) },
                    active = category != null,
                )
            }
            // Solo con más de una ubicación: con una, no dibuja nada.
            LocationSwitcher(
                locations = locationChoices(uiState.locations),
                selectedId = uiState.locationId,
                onSelect = { onAction(ReorderAction.LocationChange(it)) },
                allowAll = true,
                onManage = { onAction(ReorderAction.ManageLocations) },
            )
        }
        WholeNumberField(
            value = uiState.coverText,
            onValueChange = { onAction(ReorderAction.CoverChange(it)) },
            label = stringResource(R.string.reorder_cover_label),
            suffix = stringResource(R.string.reorder_cover_suffix),
            error = stringResource(R.string.error_value_high).takeIf { uiState.coverTooHigh },
            imeAction = ImeAction.Done,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun GroupHeader(group: ReorderGroupUi) {
    val spacing = AppTheme.spacing
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth()) {
            // Un solo encabezado: el proveedor, cuántos productos y el costo estimado.
            SideBySide(
                stacked = LocalDensity.current.fontScale >= STACKED_FONT_SCALE,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = AppTheme.sizes.touchTarget)
                    .semantics(mergeDescendants = true) { heading() }
                    .padding(horizontal = spacing.s16, vertical = spacing.s8),
                first = { modifier ->
                    Column(modifier) {
                        Text(
                            group.supplierName ?: stringResource(R.string.reorder_no_supplier),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            pluralStringResource(
                                R.plurals.reorder_group_products,
                                group.lines.size,
                                group.lines.size,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                second = {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            stringResource(R.string.reorder_estimated_cost_label),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        MoneyText(group.estimatedCost, MoneySize.ROW, sensitive = true)
                    }
                },
            )
            HorizontalDivider(
                thickness = AppTheme.sizes.hairline,
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }
    }
}

@Composable
private fun ReorderRow(line: ReorderLineUi, onAction: (ReorderAction) -> Unit) {
    val spacing = AppTheme.spacing
    val base = line.unit?.let { unitLabel(it) } ?: NO_UNIT
    val onHand = quantityText(line.onHand, base)
    val amount = amountText(line, base)
    val days = line.daysLeft?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt()
    val spoken = days?.let {
        pluralStringResource(R.plurals.a11y_reorder_row, it, line.name, onHand, it, amount)
    }
    SideBySide(
        stacked = LocalDensity.current.fontScale >= STACKED_FONT_SCALE,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = AppTheme.sizes.listRow)
            .padding(horizontal = spacing.s16, vertical = spacing.s8),
        first = { modifier ->
            Column(
                modifier
                    .semantics(mergeDescendants = true) {
                        spoken?.let { contentDescription = it }
                    },
                verticalArrangement = Arrangement.spacedBy(spacing.s4),
            ) {
                Text(line.name, style = MaterialTheme.typography.bodyLarge)
                if (line.soldOut) {
                    StatusTag(
                        text = stringResource(R.string.term_out_of_stock),
                        icon = R.drawable.ic_block,
                        tone = StatusTone.LOSS,
                    )
                }
                val status = when {
                    !line.recentSales -> stringResource(R.string.reorder_no_recent_sales)

                    days != null && !line.soldOut ->
                        pluralStringResource(R.plurals.reorder_row_status, days, onHand, days)

                    else -> null
                }
                status?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        second = {
            // Los pasos son de unidades enteras, de compra o de la base: no hay hoja de cantidad.
            QuantityStepper(
                quantity = line.quantity,
                unit = base.copy(allowsFraction = false),
                productName = line.name,
                onDecrement = { onAction(ReorderAction.Decrement(line.productId)) },
                onIncrement = { onAction(ReorderAction.Increment(line.productId)) },
                valueText = amount,
            )
        },
    )
}

/**
 * Dos partes lado a lado, la primera con el ancho que sobra; con letra grande, una debajo de la
 * otra y la segunda al borde final, para que ningún nombre quede partido letra por letra.
 */
@Composable
private fun SideBySide(
    stacked: Boolean,
    modifier: Modifier,
    first: @Composable (Modifier) -> Unit,
    second: @Composable () -> Unit,
) {
    val gap = Arrangement.spacedBy(AppTheme.spacing.s12)
    if (stacked) {
        Column(modifier, verticalArrangement = gap, horizontalAlignment = Alignment.End) {
            first(Modifier.fillMaxWidth())
            second()
        }
    } else {
        Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = gap) {
            first(Modifier.weight(1f))
            second()
        }
    }
}

/**
 * La cantidad a comprar como se lee: con unidad de compra, `2 cajas (48 und)`; sin ella, en la
 * unidad base. Si la cantidad dejó de ser un múltiplo de la de compra, va solo en la base.
 */
@Composable
private fun amountText(line: ReorderLineUi, base: UnitLabel): String {
    val inBase = quantityText(line.quantity, base)
    val unit = line.purchaseUnit
    val factor = line.purchaseFactor
    if (unit == null || factor == null || line.quantity.milli % factor != 0L) return inBase
    val inUnit = QuantityMilli(line.quantity.milli / factor * Scale.MILLI)
    val purchase = quantityText(inUnit, unitLabel(unit))
    return stringResource(R.string.reorder_purchase_units, purchase, inBase)
}

@Composable
private fun GroupActions(group: ReorderGroupUi, onAction: (ReorderAction) -> Unit) {
    val spacing = AppTheme.spacing
    // Con todo en cero no hay qué compartir ni qué comprar.
    if (group.toBuy.isEmpty()) return
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.s16, vertical = spacing.s8),
        horizontalArrangement = Arrangement.spacedBy(spacing.s12, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        InlineTextButton(
            stringResource(R.string.reorder_share_action),
            { onAction(ReorderAction.Share(group.supplierId)) },
        )
        PrimaryActionButton(
            text = stringResource(R.string.reorder_create_purchase_action),
            onClick = { onAction(ReorderAction.CreatePurchase(group.supplierId)) },
            variant = ActionVariant.TONAL,
        )
    }
}

@Composable
private fun SupplierSheet(
    uiState: ReorderUiState,
    onAction: (ReorderAction) -> Unit,
    onDismiss: () -> Unit,
) {
    val options: List<ReorderSupplierFilter> = buildList {
        add(ReorderSupplierFilter.All)
        uiState.suppliers.forEach { add(ReorderSupplierFilter.Of(it.id)) }
        add(ReorderSupplierFilter.Without)
    }
    val all = stringResource(R.string.customer_filter_all)
    val without = stringResource(R.string.reorder_no_supplier)
    AppBottomSheet(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.reorder_supplier_filter),
    ) {
        CompactChipRow(
            options = options,
            label = { filter ->
                when (filter) {
                    ReorderSupplierFilter.All -> all

                    ReorderSupplierFilter.Without -> without

                    is ReorderSupplierFilter.Of ->
                        uiState.suppliers.first { it.id == filter.supplierId }.details.name
                }
            },
            selected = setOf(uiState.supplierFilter),
            onToggle = {
                onAction(ReorderAction.SupplierFilterChange(it))
                onDismiss()
            },
            multiple = false,
        )
    }
}

@Composable
private fun CategorySheet(
    uiState: ReorderUiState,
    onAction: (ReorderAction) -> Unit,
    onDismiss: () -> Unit,
) {
    val options: List<Long?> = listOf<Long?>(null) + uiState.categories.map { it.id }
    val all = stringResource(R.string.filter_all_categories)
    AppBottomSheet(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.reorder_category_filter),
    ) {
        CompactChipRow(
            options = options,
            label = { id ->
                val chosen = uiState.categories.firstOrNull { it.id == id }
                chosen?.details?.name ?: all
            },
            selected = setOf(uiState.categoryId),
            onToggle = {
                onAction(ReorderAction.CategoryChange(it))
                onDismiss()
            },
            multiple = false,
        )
    }
}

/** Una unidad sin nombre, si la del producto no se leyó: la cantidad sigue visible. */
private val NO_UNIT = UnitLabel(symbol = "", allowsFraction = true)

/** Desde esta escala de letra, las filas y los encabezados se apilan. */
private const val STACKED_FONT_SCALE = 1.5f
private const val SUPPLIER_SHEET = "supplier"
private const val CATEGORY_SHEET = "category"
private const val SKELETON_ROWS = 6
private const val FILTERS = "filters"
private const val EMPTY = "empty"
private const val HEADER = "header"
private const val ROW = "row"
private const val ACTIONS = "actions"
