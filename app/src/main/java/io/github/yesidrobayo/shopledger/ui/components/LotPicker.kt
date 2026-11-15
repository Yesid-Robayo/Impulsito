package io.github.yesidrobayo.shopledger.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.stock.LotChoices
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

/**
 * Un lote que se puede elegir: su código, o `null` sin código, cuándo vence y lo que tiene donde
 * se saca. [unassigned] marca el lote sin asignar, que se nombra como tal.
 */
@Immutable
data class LotOption(
    val lotId: Long,
    val code: String?,
    val expiry: LotExpiry,
    val onHand: QuantityMilli,
    val unassigned: Boolean = false,
)

/**
 * Las opciones de [choices], en el orden de salida. Con [withUnassigned], al final va el lote sin
 * asignar, si existe: elegirlo saca de él, como lo que se mueve sin lote.
 */
fun lotOptionsOf(choices: LotChoices, withUnassigned: Boolean): List<LotOption> {
    val coded = choices.lots.map {
        LotOption(it.stock.lotId, it.stock.code, lotExpiryOf(it.due), it.stock.onHand)
    }
    val unassigned = choices.unassigned?.takeIf { withUnassigned }?.let {
        LotOption(it.lotId, null, LotExpiry.None, it.onHand, unassigned = true)
    }
    return coded + listOfNotNull(unassigned)
}

/**
 * El lote de una salida: un campo que dice cuál sale y abre una hoja con los lotes en el orden de
 * salida, el que vence antes primero. Con [allowAuto], arriba va `Asignar automático`, que es lo
 * elegido mientras [selectedId] sea `null`: la persona solo interviene si quiere.
 *
 * Sin opciones no dibuja nada: un producto sin lotes con stock no tiene nada que elegir.
 *
 * @param options los lotes, ya en el orden de salida.
 * @param onSelect el lote elegido, o `null` para volver a lo automático.
 * @param label el rótulo del campo: `Lote`, o `Cambiar lote` en la línea de una venta.
 */
@Composable
fun LotPicker(
    options: List<LotOption>,
    selectedId: Long?,
    unit: UnitLabel,
    onSelect: (Long?) -> Unit,
    modifier: Modifier = Modifier,
    allowAuto: Boolean = true,
    label: String = stringResource(R.string.lot_picker_label),
) {
    if (options.isEmpty()) return
    var open by rememberSaveable { mutableStateOf(false) }
    val selected = options.firstOrNull { it.lotId == selectedId }
    PickerField(
        label = label,
        value = selected?.let { lotOptionName(it) } ?: stringResource(R.string.lot_picker_auto),
        onClick = { open = true },
        modifier = modifier,
    )
    if (open) {
        AppBottomSheet(
            onDismissRequest = { open = false },
            title = stringResource(R.string.lot_picker_title),
        ) {
            LotPickerOptions(options, selected?.lotId, unit, allowAuto) { id ->
                open = false
                onSelect(id)
            }
        }
    }
}

/** El nombre de un lote: `Lote A-118`, `Sin código` o `Lote sin asignar`. */
@Composable
fun lotOptionName(option: LotOption): String = when {
    option.unassigned -> stringResource(R.string.lot_unassigned_name)
    option.code != null -> stringResource(R.string.lot_name, option.code)
    else -> stringResource(R.string.lot_no_code)
}

/** Las opciones de la hoja, aparte para poder verlas sin abrirla. */
@Composable
fun ColumnScope.LotPickerOptions(
    options: List<LotOption>,
    selectedId: Long?,
    unit: UnitLabel,
    allowAuto: Boolean,
    onSelect: (Long?) -> Unit,
) {
    // Un grupo de opciones excluyentes: TalkBack dice cuál es y de cuántas. Las filas guardan el
    // espacio de la hoja entre ellas.
    Column(
        Modifier.selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8),
    ) {
        if (allowAuto) {
            ChoiceRow(selected = selectedId == null, onClick = { onSelect(null) }) {
                Column(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s2),
                ) {
                    Text(
                        stringResource(R.string.lot_picker_auto),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        stringResource(R.string.lot_picker_auto_help),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        options.forEach { option ->
            ChoiceRow(
                selected = option.lotId == selectedId,
                onClick = { onSelect(option.lotId) },
            ) {
                LotRow(
                    code = option.code,
                    expiry = option.expiry,
                    onHand = option.onHand,
                    unit = unit,
                    modifier = Modifier.weight(1f),
                    divider = false,
                    name = if (option.unassigned) {
                        stringResource(
                            R.string.lot_unassigned_name,
                        )
                    } else {
                        null
                    },
                )
            }
        }
    }
}

/** Una opción tocable con su marca: un toque la elige, y la elegida lleva la marca. */
@Composable
private fun ChoiceRow(
    selected: Boolean,
    onClick: () -> Unit,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = AppTheme.sizes.touchTarget)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { this.selected = selected },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s4),
    ) {
        Icon(
            painter = painterResource(
                if (selected) R.drawable.ic_check_circle else R.drawable.ic_radio_button_unchecked,
            ),
            contentDescription = null,
            tint = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(AppTheme.sizes.iconTag),
        )
        content()
    }
}

@ThemePreviews
@Composable
private fun LotPickerPreview() {
    val units = UnitLabel("u", allowsFraction = false)
    val options = listOf(
        LotOption(
            1L,
            "A-118",
            LotExpiry.Dated(requireNotNull(BusinessDate.parse("2026-11-10")), -4, soon = true),
            QuantityMilli(3_000L),
        ),
        LotOption(
            2L,
            PreviewSamples.LONG_NAME.take(60),
            LotExpiry.Dated(requireNotNull(BusinessDate.parse("2026-11-20")), 6, soon = true),
            QuantityMilli(1_234_567_000L),
        ),
        LotOption(3L, "B-7", LotExpiry.None, QuantityMilli(12_000L)),
    )
    PreviewTheme {
        Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8)) {
            LotPicker(options, selectedId = null, unit = units, onSelect = {})
            LotPicker(options, selectedId = 2L, unit = units, onSelect = {})
            Column(Modifier.padding(horizontal = AppTheme.spacing.s16)) {
                LotPickerOptions(options, selectedId = 1L, unit = units, allowAuto = true) {}
            }
        }
    }
}
