package io.github.yesidrobayo.shopledger.ui.pricing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.Margin
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceListKind
import io.github.yesidrobayo.shopledger.domain.rule.pricing.PriceListFormRules
import io.github.yesidrobayo.shopledger.format.NO_VALUE_TEXT
import io.github.yesidrobayo.shopledger.ui.components.ActionState
import io.github.yesidrobayo.shopledger.ui.components.ActionVariant
import io.github.yesidrobayo.shopledger.ui.components.AppBottomSheet
import io.github.yesidrobayo.shopledger.ui.components.AppTextField
import io.github.yesidrobayo.shopledger.ui.components.CompactChipRow
import io.github.yesidrobayo.shopledger.ui.components.DiscreetValue
import io.github.yesidrobayo.shopledger.ui.components.FieldError
import io.github.yesidrobayo.shopledger.ui.components.InlineTextButton
import io.github.yesidrobayo.shopledger.ui.components.MoneyField
import io.github.yesidrobayo.shopledger.ui.components.NoticeCard
import io.github.yesidrobayo.shopledger.ui.components.NoticeVariant
import io.github.yesidrobayo.shopledger.ui.components.PercentField
import io.github.yesidrobayo.shopledger.ui.components.PrimaryActionButton
import io.github.yesidrobayo.shopledger.ui.components.saveErrorText
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme

/** El aviso de un guardado fallido y el botón principal, al pie de una hoja. */
@Composable
private fun SheetAction(
    text: String,
    saving: Boolean,
    failure: ListSaveFailure?,
    onSave: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12)) {
        failure?.let {
            NoticeCard(
                text = stringResource(saveErrorText(it.outOfSpace)),
                variant = NoticeVariant.ERROR,
                icon = R.drawable.ic_error,
            )
        }
        PrimaryActionButton(
            text = text,
            onClick = onSave,
            variant = ActionVariant.PRIMARY,
            modifier = Modifier.fillMaxWidth(),
            state = if (saving) ActionState.WORKING else ActionState.IDLE,
        )
    }
}

/**
 * La hoja de una lista: `Nombre`, `Tipo` y, si es relativa, `Lista base` y `Ajuste` con signo. Los
 * errores van en su campo, y un guardado fallido se avisa arriba del botón sin borrar nada.
 */
@Composable
fun PriceListEditorSheet(editor: PriceListEditor, onAction: (PriceListEditorAction) -> Unit) {
    val title =
        if (editor.editingId ==
            null
        ) {
            R.string.price_list_new_action
        } else {
            R.string.price_list_edit_title
        }
    AppBottomSheet(
        onDismissRequest = { onAction(PriceListEditorAction.Dismiss) },
        title = stringResource(title),
        action = {
            SheetAction(
                stringResource(R.string.price_list_save_action),
                editor.saving,
                editor.saveFailed,
            ) {
                onAction(PriceListEditorAction.Save)
            }
        },
    ) {
        AppTextField(
            value = editor.name,
            onValueChange = { onAction(PriceListEditorAction.Name(it)) },
            label = stringResource(R.string.price_list_name_label),
            error =
                when (editor.nameProblem) {
                    null -> null

                    ListNameProblem.EMPTY -> stringResource(R.string.price_list_error_name_empty)

                    ListNameProblem.DUPLICATE -> stringResource(
                        R.string.price_list_error_name_repeated,
                    )

                    ListNameProblem.TOO_LONG ->
                        pluralStringResource(
                            R.plurals.error_text_long,
                            PriceListEditorModel.NAME_MAX,
                            PriceListEditorModel.NAME_MAX,
                        )
                },
        )
        SheetLabel(stringResource(R.string.price_list_kind_label))
        CompactChipRow(
            options = PriceListKind.entries,
            label = { kind ->
                stringResource(
                    when (kind) {
                        PriceListKind.FIXED -> R.string.price_list_kind_fixed
                        PriceListKind.RELATIVE -> R.string.price_list_kind_relative
                    },
                )
            },
            selected = setOf(editor.kind),
            onToggle = { onAction(PriceListEditorAction.Kind(it)) },
            multiple = false,
        )
        if (editor.hasOwnPrices) {
            FieldError(
                stringResource(R.string.price_list_error_has_own_prices),
            )
        }
        if (editor.kind == PriceListKind.RELATIVE) {
            SheetLabel(stringResource(R.string.price_list_base_label))
            CompactChipRow(
                options = editor.bases,
                label = { it.name },
                selected = editor.bases.filter { it.list.id == editor.baseId }.toSet(),
                onToggle = { onAction(PriceListEditorAction.Base(it.list.id)) },
                multiple = false,
            )
            editor.baseProblem?.let {
                FieldError(
                    stringResource(
                        when (it) {
                            ListBaseProblem.MISSING -> R.string.price_list_error_base_missing

                            ListBaseProblem.UNAVAILABLE ->
                                R.string.price_list_error_base_unavailable

                            ListBaseProblem.CYCLE -> R.string.price_list_error_cycle
                        },
                    ),
                )
            }
            val percent = LocalAppFormats.current.percent
            PercentField(
                value = editor.adjustmentText,
                onValueChange = { onAction(PriceListEditorAction.Adjustment(it)) },
                label = stringResource(R.string.price_list_adjustment_label),
                allowNegative = true,
                imeAction = ImeAction.Done,
                error =
                    when (editor.adjustmentProblem) {
                        null -> null

                        ListAdjustmentProblem.MISSING ->
                            stringResource(R.string.price_list_error_adjustment_missing)

                        ListAdjustmentProblem.OUT_OF_RANGE ->
                            stringResource(
                                R.string.price_list_error_adjustment_range,
                                percent.formatChange(PriceListFormRules.MIN_ADJUSTMENT),
                                percent.formatChange(PriceListFormRules.MAX_ADJUSTMENT),
                            )
                    },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun SheetLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * La hoja del precio de un producto en una lista: `Precio en esta lista`, el margen que deja mientras
 * se escribe y, si tiene precio propio, `Quitar precio propio`. El margen es sensible: en modo
 * discreto se oculta; el precio no, porque es lo que se cobra.
 */
@Composable
fun ListPriceEditorSheet(editor: ListPriceEditor, onAction: (ListPriceEditorAction) -> Unit) {
    AppBottomSheet(
        onDismissRequest = { onAction(ListPriceEditorAction.Dismiss) },
        title = editor.productName,
        action = {
            SheetAction(stringResource(R.string.action_save), editor.saving, editor.saveFailed) {
                onAction(ListPriceEditorAction.Save)
            }
        },
    ) {
        val formats = LocalAppFormats.current
        MoneyField(
            value = editor.priceText,
            onValueChange = { onAction(ListPriceEditorAction.Price(it)) },
            label = stringResource(R.string.price_list_price_label),
            imeAction = ImeAction.Done,
            error =
                when (editor.problem) {
                    null -> null

                    ListPriceProblem.INVALID, ListPriceProblem.ZERO ->
                        stringResource(
                            R.string.price_list_error_zero,
                            formats.money.format(Money.ZERO),
                        )
                },
        )
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                stringResource(R.string.price_list_resulting_margin_label),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val margin = editor.typedPrice?.let(editor.pricing::marginOf) ?: Margin.NoData
            DiscreetValue {
                Text(
                    when (margin) {
                        is Margin.Defined -> formats.percent.format(margin.value)
                        Margin.NoData, Margin.OutOfRange -> NO_VALUE_TEXT
                    },
                    style = AppTheme.amounts.row,
                )
            }
        }
        if (editor.hasOwnPrice) {
            InlineTextButton(
                text = stringResource(R.string.price_list_remove_own_action),
                onClick = { onAction(ListPriceEditorAction.RemoveOwn) },
            )
        }
    }
}
