package io.github.yesidrobayo.shopledger.ui.variant

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.catalog.VariantValues
import io.github.yesidrobayo.shopledger.domain.rule.catalog.VariantRules
import io.github.yesidrobayo.shopledger.ui.components.ActionState
import io.github.yesidrobayo.shopledger.ui.components.ActionVariant
import io.github.yesidrobayo.shopledger.ui.components.AdaptiveFieldRow
import io.github.yesidrobayo.shopledger.ui.components.AppTextField
import io.github.yesidrobayo.shopledger.ui.components.AppTopBar
import io.github.yesidrobayo.shopledger.ui.components.CompactChipRow
import io.github.yesidrobayo.shopledger.ui.components.ConfirmDialog
import io.github.yesidrobayo.shopledger.ui.components.DelayedSkeleton
import io.github.yesidrobayo.shopledger.ui.components.EmptyState
import io.github.yesidrobayo.shopledger.ui.components.EmptyStateInline
import io.github.yesidrobayo.shopledger.ui.components.InlineTextButton
import io.github.yesidrobayo.shopledger.ui.components.MoneyField
import io.github.yesidrobayo.shopledger.ui.components.MoneySize
import io.github.yesidrobayo.shopledger.ui.components.MoneyText
import io.github.yesidrobayo.shopledger.ui.components.NoticeCard
import io.github.yesidrobayo.shopledger.ui.components.NoticeVariant
import io.github.yesidrobayo.shopledger.ui.components.PrimaryActionButton
import io.github.yesidrobayo.shopledger.ui.components.QuantityField
import io.github.yesidrobayo.shopledger.ui.components.SectionHeader
import io.github.yesidrobayo.shopledger.ui.components.SkeletonBlock
import io.github.yesidrobayo.shopledger.ui.components.TopBarNavigation
import io.github.yesidrobayo.shopledger.ui.components.UnitLabel
import io.github.yesidrobayo.shopledger.ui.components.VariantCellState
import io.github.yesidrobayo.shopledger.ui.components.VariantMatrix
import io.github.yesidrobayo.shopledger.ui.components.keepsMessagesAbove
import io.github.yesidrobayo.shopledger.ui.components.saveErrorText
import io.github.yesidrobayo.shopledger.ui.components.unitLabel
import io.github.yesidrobayo.shopledger.ui.help.HelpTopic
import io.github.yesidrobayo.shopledger.ui.help.ScreenHelpLink
import io.github.yesidrobayo.shopledger.ui.product.FieldProblem
import io.github.yesidrobayo.shopledger.ui.product.problemText
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme

/**
 * Las variantes de un producto: sus ejes con sus valores, la matriz de combinaciones, lo que se
 * aplica a todas y una fila por variante nueva, con la barra fija de crear al pie.
 *
 * Todo es un formulario local hasta tocar crear: nada se escribe antes, y un error nunca borra lo
 * escrito.
 */
@Composable
fun VariantsScreen(
    uiState: VariantsUiState,
    onAction: (VariantsAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = AppTheme.spacing
    Column(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .imePadding(),
    ) {
        AppTopBar(
            title = stringResource(R.string.variant_title),
            navigation = TopBarNavigation.CLOSE,
            onNavigate = { onAction(VariantsAction.Close) },
            navigationLabel = stringResource(R.string.a11y_close),
            titleStyle = MaterialTheme.typography.titleLarge,
            actions = { ScreenHelpLink(HelpTopic.VARIANTS_EXTRAS) },
        )
        when {
            uiState.loading -> DelayedSkeleton { MatrixSkeleton() }

            uiState.readFailed ->
                EmptyState(
                    icon = R.drawable.ic_error,
                    title = stringResource(R.string.state_error_read),
                ) {
                    InlineTextButton(
                        stringResource(R.string.action_retry),
                        { onAction(VariantsAction.Retry) },
                    )
                }

            else -> {
                LazyColumn(
                    Modifier
                        .weight(1f)
                        .align(Alignment.CenterHorizontally)
                        .widthIn(max = AppTheme.sizes.maxContentWidth)
                        .fillMaxWidth(),
                ) {
                    item(key = "parent") { ParentName(uiState.parentName) }
                    item(key = "first-axis") {
                        AxisSection(uiState, VariantAxis.FIRST, onAction)
                    }
                    item(key = "second-axis") { SecondAxis(uiState, onAction) }
                    if (uiState.firstValues.isNotEmpty()) {
                        item(key = "matrix") { Matrix(uiState, onAction) }
                    }
                    if (uiState.newCombos.isNotEmpty()) {
                        item(key = "apply-all") { ApplyAll(uiState, onAction) }
                    }
                    items(uiState.newCombos, key = { "new-" + it.label }) { combo ->
                        NewVariantRow(uiState, combo, onAction)
                    }
                    items(uiState.existing, key = { "existing-${it.id}" }) { variant ->
                        ExistingVariantRow(variant)
                    }
                }
                CreateBar(uiState, onAction, Modifier.align(Alignment.CenterHorizontally))
            }
        }
    }
    if (uiState.confirmingDiscard) {
        ConfirmDialog(
            title = stringResource(R.string.dialog_discard_title),
            body = stringResource(R.string.dialog_discard_body),
            confirmLabel = stringResource(R.string.dialog_discard_action),
            onConfirm = { onAction(VariantsAction.Discard) },
            onDismiss = { onAction(VariantsAction.KeepEditing) },
        )
    }
}

@Composable
private fun ParentName(name: String) {
    Text(
        text = name,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = AppTheme.spacing.s16),
    )
}

@Composable
private fun AxisSection(
    uiState: VariantsUiState,
    axis: VariantAxis,
    onAction: (VariantsAction) -> Unit,
) {
    val spacing = AppTheme.spacing
    val name = when (axis) {
        VariantAxis.FIRST -> uiState.firstAxis
        VariantAxis.SECOND -> uiState.secondAxis.orEmpty()
    }
    val values = when (axis) {
        VariantAxis.FIRST -> uiState.firstValues
        VariantAxis.SECOND -> uiState.secondValues
    }
    val newValue = when (axis) {
        VariantAxis.FIRST -> uiState.newFirstValue
        VariantAxis.SECOND -> uiState.newSecondValue
    }
    val usedValues = uiState.existing.mapNotNullTo(mutableSetOf()) {
        when (axis) {
            VariantAxis.FIRST -> it.combo.first
            VariantAxis.SECOND -> it.combo.second
        }
    }
    SectionHeader(
        title = stringResource(
            when (axis) {
                VariantAxis.FIRST -> R.string.variant_axis_1_label
                VariantAxis.SECOND -> R.string.variant_axis_2_label
            },
        ),
    )
    Column(
        Modifier.padding(horizontal = spacing.s16),
        verticalArrangement = Arrangement.spacedBy(spacing.s8),
    ) {
        if (uiState.axesLocked) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        } else {
            AppTextField(
                value = name,
                onValueChange = { onAction(VariantsAction.AxisChange(axis, it)) },
                label = stringResource(
                    when (axis) {
                        VariantAxis.FIRST -> R.string.variant_axis_1_hint
                        VariantAxis.SECOND -> R.string.variant_axis_2_hint
                    },
                ),
                error = uiState.axisProblems[axis]?.let { textProblem(it) },
            )
        }
        if (values.isEmpty() && axis == VariantAxis.FIRST) {
            EmptyStateInline(title = stringResource(R.string.variant_empty_title))
        }
        // Tocar un valor sin variantes creadas lo quita; uno ya usado queda fijo.
        CompactChipRow(
            options = values,
            label = { it },
            selected = values.toSet(),
            onToggle = { value ->
                if (value !in usedValues) onAction(VariantsAction.RemoveValue(axis, value))
            },
            multiple = true,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.s8),
        ) {
            AppTextField(
                value = newValue,
                onValueChange = { onAction(VariantsAction.NewValueChange(axis, it)) },
                label = stringResource(R.string.variant_value_label),
                error = uiState.valueProblems[axis]?.let { textProblem(it) },
                imeAction = ImeAction.Done,
                modifier = Modifier.weight(1f),
            )
            InlineTextButton(
                stringResource(R.string.variant_add_value_action),
                { onAction(VariantsAction.AddValue(axis)) },
            )
        }
    }
}

@Composable
private fun SecondAxis(uiState: VariantsUiState, onAction: (VariantsAction) -> Unit) {
    when {
        uiState.secondAxis != null -> AxisSection(uiState, VariantAxis.SECOND, onAction)

        // Con variantes creadas no se agrega otro eje: una variante no cambia de identidad.
        !uiState.axesLocked ->
            InlineTextButton(
                stringResource(R.string.variant_add_axis_action),
                { onAction(VariantsAction.ShowSecondAxis) },
                Modifier.padding(horizontal = AppTheme.spacing.s8),
            )

        else -> Unit
    }
}

@Composable
private fun Matrix(uiState: VariantsUiState, onAction: (VariantsAction) -> Unit) {
    val existing = uiState.existingKeys
    SectionHeader(title = stringResource(R.string.variant_combinations_title))
    Column(Modifier.padding(horizontal = AppTheme.spacing.s16)) {
        VariantMatrix(
            rows = uiState.firstValues,
            columns = if (uiState.secondAxis == null) emptyList() else uiState.secondValues,
            stateOf = { row, column ->
                val key = VariantCombo(row, column).key
                when (key) {
                    in existing -> VariantCellState.CREATED
                    in uiState.unchecked -> VariantCellState.UNCHECKED
                    else -> VariantCellState.CHECKED
                }
            },
            onToggle = { row, column ->
                onAction(VariantsAction.ToggleCombo(VariantCombo(row, column)))
            },
        )
    }
}

@Composable
private fun ApplyAll(uiState: VariantsUiState, onAction: (VariantsAction) -> Unit) {
    val spacing = AppTheme.spacing
    val all = uiState.applyAll
    SectionHeader(
        title = stringResource(R.string.variant_apply_all_title),
        actionLabel = stringResource(R.string.variant_apply_action),
        onAction = { onAction(VariantsAction.ApplyToAll) },
    )
    Column(
        Modifier.padding(horizontal = spacing.s16),
        verticalArrangement = Arrangement.spacedBy(spacing.s8),
    ) {
        AdaptiveFieldRow(
            firstLabel = stringResource(R.string.variant_price_label),
            secondLabel = stringResource(R.string.variant_cost_label),
            first = {
                MoneyField(
                    all.price,
                    { onAction(VariantsAction.ApplyAllChange(VariantField.PRICE, it)) },
                    stringResource(R.string.variant_price_label),
                    it,
                )
            },
            second = {
                MoneyField(
                    all.cost,
                    { onAction(VariantsAction.ApplyAllChange(VariantField.COST, it)) },
                    stringResource(R.string.variant_cost_label),
                    it,
                )
            },
        )
        if (uiState.tracksStock) {
            QuantityField(
                all.minStock,
                { onAction(VariantsAction.ApplyAllChange(VariantField.MIN_STOCK, it)) },
                stringResource(R.string.variant_min_stock_label),
                unitOf(uiState),
            )
        }
    }
}

@Composable
private fun NewVariantRow(
    uiState: VariantsUiState,
    combo: VariantCombo,
    onAction: (VariantsAction) -> Unit,
) {
    val spacing = AppTheme.spacing
    val input = uiState.inputs[combo.key] ?: VariantInput()
    val expanded = uiState.expanded == combo.key
    val name = VariantRules.nameOf(uiState.parentName, VariantValues(combo.first, combo.second))
    val price = uiState.prices[combo.key]
    // TalkBack dice si la fila está abierta: el cambio de los campos debajo no se ve sin mirar.
    val openState = stringResource(
        if (expanded) R.string.a11y_ranking_expanded else R.string.a11y_ranking_collapsed,
    )
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = AppTheme.sizes.touchTarget)
                .clickable(role = Role.Button) { onAction(VariantsAction.Expand(combo)) }
                .semantics { stateDescription = openState }
                .padding(horizontal = spacing.s16, vertical = spacing.s8),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.s12),
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (price == null) {
                Text(
                    text = stringResource(R.string.notice_no_price),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                MoneyText(price, MoneySize.ROW)
            }
        }
        uiState.comboProblems[combo.key]?.let { problem ->
            Text(
                text = stringResource(
                    when (problem) {
                        VariantComboProblem.REPEATED -> R.string.variant_error_combination_repeated
                        VariantComboProblem.NAME_TOO_LONG -> R.string.error_name_long
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = spacing.s16),
            )
        }
        if (expanded) VariantFields(uiState, combo, input, onAction)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun VariantFields(
    uiState: VariantsUiState,
    combo: VariantCombo,
    input: VariantInput,
    onAction: (VariantsAction) -> Unit,
) {
    val spacing = AppTheme.spacing
    val unit = unitOf(uiState)
    val change = { field: VariantField, text: String ->
        onAction(VariantsAction.InputChange(combo, field, text))
    }
    Column(
        Modifier.padding(start = spacing.s16, end = spacing.s16, bottom = spacing.s12),
        verticalArrangement = Arrangement.spacedBy(spacing.s8),
    ) {
        AdaptiveFieldRow(
            firstLabel = stringResource(R.string.variant_price_label),
            secondLabel = stringResource(R.string.variant_cost_label),
            first = {
                MoneyField(
                    input.price,
                    { change(VariantField.PRICE, it) },
                    stringResource(R.string.variant_price_label),
                    it,
                    error = fieldError(uiState, combo, VariantField.PRICE),
                )
            },
            second = {
                MoneyField(
                    input.cost,
                    { change(VariantField.COST, it) },
                    stringResource(R.string.variant_cost_label),
                    it,
                    error = fieldError(uiState, combo, VariantField.COST),
                )
            },
        )
        if (uiState.tracksStock) {
            AdaptiveFieldRow(
                firstLabel = stringResource(R.string.variant_initial_stock_label),
                secondLabel = stringResource(R.string.variant_min_stock_label),
                first = {
                    QuantityField(
                        input.initialStock,
                        { change(VariantField.INITIAL_STOCK, it) },
                        stringResource(R.string.variant_initial_stock_label),
                        unit,
                        it,
                        error = fieldError(uiState, combo, VariantField.INITIAL_STOCK),
                    )
                },
                second = {
                    QuantityField(
                        input.minStock,
                        { change(VariantField.MIN_STOCK, it) },
                        stringResource(R.string.variant_min_stock_label),
                        unit,
                        it,
                        error = fieldError(uiState, combo, VariantField.MIN_STOCK),
                    )
                },
            )
        }
        AppTextField(
            value = input.barcode,
            onValueChange = { change(VariantField.BARCODE, it) },
            label = stringResource(R.string.product_barcode),
            error = fieldError(uiState, combo, VariantField.BARCODE),
            imeAction = ImeAction.Done,
            autoCorrect = false,
        )
    }
}

@Composable
private fun ExistingVariantRow(variant: ExistingVariant) {
    val spacing = AppTheme.spacing
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = AppTheme.sizes.touchTarget)
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = spacing.s16, vertical = spacing.s8),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.s12),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = variant.name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(
                    if (variant.archived) {
                        R.string.status_archived
                    } else {
                        R.string.variant_created_status
                    },
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        MoneyText(variant.salePrice, MoneySize.ROW)
    }
}

@Composable
private fun CreateBar(
    uiState: VariantsUiState,
    onAction: (VariantsAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = AppTheme.spacing
    val count = uiState.newCombos.size
    Column(
        modifier
            .widthIn(max = AppTheme.sizes.maxContentWidth)
            .fillMaxWidth()
            .keepsMessagesAbove()
            .navigationBarsPadding()
            .padding(horizontal = spacing.s16, vertical = spacing.s12),
        verticalArrangement = Arrangement.spacedBy(spacing.s8),
    ) {
        when (val notice = uiState.notice) {
            null -> Unit

            is VariantsNotice.SaveFailed ->
                NoticeCard(
                    if (notice.outOfSpace) {
                        stringResource(saveErrorText(true))
                    } else {
                        stringResource(R.string.variant_error_save)
                    },
                    NoticeVariant.ERROR,
                )

            VariantsNotice.CurrencyUnconfirmed ->
                NoticeCard(
                    text = stringResource(R.string.region_required_body),
                    variant = NoticeVariant.WARNING,
                    actionLabel = stringResource(R.string.region_required_action),
                    onAction = { onAction(VariantsAction.ConfirmCurrency) },
                )

            VariantsNotice.ParentHasStock ->
                NoticeCard(
                    stringResource(R.string.variant_error_parent_stock),
                    NoticeVariant.WARNING,
                )

            VariantsNotice.ParentNotAllowed ->
                NoticeCard(
                    stringResource(R.string.variant_error_parent),
                    NoticeVariant.ERROR,
                )

            VariantsNotice.NothingSelected ->
                NoticeCard(
                    stringResource(R.string.variant_error_nothing_checked),
                    NoticeVariant.WARNING,
                )
        }
        PrimaryActionButton(
            text = if (uiState.axesLocked && count == 0) {
                stringResource(R.string.variant_save_action)
            } else {
                pluralStringResource(R.plurals.variant_create_action, count, count)
            },
            onClick = { onAction(VariantsAction.Save) },
            variant = ActionVariant.PRIMARY,
            modifier = Modifier.fillMaxWidth(),
            state = if (uiState.saving) ActionState.WORKING else ActionState.IDLE,
        )
    }
}

@Composable
private fun MatrixSkeleton() {
    val spacing = AppTheme.spacing
    Column(
        Modifier.padding(spacing.s16),
        verticalArrangement = Arrangement.spacedBy(spacing.s12),
    ) {
        repeat(SKELETON_ROWS) {
            SkeletonBlock(Modifier.fillMaxWidth().heightIn(min = AppTheme.sizes.touchTarget))
        }
    }
}

@Composable
private fun textProblem(problem: VariantTextProblem): String = when (problem) {
    VariantTextProblem.EMPTY -> stringResource(R.string.error_name_empty)

    VariantTextProblem.TOO_LONG ->
        pluralStringResource(
            R.plurals.error_text_long,
            VariantRules.TEXT_MAX,
            VariantRules.TEXT_MAX,
        )

    VariantTextProblem.REPEATED -> stringResource(R.string.variant_error_value_repeated)
}

@Composable
private fun fieldError(uiState: VariantsUiState, combo: VariantCombo, field: VariantField) =
    uiState.problemOf(combo, field)?.let {
        if (it is FieldProblem.Duplicate && field == VariantField.BARCODE) {
            stringResource(R.string.variant_error_code_repeated, it.existingName)
        } else {
            problemText(it, VariantsUiState.productFieldOf(field))
        }
    }

@Composable
private fun unitOf(uiState: VariantsUiState): UnitLabel = uiState.unit?.let { unitLabel(it) }
    ?: UnitLabel(stringResource(R.string.seed_units_unit), false)

private const val SKELETON_ROWS = 4
