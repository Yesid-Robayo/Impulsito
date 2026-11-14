package io.github.yesidrobayo.shopledger.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.ui.currency.rememberMoneyDisplay
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

/**
 * Lo que muestra una fila de documento: una venta, una cotización o un pedido.
 *
 * - [title] es el documento con su número, como `Venta 1.204`.
 * - [detail] es la segunda línea: la fecha y el cliente, o `Sin cliente`.
 * - [status] es el estado del documento, que TalkBack dice siempre. [tags] son etiquetas que solo
 *   aparecen si el documento no está en su estado normal, como `Con devolución`.
 * - [note] es una línea más, como la referencia externa, o `null`.
 */
@Immutable
data class SelectableDocumentRowData(
    val title: String,
    val detail: String,
    val total: Money,
    val status: String,
    val tags: List<String> = emptyList(),
    val note: String? = null,
)

/**
 * La fila de una lista de documentos que se pueden elegir varios a la vez, como los pendientes de
 * facturar: la misma forma de la fila de documento, con 72 dp de alto mínimo, el número y la fecha
 * con el cliente a la izquierda, y el total en `amounts.row` a la derecha. Un solo foco para
 * TalkBack, que dice documento, cliente, monto con el nombre de la moneda y estado.
 *
 * Con [selecting], la fila muestra si está elegida con un icono, no solo con el color, y TalkBack
 * dice `Seleccionada`. [onLongClick] empieza a elegir varias; con TalkBack, la misma acción es
 * `Seleccionar`, una acción personalizada de la fila.
 *
 * Nunca lleva costo, margen ni ganancia: el total es lo que el cliente ya conoce.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SelectableDocumentRow(
    data: SelectableDocumentRowData,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    selecting: Boolean = false,
    selected: Boolean = false,
    divider: Boolean = true,
) {
    val spacing = AppTheme.spacing
    val scheme = MaterialTheme.colorScheme
    val money = rememberMoneyDisplay(data.total, LocalAppFormats.current.money)
    val spoken = stringResource(
        R.string.a11y_document_row,
        data.title,
        data.detail,
        money.description,
        data.status,
    )
    // Las etiquetas y la nota también se dicen: la fila es un solo foco.
    val spokenRow = (listOf(spoken) + data.tags + listOfNotNull(data.note)).joinToString(", ")
    val selectLabel = stringResource(R.string.a11y_doc_select_action)
    val selectedLabel = stringResource(R.string.a11y_doc_selected)
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = if (selected) scheme.secondaryContainer else scheme.surface,
        contentColor = if (selected) scheme.onSecondaryContainer else scheme.onSurface,
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = AppTheme.sizes.listRow)
                    .then(
                        if (onClick != null || onLongClick != null) {
                            Modifier.combinedClickable(
                                onClick = { onClick?.invoke() },
                                onLongClick = onLongClick,
                            )
                        } else {
                            Modifier
                        },
                    )
                    .clearAndSetSemantics {
                        contentDescription = spokenRow
                        if (selecting && selected) stateDescription = selectedLabel
                        if (onClick != null) {
                            onClick {
                                onClick()
                                true
                            }
                        }
                        if (onLongClick != null && !selecting) {
                            customActions = listOf(
                                CustomAccessibilityAction(selectLabel) {
                                    onLongClick()
                                    true
                                },
                            )
                        }
                    }
                    .padding(horizontal = spacing.s16, vertical = spacing.s12),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.s12),
            ) {
                if (selecting) {
                    Icon(
                        painter = painterResource(
                            if (selected) {
                                R.drawable.ic_check_circle
                            } else {
                                R.drawable.ic_radio_button_unchecked
                            },
                        ),
                        contentDescription = null,
                        tint = if (selected) scheme.primary else scheme.onSurfaceVariant,
                        modifier = Modifier.size(AppTheme.sizes.icon),
                    )
                }
                LedgerLineLayout(
                    gap = spacing.s12,
                    modifier = Modifier.weight(1f),
                    leading = { DocumentRowLeading(data) },
                    trailing = { MoneyText(data.total, MoneySize.ROW) },
                )
            }
            if (divider) {
                HorizontalDivider(
                    modifier = Modifier.padding(start = spacing.s16),
                    thickness = AppTheme.sizes.hairline,
                    color = scheme.outlineVariant,
                )
            }
        }
    }
}

@Composable
private fun DocumentRowLeading(data: SelectableDocumentRowData) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s2)) {
        Text(
            text = data.title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = data.detail,
            style = MaterialTheme.typography.bodySmall,
            color = muted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        data.note?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = muted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (data.tags.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8),
                verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s4),
            ) {
                data.tags.forEach { StatusTag(it) }
            }
        }
    }
}

@ThemePreviews
@Composable
private fun DocumentRowPreview() {
    PreviewTheme {
        val pending = stringResource(R.string.fiscal_status_pending)
        Column {
            SelectableDocumentRow(
                SelectableDocumentRowData(
                    title = stringResource(R.string.fiscal_sale_row, "1.204"),
                    detail = PreviewSamples.LONG_NAME,
                    total = PreviewSamples.LONG_AMOUNT,
                    status = pending,
                    note = stringResource(R.string.fiscal_reference_row, "A-1043"),
                ),
                onClick = {},
                onLongClick = {},
            )
            SelectableDocumentRow(
                SelectableDocumentRowData(
                    title = stringResource(R.string.fiscal_sale_row, "1.198"),
                    detail = stringResource(R.string.fiscal_no_customer),
                    total = PreviewSamples.SECOND_PRICE,
                    status = pending,
                    tags = listOf(stringResource(R.string.fiscal_return_tag)),
                ),
                onClick = {},
                onLongClick = {},
                selecting = true,
                selected = true,
            )
            SelectableDocumentRow(
                SelectableDocumentRowData(
                    title = stringResource(R.string.fiscal_sale_row, "1.187"),
                    detail = stringResource(R.string.fiscal_no_customer),
                    total = PreviewSamples.PRICE,
                    status = pending,
                ),
                onClick = {},
                selecting = true,
            )
        }
    }
}
