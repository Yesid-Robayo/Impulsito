package io.github.yesidrobayo.shopledger.ui.count

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.sumOrNull
import io.github.yesidrobayo.shopledger.format.NO_VALUE_TEXT
import io.github.yesidrobayo.shopledger.ui.components.ActionState
import io.github.yesidrobayo.shopledger.ui.components.ActionVariant
import io.github.yesidrobayo.shopledger.ui.components.AppCard
import io.github.yesidrobayo.shopledger.ui.components.AppTopBar
import io.github.yesidrobayo.shopledger.ui.components.CompactChipRow
import io.github.yesidrobayo.shopledger.ui.components.ConfirmDialog
import io.github.yesidrobayo.shopledger.ui.components.ConfirmVariant
import io.github.yesidrobayo.shopledger.ui.components.DelayedSkeleton
import io.github.yesidrobayo.shopledger.ui.components.DiscreetValue
import io.github.yesidrobayo.shopledger.ui.components.EmptyState
import io.github.yesidrobayo.shopledger.ui.components.EmptyStateInline
import io.github.yesidrobayo.shopledger.ui.components.InlineTextButton
import io.github.yesidrobayo.shopledger.ui.components.NoticeCard
import io.github.yesidrobayo.shopledger.ui.components.NoticeVariant
import io.github.yesidrobayo.shopledger.ui.components.OverflowAction
import io.github.yesidrobayo.shopledger.ui.components.OverflowMenu
import io.github.yesidrobayo.shopledger.ui.components.PrimaryActionButton
import io.github.yesidrobayo.shopledger.ui.components.QuantityField
import io.github.yesidrobayo.shopledger.ui.components.QuantityStepper
import io.github.yesidrobayo.shopledger.ui.components.SearchField
import io.github.yesidrobayo.shopledger.ui.components.SectionHeader
import io.github.yesidrobayo.shopledger.ui.components.SkeletonBlock
import io.github.yesidrobayo.shopledger.ui.components.StatusTag
import io.github.yesidrobayo.shopledger.ui.components.StatusTone
import io.github.yesidrobayo.shopledger.ui.components.TopBarNavigation
import io.github.yesidrobayo.shopledger.ui.components.UnitLabel
import io.github.yesidrobayo.shopledger.ui.components.keepsMessagesAbove
import io.github.yesidrobayo.shopledger.ui.components.quantityText
import io.github.yesidrobayo.shopledger.ui.components.saveErrorText
import io.github.yesidrobayo.shopledger.ui.components.unitLabel
import io.github.yesidrobayo.shopledger.ui.currency.rememberMoneyDisplay
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.stock.locationName
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme

/**
 * Una sesión de conteo: arriba el progreso, la búsqueda y los filtros; una fila por producto con
 * su cantidad, por pasos si es en unidades enteras o escrita si admite decimales; abajo, `Revisar
 * diferencias`. A ciegas, lo esperado no se ve hasta la revisión.
 *
 * La revisión muestra las diferencias, lo que sobra y lo que falta con modo discreto, el aviso de
 * lo que se movió desde que se abrió, lo que quedó sin contar y `Aplicar {n} ajustes`. Una sesión
 * terminada se ve en solo lectura.
 */
@Composable
fun CountSessionScreen(
    uiState: CountSessionUiState,
    onAction: (CountSessionAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        val title = if (uiState.reviewing) {
            stringResource(R.string.count_review_action)
        } else {
            joined(
                listOfNotNull(
                    stringResource(R.string.count_session_title),
                    uiState.location?.takeIf { uiState.showsLocation }?.let { locationName(it) },
                    uiState.categoryName,
                ),
            ).orEmpty()
        }
        AppTopBar(
            title = title,
            navigation = TopBarNavigation.BACK,
            onNavigate = { onAction(CountSessionAction.Back) },
            navigationLabel = stringResource(R.string.a11y_back),
            titleStyle = MaterialTheme.typography.titleLarge,
            actions = {
                if (!uiState.readOnly && !uiState.loading && !uiState.missing) {
                    OverflowMenu(
                        listOf(
                            OverflowAction(
                                stringResource(R.string.count_cancel_action),
                                { onAction(CountSessionAction.AskCancel) },
                                destructive = true,
                            ),
                        ),
                    )
                }
            },
        )
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = AppTheme.sizes.maxContentWidth).fillMaxSize()) {
                when {
                    uiState.readFailed -> NoticeCard(
                        text = stringResource(R.string.state_error_read),
                        variant = NoticeVariant.ERROR,
                        modifier = Modifier.padding(AppTheme.spacing.s16),
                        actionLabel = stringResource(R.string.action_retry),
                        onAction = { onAction(CountSessionAction.Retry) },
                    )

                    uiState.missing -> EmptyState(
                        icon = R.drawable.ic_fact_check,
                        title = stringResource(R.string.state_error_read),
                    )

                    uiState.loading -> SessionSkeleton()

                    uiState.reviewing || uiState.readOnly -> ReviewContent(uiState, onAction)

                    else -> CountingContent(uiState, onAction)
                }
            }
        }
    }
    when (uiState.dialog) {
        CountDialog.APPLY -> ApplyDialog(uiState, onAction)

        CountDialog.CANCEL -> ConfirmDialog(
            title = stringResource(R.string.count_cancel_confirm_title),
            body = stringResource(R.string.count_cancel_confirm_body),
            confirmLabel = stringResource(R.string.count_cancel_action),
            onConfirm = { onAction(CountSessionAction.Confirm) },
            onDismiss = { onAction(CountSessionAction.DismissDialog) },
            variant = ConfirmVariant.DESTRUCTIVE,
        )

        null -> Unit
    }
}

@Composable
private fun SessionSkeleton() {
    DelayedSkeleton {
        Column(
            Modifier.padding(AppTheme.spacing.s16),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12),
        ) {
            repeat(SKELETON_ROWS) {
                SkeletonBlock(Modifier.fillMaxWidth().heightIn(min = AppTheme.sizes.listRow))
            }
        }
    }
}

@Composable
private fun ColumnScope.CountingContent(
    uiState: CountSessionUiState,
    onAction: (CountSessionAction) -> Unit,
) {
    val spacing = AppTheme.spacing
    Column(
        Modifier.padding(horizontal = spacing.s16, vertical = spacing.s8),
        verticalArrangement = Arrangement.spacedBy(spacing.s8),
    ) {
        LinearProgressIndicator(
            progress = { progressOf(uiState.counted, uiState.total) },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            stringResource(R.string.count_progress, uiState.counted, uiState.total),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SearchField(
            query = uiState.query,
            onQueryChange = { onAction(CountSessionAction.Query(it)) },
            placeholder = stringResource(R.string.sale_search_hint),
        )
        CompactChipRow(
            options = CountFilter.entries,
            label = {
                stringResource(
                    when (it) {
                        CountFilter.PENDING -> R.string.count_filter_pending
                        CountFilter.DONE -> R.string.count_filter_done
                    },
                )
            },
            selected = setOfNotNull(uiState.filter),
            onToggle = { onAction(CountSessionAction.Filter(it.takeIf { it != uiState.filter })) },
            multiple = false,
        )
    }
    LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
        if (uiState.rows.isEmpty()) {
            item(key = "empty") {
                EmptyStateInline(
                    title = stringResource(R.string.empty_search_title, uiState.query.trim()),
                    modifier = Modifier.padding(spacing.s16),
                )
            }
        }
        items(uiState.rows, key = { it.lineId }) { row -> CountRow(row, uiState.blind, onAction) }
    }
    BottomBar {
        uiState.notice?.let { NoticeView(it, onAction) }
        PrimaryActionButton(
            text = stringResource(R.string.count_review_action),
            onClick = { onAction(CountSessionAction.Review) },
            variant = ActionVariant.PRIMARY,
            modifier = Modifier.fillMaxWidth(),
            state = if (uiState.working) ActionState.WORKING else ActionState.IDLE,
        )
    }
}

/**
 * Un producto por contar: el nombre y, sin conteo a ciegas, lo esperado; la cantidad por pasos en
 * unidades enteras, o escrita si la unidad admite decimales.
 */
@Composable
private fun CountRow(row: CountRowUi, blind: Boolean, onAction: (CountSessionAction) -> Unit) {
    val spacing = AppTheme.spacing
    val unit = labelOf(row)
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = AppTheme.sizes.listRow)
                .padding(horizontal = spacing.s16, vertical = spacing.s8),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.s12),
        ) {
            Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                Text(row.name, style = MaterialTheme.typography.bodyLarge)
                if (!blind) {
                    Text(
                        stringResource(R.string.count_expected, quantityText(row.expected, unit)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (!unit.allowsFraction && row.error == null) {
                QuantityStepper(
                    quantity = row.counted ?: QuantityMilli.ZERO,
                    unit = unit,
                    productName = row.name,
                    onDecrement = { onAction(CountSessionAction.Step(row.lineId, up = false)) },
                    onIncrement = { onAction(CountSessionAction.Step(row.lineId, up = true)) },
                    valueText = if (row.counted == null) NOT_COUNTED else null,
                )
            }
        }
        if (unit.allowsFraction || row.error != null) {
            QuantityField(
                value = row.text,
                onValueChange = { onAction(CountSessionAction.Count(row.lineId, it)) },
                label = stringResource(R.string.count_counted_label),
                unit = unit,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = spacing.s16, end = spacing.s16, bottom = spacing.s8),
                error = row.error?.let { rowErrorText(it) },
            )
        }
        HorizontalDivider(
            modifier = Modifier.padding(start = spacing.s16),
            thickness = AppTheme.sizes.hairline,
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

/**
 * La revisión: el resumen con lo que sobra y lo que falta, el aviso de lo que se movió, las
 * diferencias, lo contado sin diferencia y lo que quedó sin contar, y abajo la acción que aplica.
 */
@Composable
private fun ColumnScope.ReviewContent(
    uiState: CountSessionUiState,
    onAction: (CountSessionAction) -> Unit,
) {
    val spacing = AppTheme.spacing
    val differences = uiState.rows.filter { (it.difference ?: QuantityMilli.ZERO).milli != 0L }
    val pending = uiState.rows.filter { it.counted == null }
    val review = uiState.review
    LazyColumn(
        Modifier.weight(1f).fillMaxWidth(),
        contentPadding = PaddingValues(vertical = spacing.s8),
    ) {
        item(key = "summary") { Summary(differences.size, review, uiState.readOnly) }
        if (review != null && review.movedCount > 0 && !uiState.readOnly) {
            item(key = "moved") {
                NoticeCard(
                    text = pluralStringResource(
                        R.plurals.count_moved_notice,
                        review.movedCount,
                        review.movedCount,
                    ),
                    variant = NoticeVariant.WARNING,
                    modifier = Modifier.padding(horizontal = spacing.s16, vertical = spacing.s8),
                )
            }
        }
        items(differences, key = { "diff-${it.lineId}" }) { row -> DifferenceRow(row) }
        if (pending.isNotEmpty()) {
            item(key = "pending") { SectionHeader(stringResource(R.string.count_filter_pending)) }
            items(pending, key = { "pending-${it.lineId}" }) { row -> DifferenceRow(row) }
        }
    }
    if (!uiState.readOnly) {
        BottomBar {
            uiState.notice?.let { NoticeView(it, onAction) }
            val adjustments = differences.size
            PrimaryActionButton(
                text = if (adjustments == 0) {
                    stringResource(R.string.count_close_action)
                } else {
                    pluralStringResource(R.plurals.count_apply_action, adjustments, adjustments)
                },
                onClick = { onAction(CountSessionAction.AskApply) },
                variant = ActionVariant.PRIMARY,
                modifier = Modifier.fillMaxWidth(),
                state = if (uiState.working) ActionState.WORKING else ActionState.IDLE,
            )
        }
    }
}

@Composable
private fun Summary(differences: Int, review: CountReviewUi?, readOnly: Boolean) {
    val spacing = AppTheme.spacing
    AppCard(Modifier.padding(horizontal = spacing.s16, vertical = spacing.s8)) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.s4)) {
            Text(
                if (differences == 0) {
                    stringResource(R.string.count_no_differences)
                } else {
                    pluralStringResource(R.plurals.count_differences, differences, differences)
                },
                style = MaterialTheme.typography.titleMedium,
            )
            if (review != null && !readOnly && differences > 0) {
                AmountLine(R.string.count_surplus, review.surplus)
                AmountLine(R.string.count_shortage, review.shortage)
            }
        }
    }
}

/** `Sobrante` o `Faltante` con su valor, en modo discreto. */
@Composable
private fun AmountLine(text: Int, amount: Money?) {
    val money = LocalAppFormats.current.money
    val shown = amount?.let { rememberMoneyDisplay(it, money).text.text }
        ?: NO_VALUE_TEXT
    DiscreetValue(style = MaterialTheme.typography.bodyLarge) {
        Text(stringResource(text, shown), style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * Una fila de la revisión: el producto, `Esperado`, `Contado` y `Diferencia` con signo; marcada si
 * tuvo movimientos después de abrir el conteo. Un solo foco.
 */
@Composable
private fun DifferenceRow(row: CountRowUi) {
    val spacing = AppTheme.spacing
    val unit = labelOf(row)
    val counted = row.counted?.let { quantityText(it, unit) }
        ?: NO_VALUE_TEXT
    val difference = row.difference?.let { signed(it, unit) }
        ?: NO_VALUE_TEXT
    Column(
        Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = spacing.s16, vertical = spacing.s8),
        verticalArrangement = Arrangement.spacedBy(spacing.s4),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                row.name,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            if (row.moved) {
                StatusTag(stringResource(R.string.count_moved_tag), tone = StatusTone.WARNING)
            }
        }
        Figure(R.string.count_expected_label, quantityText(row.expected, unit))
        Figure(R.string.count_counted_label, counted)
        Figure(R.string.count_difference_label, difference)
    }
    HorizontalDivider(
        modifier = Modifier.padding(start = spacing.s16),
        thickness = AppTheme.sizes.hairline,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

@Composable
private fun Figure(label: Int, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            stringResource(label),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(value, style = AppTheme.amounts.row, maxLines = 1)
    }
}

/** Una diferencia con su signo: `+2 und` o `−2 und`. */
@Composable
private fun signed(quantity: QuantityMilli, unit: UnitLabel): String {
    val text = quantityText(quantity, unit)
    return if (quantity.milli > 0L) stringResource(R.string.count_positive, text) else text
}

@Composable
private fun BottomBar(content: @Composable () -> Unit) {
    Column(Modifier.keepsMessagesAbove().imePadding().navigationBarsPadding()) {
        HorizontalDivider(
            thickness = AppTheme.sizes.hairline,
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        Column(
            Modifier.padding(AppTheme.spacing.s16),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8),
        ) { content() }
    }
}

@Composable
private fun NoticeView(notice: CountNotice, onAction: (CountSessionAction) -> Unit) {
    when (notice) {
        is CountNotice.SaveFailed -> NoticeCard(
            text = stringResource(saveErrorText(notice.outOfSpace)),
            variant = NoticeVariant.ERROR,
        )

        is CountNotice.ApplyFailed -> NoticeCard(
            text = if (notice.outOfSpace) {
                stringResource(R.string.error_device_storage)
            } else {
                stringResource(R.string.count_error_apply)
            },
            variant = NoticeVariant.ERROR,
            actionLabel = stringResource(R.string.action_retry),
            onAction = { onAction(CountSessionAction.AskApply) },
        )

        CountNotice.UnknownZone -> NoticeCard(
            text = stringResource(R.string.stats_zone_unknown_title),
            variant = NoticeVariant.WARNING,
            actionLabel = stringResource(R.string.stats_zone_unknown_action),
            onAction = { onAction(CountSessionAction.ChooseZone) },
        )

        CountNotice.CurrencyUnconfirmed -> NoticeCard(
            text = stringResource(R.string.count_error_apply),
            variant = NoticeVariant.WARNING,
        )
    }
}

/** Confirma cuántos ajustes y su valor, en modo discreto. */
@Composable
private fun ApplyDialog(uiState: CountSessionUiState, onAction: (CountSessionAction) -> Unit) {
    val review = uiState.review
    val adjustments = review?.adjustments ?: 0
    val money = LocalAppFormats.current.money
    val net = review?.let { net(it) }
    val value = net?.let { rememberMoneyDisplay(it, money).text.text }
        ?: NO_VALUE_TEXT
    ConfirmDialog(
        title = pluralStringResource(R.plurals.count_apply_confirm_title, adjustments, adjustments),
        body = stringResource(R.string.count_apply_confirm_body, value),
        confirmLabel = stringResource(R.string.count_apply_confirm_action),
        onConfirm = { onAction(CountSessionAction.Confirm) },
        onDismiss = { onAction(CountSessionAction.DismissDialog) },
        sensitive = true,
    )
}

/** Lo que sobra menos lo que falta: el valor neto de los ajustes, o `null` si no cabe. */
private fun net(review: CountReviewUi): Money? {
    val surplus = review.surplus ?: return null
    val shortage = review.shortage ?: return null
    return listOf(surplus, -shortage).sumOrNull()
}

@Composable
private fun labelOf(row: CountRowUi): UnitLabel = row.unit?.let { unitLabel(it) }
    ?: UnitLabel(stringResource(R.string.seed_units_unit), allowsFraction = false)

@Composable
private fun rowErrorText(error: CountRowError): String = when (error) {
    CountRowError.NOT_A_NUMBER -> stringResource(R.string.field_error_number)
    CountRowError.NEGATIVE -> stringResource(R.string.field_error_number)
    CountRowError.DECIMALS -> stringResource(R.string.error_unit_decimals)
    CountRowError.TOO_HIGH -> stringResource(R.string.error_value_high)
}

private const val SKELETON_ROWS = 6

/** Lo que muestra el paso de una línea que todavía no se cuenta. */
private const val NOT_COUNTED = NO_VALUE_TEXT
