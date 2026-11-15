package io.github.yesidrobayo.shopledger.ui.lot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType
import io.github.yesidrobayo.shopledger.domain.rule.stock.LotRules
import io.github.yesidrobayo.shopledger.ui.components.ActionState
import io.github.yesidrobayo.shopledger.ui.components.ActionVariant
import io.github.yesidrobayo.shopledger.ui.components.AppBottomSheet
import io.github.yesidrobayo.shopledger.ui.components.AppTextField
import io.github.yesidrobayo.shopledger.ui.components.DateField
import io.github.yesidrobayo.shopledger.ui.components.InlineTextButton
import io.github.yesidrobayo.shopledger.ui.components.LedgerLineLayout
import io.github.yesidrobayo.shopledger.ui.components.LotRow
import io.github.yesidrobayo.shopledger.ui.components.NoticeCard
import io.github.yesidrobayo.shopledger.ui.components.NoticeVariant
import io.github.yesidrobayo.shopledger.ui.components.PrimaryActionButton
import io.github.yesidrobayo.shopledger.ui.components.SectionHeader
import io.github.yesidrobayo.shopledger.ui.components.WholeNumberField
import io.github.yesidrobayo.shopledger.ui.components.quantityText
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.product.movementLabel
import io.github.yesidrobayo.shopledger.ui.product.signedQuantity
import io.github.yesidrobayo.shopledger.ui.stock.locationName
import io.github.yesidrobayo.shopledger.ui.stock.reasonLabel
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme

/**
 * El detalle de un lote: el producto, el lote con su vencimiento y lo que tiene, la compra de
 * origen, lo que hay en cada ubicación y sus últimos movimientos.
 *
 * Un lote con código ofrece `Dar salida`, si tiene stock, y corregir su vencimiento o su código.
 * El lote sin asignar no tiene código ni fecha: dice por qué existe y ofrece registrar la compra o
 * ajustar el stock, que es como se corrige.
 */
@Composable
internal fun LotDetailSheet(
    lot: LotItem,
    sheet: LotSheet.Detail,
    showsLocations: Boolean,
    onAction: (LotsAction) -> Unit,
) {
    val spacing = AppTheme.spacing
    val unit = lotUnit(lot)
    val takesOut = !lot.unassigned && lot.onHand > QuantityMilli.ZERO
    AppBottomSheet(
        onDismissRequest = { onAction(LotsAction.CloseSheet) },
        title = lot.productName,
        action = if (takesOut) {
            {
                PrimaryActionButton(
                    text = stringResource(R.string.lot_take_out_action),
                    onClick = { onAction(LotsAction.TakeOut(lot.productId, lot.lotId)) },
                    variant = ActionVariant.TONAL,
                )
            }
        } else {
            null
        },
    ) {
        LotRow(
            code = lot.code,
            expiry = lot.expiry,
            onHand = lot.onHand,
            unit = unit,
            divider = false,
            name = if (lot.unassigned) stringResource(R.string.lot_unassigned_name) else null,
            note = if (lot.unassigned) stringResource(R.string.lot_unassigned_help) else null,
        )
        if (lot.unassigned) {
            // Con letra grande, el segundo botón baja de línea en vez de partirse.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.s16)) {
                InlineTextButton(stringResource(R.string.empty_movements_action), {
                    onAction(LotsAction.RegisterPurchase(lot.productId))
                })
                InlineTextButton(stringResource(R.string.stock_adjust_title), {
                    onAction(LotsAction.AdjustStock(lot.productId))
                })
            }
        } else {
            // Con letra grande, el segundo botón baja de línea en vez de partirse.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.s16)) {
                InlineTextButton(stringResource(R.string.lot_fix_expiry_action), {
                    onAction(LotsAction.StartFixExpiry(lot.lotId))
                })
                InlineTextButton(stringResource(R.string.lot_fix_code_action), {
                    onAction(LotsAction.StartFixCode(lot.lotId))
                })
            }
        }
        sheet.originDate?.let { date ->
            LabeledLine(
                stringResource(R.string.lot_origin_label),
                LocalAppFormats.current.date.dayMonthYear(date),
            )
        }
        if (showsLocations && lot.places.isNotEmpty()) {
            SectionHeader(stringResource(R.string.lot_by_location_label))
            lot.places.forEach { place ->
                LabeledLine(locationName(place.location), quantityText(place.onHand, unit))
            }
        }
        if (sheet.movements.isNotEmpty()) {
            SectionHeader(stringResource(R.string.lot_movements_label))
            sheet.movements.forEach { row ->
                val reason = row.reason
                val title = if (row.type == StockMovementType.ADJUSTMENT && reason != null) {
                    stringResource(R.string.stock_movement_adjustment_reason, reasonLabel(reason))
                } else {
                    stringResource(movementLabel(row.type))
                }
                MovementLine(
                    title,
                    LocalAppFormats.current.date.dayMonthYear(row.date),
                    signedQuantity(row.quantity, unit),
                )
            }
        }
    }
}

/**
 * Una línea de dos columnas: el nombre a la izquierda y el valor a la derecha, un solo foco. Con
 * letra grande, el valor que no cabe al lado baja bajo el nombre: nunca se corta.
 */
@Composable
private fun LabeledLine(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = AppTheme.sizes.listRowSingle)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LedgerLineLayout(
            gap = AppTheme.spacing.s12,
            modifier = Modifier.weight(1f),
            leading = { Text(label, style = MaterialTheme.typography.bodyLarge) },
            trailing = {
                Text(
                    value,
                    style = AppTheme.amounts.row,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    softWrap = false,
                )
            },
        )
    }
}

/** Un movimiento del lote: qué fue y cuándo, y la cantidad con signo. Un solo foco. */
@Composable
private fun MovementLine(title: String, date: String, quantity: String) {
    val spacing = AppTheme.spacing
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = AppTheme.sizes.listRow)
            .padding(vertical = spacing.s4)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.s12),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.s2)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                date,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            quantity,
            style = AppTheme.amounts.row,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/**
 * Corregir el vencimiento. Admite una fecha pasada, porque un lote puede llegar vencido, y
 * `Sin vencimiento` lo quita. Sin saber qué día es hoy, solo se puede quitar.
 */
@Composable
internal fun FixExpirySheet(
    sheet: LotSheet.FixExpiry,
    ready: LotsReady?,
    onAction: (LotsAction) -> Unit,
) {
    AppBottomSheet(
        onDismissRequest = { onAction(LotsAction.CloseSheet) },
        title = stringResource(R.string.lot_fix_expiry_action),
        action = { SaveButton(sheet.saving, onAction) },
    ) {
        val today = ready?.today
        val weekStart = ready?.weekStartDay
        if (today != null && weekStart != null) {
            DateField(
                label = stringResource(R.string.purchase_lot_expiry_label),
                date = sheet.date,
                onDateChange = { onAction(LotsAction.ExpiryChange(it)) },
                today = today,
                weekStartDay = weekStart,
                latest = null,
            )
        }
        if (sheet.date != null) {
            InlineTextButton(stringResource(R.string.lot_no_expiry), {
                onAction(LotsAction.ExpiryChange(null))
            })
        } else {
            Text(
                stringResource(R.string.lot_no_expiry),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (sheet.failed) SaveFailed()
    }
}

/** Corregir el código. Lo escrito se conserva con cualquier error. */
@Composable
internal fun FixCodeSheet(sheet: LotSheet.FixCode, onAction: (LotsAction) -> Unit) {
    AppBottomSheet(
        onDismissRequest = { onAction(LotsAction.CloseSheet) },
        title = stringResource(R.string.lot_fix_code_action),
        action = { SaveButton(sheet.saving, onAction) },
    ) {
        AppTextField(
            value = sheet.text,
            onValueChange = { onAction(LotsAction.CodeChange(it)) },
            label = stringResource(R.string.purchase_lot_code_label),
            error = when (sheet.error) {
                LotCodeError.BLANK -> stringResource(R.string.lot_error_code_blank)

                LotCodeError.TOO_LONG -> pluralStringResource(
                    R.plurals.error_text_long,
                    LotRules.MAX_CODE_LENGTH,
                    LotRules.MAX_CODE_LENGTH,
                )

                LotCodeError.REPEATED ->
                    stringResource(R.string.lot_error_code_repeated, sheet.repeatedIn.orEmpty())

                null -> null
            },
            imeAction = ImeAction.Done,
            capitalization = KeyboardCapitalization.Characters,
        )
        if (sheet.failed) SaveFailed()
    }
}

/** Los días de aviso de los ajustes, para los productos que no fijan los suyos. */
@Composable
internal fun NoticeSheet(sheet: LotSheet.Notice, onAction: (LotsAction) -> Unit) {
    AppBottomSheet(
        onDismissRequest = { onAction(LotsAction.CloseSheet) },
        title = stringResource(R.string.lot_notice_sheet_title),
        action = { SaveButton(sheet.saving, onAction) },
    ) {
        WholeNumberField(
            value = sheet.text,
            onValueChange = { onAction(LotsAction.NoticeChange(it)) },
            label = stringResource(R.string.lot_notice_days_label),
            error = stringResource(R.string.error_value_high).takeIf { sheet.invalid },
            imeAction = ImeAction.Done,
        )
        if (sheet.failed) SaveFailed()
    }
}

@Composable
private fun SaveButton(saving: Boolean, onAction: (LotsAction) -> Unit) {
    PrimaryActionButton(
        text = stringResource(R.string.action_save),
        onClick = { onAction(LotsAction.SaveSheet) },
        variant = ActionVariant.PRIMARY,
        state = if (saving) ActionState.WORKING else ActionState.IDLE,
    )
}

@Composable
private fun SaveFailed() {
    NoticeCard(
        text = stringResource(R.string.state_error_save),
        variant = NoticeVariant.ERROR,
        icon = R.drawable.ic_error,
    )
}
