package io.github.yesidrobayo.shopledger.ui.payable

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.usecase.payable.PayableRow
import io.github.yesidrobayo.shopledger.domain.usecase.payable.PayableStatus
import io.github.yesidrobayo.shopledger.domain.usecase.payable.PayablesOverview
import io.github.yesidrobayo.shopledger.ui.components.ActionVariant
import io.github.yesidrobayo.shopledger.ui.components.AppTopBar
import io.github.yesidrobayo.shopledger.ui.components.CompactChipRow
import io.github.yesidrobayo.shopledger.ui.components.DelayedSkeleton
import io.github.yesidrobayo.shopledger.ui.components.EmptyState
import io.github.yesidrobayo.shopledger.ui.components.MoneySize
import io.github.yesidrobayo.shopledger.ui.components.MoneyText
import io.github.yesidrobayo.shopledger.ui.components.PrimaryActionButton
import io.github.yesidrobayo.shopledger.ui.components.SectionHeader
import io.github.yesidrobayo.shopledger.ui.components.SkeletonBlock
import io.github.yesidrobayo.shopledger.ui.components.StatTile
import io.github.yesidrobayo.shopledger.ui.components.StatTileVariant
import io.github.yesidrobayo.shopledger.ui.components.StatusTag
import io.github.yesidrobayo.shopledger.ui.components.StatusTone
import io.github.yesidrobayo.shopledger.ui.components.TopBarNavigation
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme

/**
 * La lista de cuentas por pagar, sin ViewModel: `Por pagar` y `Vence en 7 días`, los filtros y un
 * proveedor por fila, primero lo vencido, con el saldo a favor en su propio grupo al final. No tiene
 * acción principal: las deudas nacen de las compras.
 *
 * Las cifras de la lista se ocultan con el modo discreto: dicen cuánto costó lo comprado.
 *
 * @param selectedId el proveedor abierto en el panel derecho, en una ventana ancha.
 */
@Composable
fun PayablesScreen(
    uiState: PayablesUiState,
    onAction: (PayablesAction) -> Unit,
    modifier: Modifier = Modifier,
    selectedId: Long? = null,
) {
    val sizes = AppTheme.sizes
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize()) {
            AppTopBar(
                title = stringResource(R.string.payable_title),
                navigation = TopBarNavigation.BACK,
                onNavigate = { onAction(PayablesAction.Back) },
                navigationLabel = stringResource(R.string.a11y_back),
            )
            Column(
                Modifier
                    .weight(1f)
                    .align(Alignment.CenterHorizontally)
                    .widthIn(max = sizes.maxContentWidth)
                    .fillMaxWidth()
                    .navigationBarsPadding(),
            ) {
                PayablesBody(uiState, onAction, selectedId)
            }
        }
    }
}

@Composable
private fun PayablesBody(
    uiState: PayablesUiState,
    onAction: (PayablesAction) -> Unit,
    selectedId: Long?,
) {
    when (val content = uiState.content) {
        PayablesContent.Loading -> DelayedSkeleton {
            Column(
                Modifier.padding(AppTheme.spacing.s16),
                verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12)) {
                    repeat(2) {
                        SkeletonBlock(
                            Modifier.weight(1f).heightIn(min = AppTheme.sizes.listRow * 2),
                        )
                    }
                }
                repeat(SKELETON_ROWS) {
                    SkeletonBlock(Modifier.fillMaxWidth().heightIn(min = AppTheme.sizes.listRow))
                }
            }
        }

        PayablesContent.Empty -> CenteredState {
            EmptyState(
                icon = R.drawable.ic_receipt_long,
                title = stringResource(R.string.payable_empty_title),
                body = stringResource(R.string.payable_empty_body),
            ) {
                PrimaryActionButton(
                    text = stringResource(R.string.payable_empty_action),
                    onClick = { onAction(PayablesAction.NewPurchase) },
                    variant = ActionVariant.TONAL,
                )
            }
        }

        PayablesContent.UnknownZone -> CenteredState {
            EmptyState(
                icon = R.drawable.ic_error,
                title = stringResource(R.string.stats_zone_unknown_title),
            ) {
                PrimaryActionButton(
                    text = stringResource(R.string.stats_zone_unknown_action),
                    onClick = { onAction(PayablesAction.ChooseZone) },
                    variant = ActionVariant.TONAL,
                )
            }
        }

        PayablesContent.ReadError -> CenteredState {
            EmptyState(
                icon = R.drawable.ic_error,
                title = stringResource(R.string.state_error_read),
            ) {
                PrimaryActionButton(
                    text = stringResource(R.string.action_retry),
                    onClick = { onAction(PayablesAction.Retry) },
                    variant = ActionVariant.TONAL,
                )
            }
        }

        is PayablesContent.Ready -> PayableRows(content, uiState.filter, onAction, selectedId)
    }
}

/** Los totales, los filtros y las filas, en una sola lista perezosa. */
@Composable
private fun PayableRows(
    content: PayablesContent.Ready,
    filter: PayableFilter,
    onAction: (PayablesAction) -> Unit,
    selectedId: Long?,
) {
    val spacing = AppTheme.spacing
    val firstInFavor = content.rows.indexOfFirst { it.status == PayableStatus.IN_FAVOR }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = spacing.s16)) {
        item(key = "totals") { Totals(content.overview) }
        item(key = "filters") {
            CompactChipRow(
                options = PayableFilter.entries,
                label = { stringResource(filterLabel(it)) },
                selected = setOf(filter),
                onToggle = { onAction(PayablesAction.FilterChange(it)) },
                multiple = false,
                modifier = Modifier.padding(horizontal = spacing.s16, vertical = spacing.s8),
            )
        }
        if (content.rows.isEmpty()) {
            item(key = "filter-empty") {
                EmptyState(
                    icon = R.drawable.ic_receipt_long,
                    title = stringResource(R.string.payable_filter_empty),
                ) {
                    PrimaryActionButton(
                        text = stringResource(R.string.action_clear_filters),
                        onClick = { onAction(PayablesAction.ClearFilters) },
                        variant = ActionVariant.TONAL,
                    )
                }
            }
        }
        content.rows.forEachIndexed { index, row ->
            // El saldo a favor es un grupo propio: no es una deuda, es lo que el proveedor debe.
            if (index == firstInFavor && filter == PayableFilter.ALL) {
                item(key = "credit-header") {
                    SectionHeader(stringResource(R.string.payable_filter_credit))
                }
            }
            item(key = row.supplier.id) {
                PayableRowItem(
                    row,
                    selected = row.supplier.id == selectedId,
                    onClick = { onAction(PayablesAction.OpenSupplier(row.supplier.id)) },
                )
            }
        }
    }
}

/** `Por pagar` y `Vence en 7 días`, lado a lado; con letra grande, uno sobre otro. */
@Composable
private fun Totals(overview: PayablesOverview) {
    val spacing = AppTheme.spacing
    val stacked = LocalDensity.current.fontScale >= STACKED_FONT_SCALE
    val owed: @Composable (Modifier) -> Unit = {
        StatTile(
            label = stringResource(R.string.payable_total),
            amount = overview.owed,
            variant = StatTileVariant.CARD,
            modifier = it,
            sensitive = true,
        )
    }
    val dueSoon: @Composable (Modifier) -> Unit = {
        StatTile(
            label = stringResource(R.string.payable_due_7_days),
            amount = overview.dueWithinWeek,
            variant = StatTileVariant.CARD,
            modifier = it,
            sensitive = true,
        )
    }
    val padding = Modifier.padding(horizontal = spacing.s16, vertical = spacing.s8)
    if (stacked) {
        Column(padding, verticalArrangement = Arrangement.spacedBy(spacing.s8)) {
            owed(Modifier.fillMaxWidth())
            dueSoon(Modifier.fillMaxWidth())
        }
    } else {
        Row(padding, horizontalArrangement = Arrangement.spacedBy(spacing.s12)) {
            owed(Modifier.weight(1f))
            dueSoon(Modifier.weight(1f))
        }
    }
}

/**
 * Un proveedor: su nombre, lo que vence o desde cuándo está vencido, y el saldo a la derecha. Lo
 * vencido lleva icono, texto y etiqueta: nunca solo el color. Con letra grande, el saldo baja.
 */
@Composable
internal fun PayableRowItem(row: PayableRow, selected: Boolean, onClick: () -> Unit) {
    val spacing = AppTheme.spacing
    val scheme = MaterialTheme.colorScheme
    val stacked = LocalDensity.current.fontScale >= STACKED_FONT_SCALE
    Surface(color = if (selected) scheme.secondaryContainer else scheme.surface) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button, onClick = onClick)
                    .heightIn(min = AppTheme.sizes.listRow)
                    .padding(horizontal = spacing.s16, vertical = spacing.s12)
                    .semantics(mergeDescendants = true) {},
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.s12),
            ) {
                Column(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(spacing.s4),
                ) {
                    Text(
                        row.supplier.details.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = if (row.supplier.isArchived) {
                            scheme.onSurfaceVariant
                        } else {
                            scheme.onSurface
                        },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    DueText(row)
                    if (stacked) RowAmount(row)
                    RowTags(row)
                }
                if (!stacked) RowAmount(row)
            }
            HorizontalDivider(
                modifier = Modifier.padding(start = spacing.s16),
                thickness = AppTheme.sizes.hairline,
                color = scheme.outlineVariant,
            )
        }
    }
}

/** `Vencido desde el …`, con icono, o `Próximo vencimiento: …`. Sin vencimiento no dice nada. */
@Composable
private fun DueText(row: PayableRow) {
    val formats = LocalAppFormats.current
    val overdue = row.overdueSince
    val next = row.nextDue
    val (text, late) = when {
        overdue != null ->
            stringResource(R.string.payable_overdue_since, formats.date.dayMonthYear(overdue)) to
                true

        next != null ->
            stringResource(R.string.payable_next_due, formats.date.dayMonthYear(next)) to false

        else -> return
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s4),
    ) {
        if (late) {
            Icon(
                painterResource(R.drawable.ic_warning),
                contentDescription = null,
                tint = AppTheme.colors.loss,
                modifier = Modifier.size(AppTheme.sizes.iconTag),
            )
        }
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = if (late) AppTheme.colors.loss else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** `Vencida`, `A favor` y `Archivado`, solo cuando aplican. */
@Composable
private fun RowTags(row: PayableRow) {
    val vencida = row.status == PayableStatus.OVERDUE
    val inFavor = row.status == PayableStatus.IN_FAVOR
    if (!vencida && !inFavor && !row.supplier.isArchived) return
    Row(horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8)) {
        if (vencida) {
            StatusTag(
                stringResource(R.string.payable_overdue_tag),
                icon = R.drawable.ic_error,
                tone = StatusTone.LOSS,
            )
        }
        if (inFavor) {
            StatusTag(
                stringResource(R.string.payable_credit_tag),
                icon = R.drawable.ic_savings,
                tone = StatusTone.INFO,
            )
        }
        if (row.supplier.isArchived) StatusTag(stringResource(R.string.payable_archived_tag))
    }
}

/** Lo que se le debe, o lo que tiene a favor el negocio, en tinta. */
@Composable
private fun RowAmount(row: PayableRow) {
    val amount = if (row.status == PayableStatus.IN_FAVOR) row.creditInFavor else row.owed
    MoneyText(amount, MoneySize.ROW, sensitive = true)
}

private fun filterLabel(filter: PayableFilter): Int = when (filter) {
    PayableFilter.ALL -> R.string.payable_filter_all
    PayableFilter.OVERDUE -> R.string.payable_filter_overdue
    PayableFilter.UPCOMING -> R.string.payable_filter_upcoming
    PayableFilter.CREDIT -> R.string.payable_filter_credit
}

/** Un estado que ocupa la pantalla: centrado y desplazable con letra grande. */
@Composable
private fun CenteredState(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}

/** Desde qué tamaño de letra las cifras bajan bajo el texto. */
private const val STACKED_FONT_SCALE = 1.5f
private const val SKELETON_ROWS = 6
