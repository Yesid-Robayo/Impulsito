package io.github.yesidrobayo.shopledger.ui.location

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.stock.LocationKind
import io.github.yesidrobayo.shopledger.domain.rule.stock.LocationRules
import io.github.yesidrobayo.shopledger.domain.usecase.stock.StockTransferDetailLine
import io.github.yesidrobayo.shopledger.ui.components.ActionState
import io.github.yesidrobayo.shopledger.ui.components.ActionVariant
import io.github.yesidrobayo.shopledger.ui.components.AppBottomSheet
import io.github.yesidrobayo.shopledger.ui.components.AppTextField
import io.github.yesidrobayo.shopledger.ui.components.CompactChipRow
import io.github.yesidrobayo.shopledger.ui.components.ConfirmDialog
import io.github.yesidrobayo.shopledger.ui.components.ConfirmVariant
import io.github.yesidrobayo.shopledger.ui.components.LedgerLineLayout
import io.github.yesidrobayo.shopledger.ui.components.NoticeCard
import io.github.yesidrobayo.shopledger.ui.components.NoticeVariant
import io.github.yesidrobayo.shopledger.ui.components.PrimaryActionButton
import io.github.yesidrobayo.shopledger.ui.components.SkeletonBlock
import io.github.yesidrobayo.shopledger.ui.components.StatusTag
import io.github.yesidrobayo.shopledger.ui.components.UnitLabel
import io.github.yesidrobayo.shopledger.ui.components.quantityText
import io.github.yesidrobayo.shopledger.ui.components.saveErrorText
import io.github.yesidrobayo.shopledger.ui.components.unitLabel
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.stock.locationName
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme

/**
 * La hoja de una ubicación: `Nombre`, hasta 40 caracteres, y `Tipo`. En la inicial, el nombre en
 * blanco la deja con el traducido, que se muestra debajo del campo.
 */
@Composable
internal fun LocationEditorSheet(editor: LocationEditor, onAction: (LocationsAction) -> Unit) {
    val title = if (editor.editingId == null) {
        stringResource(R.string.location_new_action)
    } else {
        stringResource(R.string.action_rename)
    }
    AppBottomSheet(onDismissRequest = { onAction(LocationsAction.DismissEditor) }, title = title) {
        Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12)) {
            AppTextField(
                value = editor.name,
                onValueChange = { onAction(LocationsAction.EditorName(it)) },
                label = stringResource(R.string.location_name_label),
                modifier = Modifier.fillMaxWidth(),
                error = editor.errors.firstOrNull()?.let { editorErrorText(it) },
                supportingText = stringResource(R.string.seed_locations_main)
                    .takeIf { editor.isInitial && editor.name.isBlank() },
                imeAction = ImeAction.Done,
            )
            Text(
                stringResource(R.string.location_kind_label),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CompactChipRow(
                options = LocationKind.entries,
                label = { stringResource(kindLabel(it)) },
                selected = setOf(editor.kind),
                onToggle = { onAction(LocationsAction.EditorKind(it)) },
                multiple = false,
            )
            editor.failure?.let {
                NoticeCard(
                    text = stringResource(saveErrorText(it.outOfSpace)),
                    variant = NoticeVariant.ERROR,
                    actionLabel = stringResource(R.string.action_retry),
                    onAction = { onAction(LocationsAction.SaveLocation) },
                )
            }
            PrimaryActionButton(
                text = stringResource(R.string.location_save_action),
                onClick = { onAction(LocationsAction.SaveLocation) },
                variant = ActionVariant.PRIMARY,
                modifier = Modifier.fillMaxWidth(),
                state = if (editor.saving) ActionState.WORKING else ActionState.IDLE,
            )
        }
    }
}

@Composable
private fun editorErrorText(error: LocationEditorError): String = when (error) {
    LocationEditorError.NAME_EMPTY -> stringResource(R.string.location_error_name_empty)

    LocationEditorError.NAME_TOO_LONG -> pluralStringResource(
        R.plurals.error_text_long,
        LocationRules.NAME_MAX,
        LocationRules.NAME_MAX,
    )

    LocationEditorError.NAME_REPEATED -> stringResource(R.string.location_error_name_repeated)
}

/**
 * La hoja de una transferencia: de dónde a dónde, el día, cada producto con lo que se movió y la
 * nota. Activa, ofrece `Anular transferencia`, y antes avisa lo que quedaría en negativo en el
 * destino, sin impedirlo.
 */
@Composable
internal fun TransferDetailSheet(
    sheet: TransferSheet,
    names: Map<Long, String>,
    onAction: (LocationsAction) -> Unit,
) {
    val spacing = AppTheme.spacing
    val ready = sheet as? TransferSheet.Ready
    val title = ready?.detail?.transfer?.content?.let {
        stringResource(
            R.string.location_transfer_row,
            names[it.fromLocationId].orEmpty(),
            names[it.toLocationId].orEmpty(),
        )
    }
    AppBottomSheet(
        onDismissRequest = { onAction(LocationsAction.DismissTransfer) },
        title = title,
    ) {
        if (ready == null) {
            repeat(SHEET_SKELETON_ROWS) {
                SkeletonBlock(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = spacing.s4)
                        .heightIn(min = AppTheme.sizes.listRowSingle),
                )
            }
            return@AppBottomSheet
        }
        val detail = ready.detail
        val content = detail.transfer.content
        Column(verticalArrangement = Arrangement.spacedBy(spacing.s8)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.s8),
            ) {
                Text(
                    LocalAppFormats.current.date.weekdayDayMonth(content.businessDate),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (detail.transfer.voiding != null) {
                    StatusTag(stringResource(R.string.status_voided))
                }
            }
            detail.lines.forEach { line ->
                LineRow(
                    name = transferLineName(line),
                    quantity = line.quantity,
                    unit = line.unit,
                )
            }
            content.note?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (detail.transfer.voiding == null) {
                val destination = names[content.toLocationId].orEmpty()
                detail.voidShortfalls.forEach { shortfall ->
                    NoticeCard(
                        text = stringResource(
                            R.string.location_warning_negative,
                            quantityText(shortfall.remaining, labelOf(shortfall.unit)),
                            destination,
                        ),
                        variant = NoticeVariant.WARNING,
                    )
                }
                if (ready.lotUsed) {
                    NoticeCard(
                        text = stringResource(R.string.error_void_lot_already_used),
                        variant = NoticeVariant.ERROR,
                    )
                }
                ready.failure?.let {
                    NoticeCard(
                        text = stringResource(saveErrorText(it.outOfSpace)),
                        variant = NoticeVariant.ERROR,
                    )
                }
                PrimaryActionButton(
                    text = stringResource(R.string.location_transfer_void_action),
                    onClick = { onAction(LocationsAction.AskVoidTransfer) },
                    variant = ActionVariant.TONAL,
                    modifier = Modifier.fillMaxWidth().padding(top = spacing.s8),
                    state = if (ready.voiding) ActionState.WORKING else ActionState.IDLE,
                )
            }
        }
    }
}

/**
 * Un producto de la transferencia y cuánto se movió, en su unidad. Un solo foco. Con letra grande,
 * la cantidad que no cabe al lado baja bajo el nombre: nunca se corta.
 */
@Composable
private fun LineRow(name: String, quantity: QuantityMilli, unit: MeasureUnit?) {
    val moved = quantityText(quantity, labelOf(unit))
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = AppTheme.sizes.listRowSingle)
                .semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LedgerLineLayout(
                gap = AppTheme.spacing.s12,
                modifier = Modifier.weight(1f),
                leading = { Text(name, style = MaterialTheme.typography.bodyLarge) },
                trailing = {
                    Text(moved, style = AppTheme.amounts.row, maxLines = 1, softWrap = false)
                },
            )
        }
        HorizontalDivider(
            thickness = AppTheme.sizes.hairline,
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

/**
 * El nombre de una línea: el producto y, si salió de un lote, también el lote, como
 * `Jabón de avena · Lote 22` o `Jabón de avena · Lote sin asignar`.
 */
@Composable
private fun transferLineName(line: StockTransferDetailLine): String {
    val name = line.product?.details?.name.orEmpty()
    val lot = line.lot ?: return name
    val code = lot.code
    val lotName = when {
        lot.isUnassigned -> stringResource(R.string.lot_unassigned_name)
        code != null -> stringResource(R.string.lot_name, code)
        else -> stringResource(R.string.lot_no_code)
    }
    return stringResource(R.string.location_transfer_line_lot, name, lotName)
}

@Composable
private fun labelOf(unit: MeasureUnit?): UnitLabel =
    unit?.let { unitLabel(it) } ?: UnitLabel(stringResource(R.string.seed_units_unit), false)

/** Las confirmaciones de la pantalla: archivar una ubicación o anular una transferencia. */
@Composable
internal fun LocationsDialogView(dialog: LocationsDialog, onAction: (LocationsAction) -> Unit) {
    when (dialog) {
        is LocationsDialog.Archive -> ConfirmDialog(
            title = stringResource(
                R.string.location_archive_confirm_title,
                locationName(dialog.location),
            ),
            body = stringResource(R.string.location_archive_confirm_body),
            confirmLabel = stringResource(R.string.location_archive_confirm_action),
            onConfirm = { onAction(LocationsAction.ConfirmDialog) },
            onDismiss = { onAction(LocationsAction.DismissDialog) },
        )

        is LocationsDialog.VoidTransfer -> ConfirmDialog(
            title = stringResource(R.string.location_transfer_void_confirm_title),
            body = stringResource(R.string.location_transfer_void_confirm_body),
            confirmLabel = stringResource(R.string.location_transfer_void_action),
            onConfirm = { onAction(LocationsAction.ConfirmDialog) },
            onDismiss = { onAction(LocationsAction.DismissDialog) },
            variant = ConfirmVariant.STANDARD,
        )
    }
}

private const val SHEET_SKELETON_ROWS = 3
