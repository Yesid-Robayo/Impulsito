package io.github.yesidrobayo.shopledger.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

/**
 * La fila de un documento para el cliente, como una cotización o un pedido: el número a la
 * izquierda, el cliente debajo y la fecha que importa, como la vigencia o la entrega, en la tercera
 * línea; a la derecha, el total y, si el documento no está en su estado normal, su etiqueta.
 *
 * Toda la fila es un foco: TalkBack lee número, cliente, fecha, total y estado juntos. Con letra
 * grande o un total largo, las cifras pasan debajo del texto, sin cortarse.
 *
 * Un documento para el cliente nunca lleva costo ni ganancia, así que el total no se oculta con el
 * modo discreto: es lo que se le ofrece a quien se atiende.
 *
 * @param title el documento, como `Cotización 41`.
 * @param customer el cliente, o el texto de sin cliente.
 * @param detail la fecha que importa, como `Vigente hasta 28 oct`, o `null`.
 * @param status la etiqueta del estado, o `null` en su estado normal.
 */
@Composable
fun DocumentRow(
    title: String,
    customer: String,
    detail: String?,
    total: Money,
    modifier: Modifier = Modifier,
    status: String? = null,
    statusTone: StatusTone = StatusTone.NEUTRAL,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
) {
    val spacing = AppTheme.spacing
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val touch =
        if (onClick == null) {
            Modifier
        } else {
            Modifier.clickable(role = Role.Button, onClickLabel = onClickLabel, onClick = onClick)
        }
    // Tocable, el clic ya junta todo en un foco con nombre; otro foco adentro lo dejaría sin nombre
    // para TalkBack.
    val merge = if (onClick == null) Modifier.semantics(mergeDescendants = true) {} else Modifier
    Column(modifier.fillMaxWidth().then(touch)) {
        LedgerLineLayout(
            gap = spacing.s12,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = AppTheme.sizes.listRow)
                    .padding(horizontal = spacing.s16, vertical = spacing.s12)
                    .then(merge),
            leading = {
                Column(verticalArrangement = Arrangement.spacedBy(spacing.s2)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = customer,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    detail?.let {
                        Text(text = it, style = MaterialTheme.typography.bodySmall, color = muted)
                    }
                }
            },
            trailing = { alignment ->
                Column(
                    horizontalAlignment = alignment,
                    verticalArrangement = Arrangement.spacedBy(spacing.s4),
                ) {
                    MoneyText(amount = total, size = MoneySize.ROW)
                    status?.let { StatusTag(text = it, tone = statusTone) }
                }
            },
        )
        HorizontalDivider(
            modifier = Modifier.padding(start = spacing.s16),
            thickness = AppTheme.sizes.hairline,
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

// ---------------------------------------------------------------- vistas previas

@ThemePreviews
@Composable
private fun DocumentRowPreview() {
    PreviewTheme {
        Column {
            DocumentRow(
                title = PreviewSamples.DOCUMENT_TITLE,
                customer = PreviewSamples.CUSTOMER_NAME,
                detail = PreviewSamples.DOCUMENT_DETAIL,
                total = PreviewSamples.PRICE,
                onClick = {},
            )
            DocumentRow(
                title = PreviewSamples.DOCUMENT_TITLE,
                customer = PreviewSamples.LONG_NAME,
                detail = null,
                total = PreviewSamples.LONG_AMOUNT,
                status = PreviewSamples.DOCUMENT_STATUS,
                statusTone = StatusTone.WARNING,
            )
        }
    }
}
