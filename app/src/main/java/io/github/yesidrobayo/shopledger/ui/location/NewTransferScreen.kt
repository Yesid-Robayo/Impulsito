package io.github.yesidrobayo.shopledger.ui.location

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.rule.stock.StockTransferRules
import io.github.yesidrobayo.shopledger.ui.components.ActionState
import io.github.yesidrobayo.shopledger.ui.components.ActionVariant
import io.github.yesidrobayo.shopledger.ui.components.AppBottomSheet
import io.github.yesidrobayo.shopledger.ui.components.AppCard
import io.github.yesidrobayo.shopledger.ui.components.AppTextField
import io.github.yesidrobayo.shopledger.ui.components.AppTopBar
import io.github.yesidrobayo.shopledger.ui.components.ConfirmDialog
import io.github.yesidrobayo.shopledger.ui.components.DelayedSkeleton
import io.github.yesidrobayo.shopledger.ui.components.InlineTextButton
import io.github.yesidrobayo.shopledger.ui.components.LocationSwitcherOptions
import io.github.yesidrobayo.shopledger.ui.components.LotPicker
import io.github.yesidrobayo.shopledger.ui.components.NoticeCard
import io.github.yesidrobayo.shopledger.ui.components.NoticeVariant
import io.github.yesidrobayo.shopledger.ui.components.PickerField
import io.github.yesidrobayo.shopledger.ui.components.PrimaryActionButton
import io.github.yesidrobayo.shopledger.ui.components.QuantityField
import io.github.yesidrobayo.shopledger.ui.components.SectionHeader
import io.github.yesidrobayo.shopledger.ui.components.SelectionSheet
import io.github.yesidrobayo.shopledger.ui.components.SettingsRow
import io.github.yesidrobayo.shopledger.ui.components.SettingsRowAction
import io.github.yesidrobayo.shopledger.ui.components.SkeletonBlock
import io.github.yesidrobayo.shopledger.ui.components.TopBarNavigation
import io.github.yesidrobayo.shopledger.ui.components.UnitLabel
import io.github.yesidrobayo.shopledger.ui.components.keepsMessagesAbove
import io.github.yesidrobayo.shopledger.ui.components.quantityText
import io.github.yesidrobayo.shopledger.ui.components.saveErrorText
import io.github.yesidrobayo.shopledger.ui.components.unitLabel
import io.github.yesidrobayo.shopledger.ui.stock.locationChoices
import io.github.yesidrobayo.shopledger.ui.stock.locationName
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme

/**
 * El formulario de una transferencia: `Desde`, `Hacia`, una tarjeta por producto con su cantidad,
 * `Agregar producto`, `Nota` y, abajo, `Registrar transferencia`.
 *
 * Una línea que dejaría el origen en negativo lo avisa con lo que quedaría, sin bloquear. Sin
 * líneas, tocar registrar dice qué falta. En una ventana ancha se centra a 600 dp.
 */
@Composable
fun NewTransferScreen(
    uiState: NewTransferUiState,
    onAction: (NewTransferAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(Modifier.fillMaxSize()) {
            AppTopBar(
                title = stringResource(R.string.location_new_transfer_action),
                navigation = TopBarNavigation.CLOSE,
                onNavigate = { onAction(NewTransferAction.Close) },
                navigationLabel = stringResource(R.string.action_close),
                titleStyle = MaterialTheme.typography.titleLarge,
            )
            when {
                uiState.loadFailed -> NoticeCard(
                    text = stringResource(R.string.state_error_read),
                    variant = NoticeVariant.ERROR,
                    modifier = Modifier.padding(AppTheme.spacing.s16),
                    actionLabel = stringResource(R.string.action_retry),
                    onAction = { onAction(NewTransferAction.Retry) },
                )

                uiState.loading -> LoadingForm()

                else -> TransferForm(uiState, onAction)
            }
        }
    }
    uiState.choosing?.let { EndSheet(it, uiState, onAction) }
    uiState.picker?.let { ProductPicker(it, onAction) }
    if (uiState.discardAsked) {
        ConfirmDialog(
            title = stringResource(R.string.dialog_discard_title),
            body = stringResource(R.string.dialog_discard_body),
            confirmLabel = stringResource(R.string.dialog_discard_action),
            onConfirm = { onAction(NewTransferAction.ConfirmLeave) },
            onDismiss = { onAction(NewTransferAction.DismissDialog) },
            dismissLabel = stringResource(R.string.dialog_discard_exit),
        )
    }
}

@Composable
private fun LoadingForm() {
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
private fun ColumnScope.TransferForm(
    uiState: NewTransferUiState,
    onAction: (NewTransferAction) -> Unit,
) {
    val spacing = AppTheme.spacing
    val names = uiState.locations.associate { it.id to locationName(it) }
    Column(
        Modifier
            .weight(1f)
            .fillMaxWidth()
            .align(Alignment.CenterHorizontally)
            .widthIn(max = FORM_WIDTH),
    ) {
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().testTag(TRANSFER_LIST_TAG),
            verticalArrangement = Arrangement.spacedBy(spacing.s12),
            contentPadding = PaddingValues(spacing.s16),
        ) {
            item(key = "from") {
                EndField(
                    label = stringResource(R.string.location_transfer_from),
                    value = uiState.fromId?.let { names[it] }.orEmpty(),
                    error = uiState.endError == TransferEnd.FROM,
                    onClick = { onAction(NewTransferAction.OpenEnd(TransferEnd.FROM)) },
                )
            }
            item(key = "to") {
                EndField(
                    label = stringResource(R.string.location_transfer_to),
                    value = uiState.toId?.let { names[it] }.orEmpty(),
                    error = uiState.endError == TransferEnd.TO,
                    onClick = { onAction(NewTransferAction.OpenEnd(TransferEnd.TO)) },
                )
            }
            item(key = "products") {
                SectionHeader(
                    pluralStringResource(
                        R.plurals.location_transfer_products,
                        uiState.lines.size,
                        uiState.lines.size,
                    ),
                )
            }
            items(uiState.lines, key = { it.key }) { line ->
                LineCard(line, names[uiState.fromId].orEmpty(), onAction)
            }
            if (uiState.canAdd) {
                item(key = "add") {
                    InlineTextButton(
                        text = stringResource(R.string.location_transfer_add_product_action),
                        onClick = { onAction(NewTransferAction.OpenPicker) },
                    )
                }
            }
            item(key = "note") {
                AppTextField(
                    value = uiState.note,
                    onValueChange = { onAction(NewTransferAction.Note(it)) },
                    label = stringResource(R.string.location_transfer_note_label),
                    modifier = Modifier.fillMaxWidth(),
                    error = if (uiState.noteTooLong) {
                        pluralStringResource(
                            R.plurals.error_text_long,
                            StockTransferRules.NOTE_MAX,
                            StockTransferRules.NOTE_MAX,
                        )
                    } else {
                        null
                    },
                    singleLine = false,
                    imeAction = ImeAction.Default,
                )
            }
        }
        BottomBar(uiState, onAction)
    }
}

/** Un extremo de la transferencia, que abre la lista de ubicaciones vigentes. */
@Composable
private fun EndField(label: String, value: String, error: Boolean, onClick: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s4)) {
        PickerField(label = label, value = value, onClick = onClick)
        if (error) {
            Text(
                stringResource(R.string.location_error_same),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = AppTheme.spacing.s16),
            )
        }
    }
}

/**
 * Un producto de la transferencia: su nombre, `Quitar`, la cantidad en su unidad, el lote si el
 * producto lleva lotes y, si dejaría el origen en negativo, lo que quedaría.
 */
@Composable
private fun LineCard(line: TransferLineUi, origin: String, onAction: (NewTransferAction) -> Unit) {
    val spacing = AppTheme.spacing
    val unit = line.unit?.let { unitLabel(it) }
        ?: UnitLabel(stringResource(R.string.seed_units_unit), allowsFraction = false)
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = line.name,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            InlineTextButton(
                text = stringResource(R.string.action_remove),
                onClick = { onAction(NewTransferAction.RemoveLine(line.key)) },
            )
        }
        QuantityField(
            value = line.quantityText,
            onValueChange = { onAction(NewTransferAction.QuantityChange(line.key, it)) },
            label = stringResource(R.string.sale_line_quantity),
            unit = unit,
            modifier = Modifier.fillMaxWidth().padding(top = spacing.s8),
            error = line.error?.let { lineErrorText(it) },
        )
        // Solo un producto con lotes trae opciones: sin lotes no se dibuja nada.
        LotPicker(
            options = line.lots,
            selectedId = line.lotId,
            unit = unit,
            onSelect = { onAction(NewTransferAction.ChooseLot(line.key, it)) },
            modifier = Modifier.fillMaxWidth().padding(top = spacing.s8),
        )
        line.leftAtOrigin?.let { left ->
            NoticeCard(
                text = stringResource(
                    R.string.location_warning_negative,
                    quantityText(left, unit),
                    origin,
                ),
                variant = NoticeVariant.WARNING,
                modifier = Modifier.padding(top = spacing.s8),
            )
        }
    }
}

@Composable
private fun BottomBar(uiState: NewTransferUiState, onAction: (NewTransferAction) -> Unit) {
    val spacing = AppTheme.spacing
    Column(Modifier.keepsMessagesAbove().imePadding().navigationBarsPadding()) {
        HorizontalDivider(
            thickness = AppTheme.sizes.hairline,
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        Column(
            Modifier.padding(spacing.s16),
            verticalArrangement = Arrangement.spacedBy(spacing.s8),
        ) {
            when (val notice = uiState.notice) {
                is TransferNotice.SaveFailed -> NoticeCard(
                    text = if (notice.outOfSpace) {
                        stringResource(saveErrorText(outOfSpace = true))
                    } else {
                        stringResource(R.string.location_error_register)
                    },
                    variant = NoticeVariant.ERROR,
                    actionLabel = stringResource(R.string.action_retry),
                    onAction = { onAction(NewTransferAction.Retry) },
                )

                TransferNotice.UnknownZone -> NoticeCard(
                    text = stringResource(R.string.stats_zone_unknown_title),
                    variant = NoticeVariant.WARNING,
                    actionLabel = stringResource(R.string.stats_zone_unknown_action),
                    onAction = { onAction(NewTransferAction.ChooseZone) },
                )

                null -> Unit
            }
            if (uiState.showNoLines) {
                Text(
                    stringResource(R.string.location_error_no_lines),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            PrimaryActionButton(
                text = stringResource(R.string.location_transfer_register_action),
                onClick = { onAction(NewTransferAction.Register) },
                variant = ActionVariant.PRIMARY,
                modifier = Modifier.fillMaxWidth(),
                state = if (uiState.saving) ActionState.WORKING else ActionState.IDLE,
            )
        }
    }
}

/** La lista de ubicaciones vigentes para elegir un extremo. */
@Composable
private fun EndSheet(
    end: TransferEnd,
    uiState: NewTransferUiState,
    onAction: (NewTransferAction) -> Unit,
) {
    val title = when (end) {
        TransferEnd.FROM -> stringResource(R.string.location_transfer_from)
        TransferEnd.TO -> stringResource(R.string.location_transfer_to)
    }
    AppBottomSheet(onDismissRequest = { onAction(NewTransferAction.CloseEnd) }, title = title) {
        LocationSwitcherOptions(
            locations = locationChoices(uiState.locations),
            selectedId = when (end) {
                TransferEnd.FROM -> uiState.fromId
                TransferEnd.TO -> uiState.toId
            },
            allowAll = false,
            onSelect = { id -> id?.let { onAction(NewTransferAction.ChooseEnd(it)) } },
            onManage = null,
        )
    }
}

/** La hoja para agregar un producto, con lo que hay de él en el origen. */
@Composable
private fun ProductPicker(picker: TransferPicker, onAction: (NewTransferAction) -> Unit) {
    SelectionSheet(
        title = stringResource(R.string.location_transfer_add_product_action),
        items = picker.options,
        key = { it.id },
        query = picker.query,
        onQueryChange = { onAction(NewTransferAction.QueryChange(it)) },
        onDismissRequest = { onAction(NewTransferAction.ClosePicker) },
        searchHint = stringResource(R.string.sale_search_hint),
        itemContent = { option ->
            val unit = option.unit?.let { unitLabel(it) }
                ?: UnitLabel(stringResource(R.string.seed_units_unit), allowsFraction = false)
            SettingsRow(
                title = option.name,
                summary = quantityText(option.onHand, unit),
                action = SettingsRowAction.Navigation {
                    onAction(NewTransferAction.AddProduct(option.id))
                },
            )
        },
    )
}

@Composable
private fun lineErrorText(error: TransferLineError): String = when (error) {
    TransferLineError.NOT_A_NUMBER -> stringResource(R.string.field_error_number)
    TransferLineError.ZERO -> stringResource(R.string.error_quantity_zero)
    TransferLineError.DECIMALS -> stringResource(R.string.error_unit_decimals)
    TransferLineError.TOO_HIGH -> stringResource(R.string.error_value_high)
    TransferLineError.UNAVAILABLE -> stringResource(R.string.combo_error_unavailable)
}

/** La lista del formulario, para que las pruebas la desplacen. */
internal const val TRANSFER_LIST_TAG = "transfer-list"

private const val SKELETON_ROWS = 4

/** Ancho máximo del formulario en una ventana ancha. */
private val FORM_WIDTH = 600.dp
