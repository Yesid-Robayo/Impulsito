package io.github.yesidrobayo.shopledger.ui.stock

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.stock.StockAdjustmentReason
import io.github.yesidrobayo.shopledger.domain.usecase.stock.StockAdjustmentSubject
import io.github.yesidrobayo.shopledger.format.NO_VALUE_TEXT
import io.github.yesidrobayo.shopledger.ui.components.ActionState
import io.github.yesidrobayo.shopledger.ui.components.ActionVariant
import io.github.yesidrobayo.shopledger.ui.components.AppTextField
import io.github.yesidrobayo.shopledger.ui.components.AppTopBar
import io.github.yesidrobayo.shopledger.ui.components.CompactChipRow
import io.github.yesidrobayo.shopledger.ui.components.ConfirmDialog
import io.github.yesidrobayo.shopledger.ui.components.DISCREET_TEXT
import io.github.yesidrobayo.shopledger.ui.components.DelayedSkeleton
import io.github.yesidrobayo.shopledger.ui.components.EmptyState
import io.github.yesidrobayo.shopledger.ui.components.MoneyField
import io.github.yesidrobayo.shopledger.ui.components.NoticeCard
import io.github.yesidrobayo.shopledger.ui.components.NoticeVariant
import io.github.yesidrobayo.shopledger.ui.components.PrimaryActionButton
import io.github.yesidrobayo.shopledger.ui.components.QuantityField
import io.github.yesidrobayo.shopledger.ui.components.SegmentedTabs
import io.github.yesidrobayo.shopledger.ui.components.SkeletonBlock
import io.github.yesidrobayo.shopledger.ui.components.TopBarNavigation
import io.github.yesidrobayo.shopledger.ui.components.UnitLabel
import io.github.yesidrobayo.shopledger.ui.components.formatAnnotated
import io.github.yesidrobayo.shopledger.ui.components.keepsMessagesAbove
import io.github.yesidrobayo.shopledger.ui.components.quantityText
import io.github.yesidrobayo.shopledger.ui.components.unitLabel
import io.github.yesidrobayo.shopledger.ui.currency.rememberMoneyDisplay
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.format.LocalDiscreetMode
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme

/**
 * El ajuste de stock de un producto. Es puro: todo llega en [uiState] y sale por [onAction].
 *
 * Arriba, el producto y su stock; después el modo, los campos del modo, la nota y el resultado en
 * vivo. Abajo, fijo, `Guardar ajuste`. Un ajuste que deja el stock negativo se avisa sin bloquear:
 * es decisión de la persona.
 */
@Composable
fun StockAdjustmentScreen(
    uiState: StockAdjustmentUiState,
    onAction: (StockAdjustmentAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        AppTopBar(
            title = stringResource(R.string.stock_adjust_title),
            navigation = TopBarNavigation.CLOSE,
            onNavigate = { onAction(StockAdjustmentAction.Close) },
            navigationLabel = stringResource(R.string.a11y_close),
            titleStyle = MaterialTheme.typography.titleLarge,
        )
        when (val content = uiState.content) {
            StockAdjustmentContent.Loading ->
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    DelayedSkeleton { FormSkeleton() }
                }

            StockAdjustmentContent.Unavailable ->
                EmptyState(
                    icon = R.drawable.ic_error,
                    title = stringResource(R.string.state_error_read),
                    modifier = Modifier.weight(1f),
                )

            is StockAdjustmentContent.Ready -> {
                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    AdjustmentForm(
                        uiState,
                        content.subject,
                        onAction,
                        Modifier.widthIn(max = AppTheme.sizes.maxContentWidth),
                    )
                }
                SaveBar(uiState, onAction)
            }
        }
    }
    if (uiState.confirmingDiscard) {
        ConfirmDialog(
            title = stringResource(R.string.dialog_discard_title),
            body = stringResource(R.string.dialog_discard_body),
            confirmLabel = stringResource(R.string.dialog_discard_action),
            onConfirm = { onAction(StockAdjustmentAction.Discard) },
            onDismiss = { onAction(StockAdjustmentAction.KeepEditing) },
            dismissLabel = stringResource(R.string.dialog_discard_exit),
        )
    }
}

@Composable
private fun AdjustmentForm(
    uiState: StockAdjustmentUiState,
    subject: StockAdjustmentSubject,
    onAction: (StockAdjustmentAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = AppTheme.spacing
    val unit = unitLabel(subject.unit)
    Column(
        modifier.fillMaxWidth().padding(spacing.s16),
        verticalArrangement = Arrangement.spacedBy(spacing.s16),
    ) {
        ProductBlock(subject, unit)
        val modes = buildList {
            add(AdjustmentMode.COUNT)
            add(AdjustmentMode.CHANGE)
            if (subject.acceptsOpeningBalance) add(AdjustmentMode.OPENING)
        }
        val labels = modes.map { modeLabel(it) }
        SegmentedTabs(
            options = labels,
            selectedIndex = modes.indexOf(uiState.mode).coerceAtLeast(0),
            onSelect = { onAction(StockAdjustmentAction.ModeChanged(modes[it])) },
        )
        ModeFields(uiState, unit, onAction)
        if (uiState.mode != AdjustmentMode.OPENING) {
            AppTextField(
                value = uiState.noteText,
                onValueChange = { onAction(StockAdjustmentAction.NoteChanged(it)) },
                label = stringResource(R.string.form_note),
                error = uiState.errors[AdjustmentField.NOTE]?.let { problemText(it) },
                imeAction = ImeAction.Done,
            )
        }
        uiState.preview?.let { ResultLine(it, unit) }
        Notices(uiState, subject, unit, onAction)
    }
}

@Composable
private fun ProductBlock(subject: StockAdjustmentSubject, unit: UnitLabel) {
    Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s4)) {
        Text(
            text = subject.name,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                text = stringResource(R.string.term_stock),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = quantityText(subject.cost.onHand, unit),
                style = AppTheme.amounts.title,
                color =
                    if (subject.cost.onHand < QuantityMilli.ZERO) {
                        AppTheme.colors.loss
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
            )
        }
        Text(
            text = stringResource(R.string.stock_minimum, quantityText(subject.minStock, unit)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ModeFields(
    uiState: StockAdjustmentUiState,
    unit: UnitLabel,
    onAction: (StockAdjustmentAction) -> Unit,
) {
    val quantityError = uiState.errors[AdjustmentField.QUANTITY]?.let { problemText(it) }
    when (uiState.mode) {
        AdjustmentMode.COUNT ->
            QuantityField(
                value = uiState.quantityText,
                onValueChange = { onAction(StockAdjustmentAction.QuantityChanged(it)) },
                label = stringResource(R.string.stock_tab_counted),
                unit = unit,
                error = quantityError,
            )

        AdjustmentMode.CHANGE -> {
            CompactChipRow(
                options = CHANGE_REASONS,
                label = { reasonLabel(it) },
                selected = setOf(uiState.reason),
                onToggle = { onAction(StockAdjustmentAction.ReasonChanged(it)) },
                multiple = false,
            )
            if (uiState.showsDirection) {
                CompactChipRow(
                    options = listOf(false, true),
                    label = { increase ->
                        stringResource(if (increase) R.string.stock_in else R.string.stock_tab_out)
                    },
                    selected = setOf(uiState.increase),
                    onToggle = { onAction(StockAdjustmentAction.DirectionChanged(it)) },
                    multiple = false,
                )
            }
            QuantityField(
                value = uiState.quantityText,
                onValueChange = { onAction(StockAdjustmentAction.QuantityChanged(it)) },
                label = stringResource(R.string.sale_line_quantity),
                unit = unit,
                error = quantityError,
            )
        }

        AdjustmentMode.OPENING -> {
            QuantityField(
                value = uiState.quantityText,
                onValueChange = { onAction(StockAdjustmentAction.QuantityChanged(it)) },
                label = stringResource(R.string.sale_line_quantity),
                unit = unit,
                error = quantityError,
            )
            MoneyField(
                value = uiState.unitCostText,
                onValueChange = { onAction(StockAdjustmentAction.UnitCostChanged(it)) },
                label = stringResource(R.string.form_unit_cost),
                error = uiState.errors[AdjustmentField.UNIT_COST]?.let { problemText(it) },
                imeAction = ImeAction.Done,
            )
        }
    }
}

/**
 * `Quedan 10 und · Diferencia −2 und · Valor ¤ 2.710`. El valor es costo: en modo discreto se
 * oculta, y TalkBack lo lee con su nombre de moneda.
 */
@Composable
private fun ResultLine(preview: AdjustmentPreview, unit: UnitLabel) {
    val formatter = LocalAppFormats.current.money
    val discreet = LocalDiscreetMode.current
    val left = quantityText(preview.left, unit)
    val difference = signed(preview.difference, unit)
    val display = preview.value?.let { rememberMoneyDisplay(it, formatter) }
    val amount: CharSequence =
        when {
            discreet -> DISCREET_TEXT
            display != null -> display.text
            else -> NO_VALUE_TEXT
        }
    val spoken =
        when {
            discreet -> stringResource(R.string.a11y_discreet_hidden)
            display != null -> display.description
            else -> stringResource(R.string.a11y_no_value)
        }
    val description = stringResource(R.string.stock_result, left, difference, spoken)
    Text(
        text = formatAnnotated(stringResource(R.string.stock_result), left, difference, amount),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.semantics { contentDescription = description },
    )
}

@Composable
private fun Notices(
    uiState: StockAdjustmentUiState,
    subject: StockAdjustmentSubject,
    unit: UnitLabel,
    onAction: (StockAdjustmentAction) -> Unit,
) {
    val preview = uiState.preview
    if (preview != null && preview.left < QuantityMilli.ZERO) {
        NoticeCard(
            text = stringResource(R.string.notice_oversell, quantityText(preview.left, unit)),
            variant = NoticeVariant.WARNING,
        )
    }
    val noCost = subject.cost.averageCost == Money.ZERO && subject.cost.stockValue == Money.ZERO
    if (noCost && uiState.mode != AdjustmentMode.OPENING) {
        val zero = rememberMoneyDisplay(Money.ZERO, LocalAppFormats.current.money).text
        val pattern = stringResource(R.string.notice_adjustment_no_cost)
        // El aviso lleva a corregir el costo, que es lo que deja de valorar el ajuste en cero.
        NoticeCard(
            text = formatAnnotated(pattern, zero).toString(),
            variant = NoticeVariant.INFO,
            actionLabel = stringResource(R.string.product_fix_cost),
            onAction = { onAction(StockAdjustmentAction.FixCost) },
        )
    }
    NoticeCard(
        text = stringResource(R.string.notice_adjustment_history),
        variant = NoticeVariant.INFO,
        icon = R.drawable.ic_history,
    )
}

@Composable
private fun SaveBar(uiState: StockAdjustmentUiState, onAction: (StockAdjustmentAction) -> Unit) {
    val spacing = AppTheme.spacing
    Column(
        Modifier
            .fillMaxWidth()
            .keepsMessagesAbove()
            .navigationBarsPadding()
            .imePadding()
            .padding(spacing.s16),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(spacing.s12),
    ) {
        if (uiState.saveFailed) {
            NoticeCard(
                text = stringResource(R.string.state_error_save),
                variant = NoticeVariant.ERROR,
                modifier = Modifier.widthIn(max = AppTheme.sizes.maxContentWidth),
            )
        }
        PrimaryActionButton(
            text = stringResource(R.string.stock_save_adjustment),
            onClick = { onAction(StockAdjustmentAction.Save) },
            variant = ActionVariant.PRIMARY,
            modifier = Modifier.widthIn(max = AppTheme.sizes.maxContentWidth),
            state = if (uiState.saving) ActionState.WORKING else ActionState.IDLE,
        )
    }
}

@Composable
private fun FormSkeleton() {
    val spacing = AppTheme.spacing
    Column(
        Modifier.widthIn(max = AppTheme.sizes.maxContentWidth).fillMaxWidth().padding(spacing.s16),
        verticalArrangement = Arrangement.spacedBy(spacing.s16),
    ) {
        repeat(SKELETON_BLOCKS) {
            SkeletonBlock(Modifier.fillMaxWidth().padding(vertical = spacing.s24))
        }
    }
}

/** La diferencia con su signo: `−2 und` o `+1 und`. Cero va sin signo. */
@Composable
private fun signed(quantity: QuantityMilli, unit: UnitLabel): String {
    val text = quantityText(quantity, unit)
    return if (quantity > QuantityMilli.ZERO) PLUS_SIGN + text else text
}

@Composable
private fun modeLabel(mode: AdjustmentMode): String = stringResource(
    when (mode) {
        AdjustmentMode.COUNT -> R.string.stock_tab_counted
        AdjustmentMode.CHANGE -> R.string.stock_tab_out
        AdjustmentMode.OPENING -> R.string.term_opening_stock
    },
)

/** El nombre de un motivo, el mismo que muestra el historial del producto. */
@Composable
internal fun reasonLabel(reason: StockAdjustmentReason): String = stringResource(
    when (reason) {
        StockAdjustmentReason.COUNT_CORRECTION -> R.string.adjustment_reason_count_correction
        StockAdjustmentReason.DAMAGED -> R.string.adjustment_reason_damaged
        StockAdjustmentReason.EXPIRED -> R.string.adjustment_reason_expired
        StockAdjustmentReason.LOST_OR_STOLEN -> R.string.adjustment_reason_lost_or_stolen
        StockAdjustmentReason.OWN_USE -> R.string.adjustment_reason_own_use
        StockAdjustmentReason.GIFT_OR_SAMPLE -> R.string.adjustment_reason_gift_or_sample
        StockAdjustmentReason.OTHER -> R.string.adjustment_reason_other
    },
)

@Composable
private fun problemText(problem: AdjustmentProblem): String = when (problem) {
    AdjustmentProblem.NotANumber -> stringResource(R.string.field_error_number)

    AdjustmentProblem.TooHigh -> stringResource(R.string.error_value_high)

    AdjustmentProblem.Zero -> stringResource(R.string.error_quantity_zero)

    AdjustmentProblem.WholeUnitsOnly -> stringResource(R.string.error_unit_decimals)

    is AdjustmentProblem.TooManyDecimals ->
        pluralStringResource(R.plurals.error_money_decimals, problem.allowed, problem.allowed)

    AdjustmentProblem.NoteRequired -> stringResource(R.string.error_other_reason_note)

    is AdjustmentProblem.TooLong ->
        pluralStringResource(R.plurals.error_text_long, problem.maxLength, problem.maxLength)
}

/** Los motivos de una salida: el conteo tiene su propio modo. */
private val CHANGE_REASONS =
    StockAdjustmentReason.entries.filter { it != StockAdjustmentReason.COUNT_CORRECTION }

private const val PLUS_SIGN = "+"
private const val SKELETON_BLOCKS = 4
