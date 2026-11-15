package io.github.yesidrobayo.shopledger.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.stock.LotDue
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

/**
 * Cuándo vence un lote, visto desde hoy. [daysLeft] cero vence hoy, y negativo ya venció. [soon]
 * dice si ya está en sus días de aviso. Sin fecha, el lote no vence.
 */
sealed interface LotExpiry {
    data object None : LotExpiry

    data class Dated(val date: BusinessDate, val daysLeft: Int, val soon: Boolean) : LotExpiry
}

/**
 * Cómo se dibuja el vencimiento de [due]: sin fecha, sin marca. Sin saber qué día es hoy no se
 * cuentan días: la fila dice la fecha, sin marca.
 */
fun lotExpiryOf(due: LotDue?): LotExpiry = when (due) {
    null -> LotExpiry.None
    else -> LotExpiry.Dated(due.date, due.daysLeft ?: Int.MAX_VALUE, due.soon)
}

/**
 * Una fila de lote: el código, o `Sin código`, en `titleSmall`; el vencimiento en `bodySmall`; y la
 * cantidad a la derecha. Con [productName] arriba, como en la lista `Por vencer` del inventario.
 *
 * Un lote vencido se marca con el texto `Venció el…` en el color de error: nunca solo con el
 * color, y nunca bloquea nada. TalkBack lee el producto y después el lote, el vencimiento y la
 * cantidad en una sola frase.
 *
 * @param code el código del lote, o `null` sin código.
 * @param unit la unidad en que se cuenta [onHand].
 * @param onClick tocar la fila; `null` si no hace nada.
 * @param name el nombre que reemplaza al código, como `Lote sin asignar`.
 * @param location la ubicación del lote, cuando hay más de una.
 * @param note el texto que reemplaza al vencimiento, como el del lote sin asignar.
 */
@Composable
fun LotRow(
    code: String?,
    expiry: LotExpiry,
    onHand: QuantityMilli,
    unit: UnitLabel,
    modifier: Modifier = Modifier,
    productName: String? = null,
    onClick: (() -> Unit)? = null,
    divider: Boolean = true,
    name: String? = null,
    location: String? = null,
    note: String? = null,
) {
    val spacing = AppTheme.spacing
    val lot = name
        ?: code?.let { stringResource(R.string.lot_name, it) }
        ?: stringResource(R.string.lot_no_code)
    val dated = lotExpiryText(expiry)
    val expiryText = note
        ?: location?.let { stringResource(R.string.lot_expiry_location, dated, it) }
        ?: dated
    val quantity = quantityText(onHand, unit)
    val description = stringResource(R.string.a11y_lot_row, lot, expiryText, quantity)
    val mark = lotExpiryMark(expiry)
    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (onClick != null) {
                        Modifier.clickable(role = Role.Button, onClick = onClick)
                    } else {
                        Modifier
                    },
                )
                .heightIn(min = AppTheme.sizes.listRow)
                .padding(horizontal = spacing.s16, vertical = spacing.s8),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.s12),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.s2)) {
                productName?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(
                    Modifier.clearAndSetSemantics { contentDescription = description },
                    verticalArrangement = Arrangement.spacedBy(spacing.s2),
                ) {
                    Text(lot, style = MaterialTheme.typography.titleSmall)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(spacing.s4),
                    ) {
                        mark?.let { (icon, tint) ->
                            Icon(
                                painter = painterResource(icon),
                                contentDescription = null,
                                tint = tint,
                                modifier = Modifier.size(spacing.s16),
                            )
                        }
                        Text(
                            expiryText,
                            style = MaterialTheme.typography.bodySmall,
                            color = mark?.second ?: MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Text(
                text = quantity,
                style = AppTheme.amounts.row,
                // Un saldo negativo, solo posible en el lote sin asignar, va en pérdida.
                color = if (onHand < QuantityMilli.ZERO) {
                    AppTheme.colors.loss
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.clearAndSetSemantics {},
            )
        }
        if (divider) {
            HorizontalDivider(
                modifier = Modifier.padding(start = spacing.s16),
                thickness = AppTheme.sizes.hairline,
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }
    }
}

/**
 * El vencimiento de un lote como se lee en la fila: cuándo vence; en sus días de aviso, en cuántos
 * días o que vence hoy; y cuándo venció.
 */
@Composable
fun lotExpiryText(expiry: LotExpiry): String = when (expiry) {
    LotExpiry.None -> stringResource(R.string.lot_no_expiry)

    is LotExpiry.Dated -> {
        val left = expiry.daysLeft
        val date = LocalAppFormats.current.date.dayMonthYear(expiry.date)
        when {
            left < 0 -> stringResource(R.string.lot_expired_on, date)
            !expiry.soon -> stringResource(R.string.lot_expires_on, date)
            left == 0 -> stringResource(R.string.lot_expires_today)
            else -> pluralStringResource(R.plurals.lot_expires_in_date, left, left, date)
        }
    }
}

/**
 * El icono y el color del vencimiento, como los fija el sistema de diseño: `schedule` en
 * advertencia mientras está por vencer, y `block` en pérdida si ya venció. `null` sin marca. El
 * texto siempre dice lo mismo: el color nunca va solo.
 */
@Composable
private fun lotExpiryMark(expiry: LotExpiry): Pair<Int, Color>? = when {
    expiry !is LotExpiry.Dated -> null
    expiry.daysLeft < 0 -> R.drawable.ic_block to AppTheme.colors.loss
    expiry.soon -> R.drawable.ic_schedule to AppTheme.colors.warning
    else -> null
}

@ThemePreviews
@Composable
private fun LotRowPreview() {
    val units = UnitLabel("u", allowsFraction = false)
    PreviewTheme {
        Column {
            LotRow(
                "A-118",
                LotExpiry.Dated(requireNotNull(BusinessDate.parse("2026-11-20")), 6, soon = true),
                QuantityMilli(12_000L),
                units,
                productName = PreviewSamples.SHORT_NAME,
                onClick = {},
            )
            LotRow(
                "L-0042",
                LotExpiry.Dated(requireNotNull(BusinessDate.parse("2026-11-10")), -4, soon = true),
                QuantityMilli(3_000L),
                units,
                productName = PreviewSamples.LONG_NAME,
                onClick = {},
            )
            LotRow(null, LotExpiry.None, QuantityMilli(1_234_567_000L), units)
        }
    }
}
