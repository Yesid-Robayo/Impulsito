package io.github.yesidrobayo.shopledger.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

/**
 * Lo que muestra la fila de un cliente.
 *
 * [name] es `null` en un cliente anonimizado, que ya no tiene nombre ni teléfono. [balance] es el
 * saldo con signo: positivo debe y negativo es saldo a favor. [points] son sus puntos, o `null` si
 * el programa de puntos está apagado y no se muestran.
 */
@Immutable
data class CustomerRowData(
    val name: String?,
    val balance: Money,
    val phone: String? = null,
    val points: Long? = null,
    val overdue: Boolean = false,
    val archived: Boolean = false,
)

/**
 * Un cliente en una lista, como una fila de libreta: el nombre, debajo su teléfono y sus puntos, y a
 * la derecha su saldo con [BalanceBadge], o `Al día` si no debe ni tiene saldo a favor. Sin avatares
 * ni iniciales.
 *
 * Un cliente anonimizado dice `Cliente anónimo` en tono apagado, con su etiqueta. Uno archivado
 * lleva la suya y, con [onReactivate], el botón `Reactivar`. Con letra grande, el saldo baja debajo
 * del nombre para no dejarlo sin ancho. Una fila, un foco.
 *
 * @param selected si es el cliente abierto en el panel derecho de una ventana ancha. TalkBack lo
 *   dice con palabras, no solo con el fondo.
 */
@Composable
fun CustomerRow(
    data: CustomerRowData,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onReactivate: (() -> Unit)? = null,
    selected: Boolean = false,
) {
    val spacing = AppTheme.spacing
    val scheme = MaterialTheme.colorScheme
    val stacked = LocalDensity.current.fontScale >= STACKED_FONT_SCALE
    Surface(modifier, color = if (selected) scheme.secondaryContainer else scheme.surface) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button, onClick = onClick)
                    .heightIn(min = AppTheme.sizes.listRow)
                    .padding(horizontal = spacing.s16, vertical = spacing.s12)
                    .semantics(mergeDescendants = true) {
                        if (selected) this.selected = true
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.s12),
            ) {
                Column(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(spacing.s2),
                ) {
                    CustomerText(data)
                    if (stacked) Balance(data)
                    Tags(data, onReactivate)
                }
                if (!stacked) Balance(data)
            }
            HorizontalDivider(
                modifier = Modifier.padding(start = spacing.s16),
                thickness = AppTheme.sizes.hairline,
                color = scheme.outlineVariant,
            )
        }
    }
}

/** El nombre y, debajo, el teléfono y los puntos. */
@Composable
private fun CustomerText(data: CustomerRowData) {
    val scheme = MaterialTheme.colorScheme
    val muted = data.name == null || data.archived
    Text(
        data.name ?: stringResource(R.string.customer_anonymous),
        style = MaterialTheme.typography.titleSmall,
        color = if (muted) scheme.onSurfaceVariant else scheme.onSurface,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
    data.phone?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
    }
    val points = data.points
    if (points != null && points != 0L) {
        Text(
            pluralStringResource(
                R.plurals.customer_points_count,
                points.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt(),
                LocalAppFormats.current.quantity.formatCount(points),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
        )
    }
}

/** El saldo: la insignia si hay, o `Al día`. */
@Composable
private fun Balance(data: CustomerRowData) {
    val variant = BalanceBadgeVariant.forCustomer(data.balance, data.overdue)
    if (variant != null) {
        BalanceBadge(variant, data.balance)
    } else {
        Text(
            stringResource(R.string.customer_balance_clear),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Las etiquetas de anonimizado y archivado, con `Reactivar`, debajo del nombre: a su lado le
 * quitarían el ancho con letra grande.
 */
@Composable
private fun Tags(data: CustomerRowData, onReactivate: (() -> Unit)?) {
    if (data.name != null && !data.archived) return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12),
    ) {
        if (data.name == null) StatusTag(stringResource(R.string.customer_anonymized_tag))
        if (data.archived) StatusTag(stringResource(R.string.customer_archived_tag))
        if (data.archived && onReactivate != null) {
            InlineTextButton(stringResource(R.string.action_reactivate), onReactivate)
        }
    }
}

/** Desde esta escala de letra, el saldo va debajo del nombre. */
private const val STACKED_FONT_SCALE = 1.5f

@ThemePreviews
@Composable
private fun CustomerRowPreview() {
    PreviewTheme {
        Column {
            CustomerRow(
                CustomerRowData(
                    PreviewSamples.LONG_NAME,
                    PreviewSamples.LONG_AMOUNT,
                    "300 000 000",
                ),
                onClick = {},
            )
            CustomerRow(
                CustomerRowData(PreviewSamples.SHORT_NAME, Money(-1_500), points = 12),
                onClick = {},
            )
            CustomerRow(CustomerRowData(PreviewSamples.SECOND_NAME, Money.ZERO), onClick = {})
            CustomerRow(CustomerRowData(null, PreviewSamples.PRICE), onClick = {})
            CustomerRow(
                CustomerRowData(PreviewSamples.THIRD_NAME, Money.ZERO, archived = true),
                onClick = {},
                onReactivate = {},
            )
        }
    }
}

@ThemePreviews
@Composable
private fun CustomerRowDiscreetPreview() {
    PreviewTheme(discreet = true) {
        CustomerRow(
            CustomerRowData(PreviewSamples.SHORT_NAME, PreviewSamples.SECOND_PRICE),
            onClick = {},
        )
    }
}
