package io.github.yesidrobayo.shopledger.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

/** Cómo quedó relacionada una columna del archivo. */
enum class ColumnMapState {
    /** No se reconoció y nadie eligió: va primero, con aviso. */
    UNMATCHED,

    /** Se reconoció por su encabezado. */
    RECOGNIZED,

    /** La persona eligió qué trae, o que no se importe. */
    CHOSEN,
}

/**
 * Una columna del archivo junto al dato de la app que trae: su encabezado y hasta dos ejemplos a la
 * izquierda, y el dato elegido a la derecha, con una flecha que abre la lista de datos. Toda la
 * fila se toca y es un solo foco para TalkBack. Una columna sin relacionar lleva su aviso en color
 * y con icono, nunca solo con color; una marcada con [error] por un dato repetido, también.
 *
 * Los ejemplos son texto del archivo: se muestran tal cual, en una línea, y nada se ejecuta.
 */
@Composable
fun ColumnMapRow(
    header: String,
    examples: List<String>,
    field: String,
    state: ColumnMapState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    error: String? = null,
) {
    val spacing = AppTheme.spacing
    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = AppTheme.sizes.listRow)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(
                    start = spacing.s16,
                    end = spacing.s8,
                    top = spacing.s12,
                    bottom = spacing.s12,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.s12),
        ) {
            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(spacing.s2),
            ) {
                Text(
                    header,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                examples.forEach { example ->
                    Text(
                        stringResource(R.string.import_column_example, example),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                when {
                    error != null -> Warning(error, AppTheme.colors.loss, R.drawable.ic_error)

                    state == ColumnMapState.UNMATCHED -> Warning(
                        stringResource(R.string.import_column_unmatched),
                        AppTheme.colors.warning,
                        R.drawable.ic_warning,
                    )

                    state == ColumnMapState.RECOGNIZED -> Text(
                        stringResource(R.string.import_column_recognized),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    else -> Unit
                }
            }
            Text(
                field,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.End,
                modifier = Modifier.widthIn(max = AppTheme.sizes.maxContentWidth / 3),
            )
            Icon(
                painter = painterResource(R.drawable.ic_chevron_right),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(AppTheme.sizes.icon),
            )
        }
        HorizontalDivider(
            thickness = AppTheme.sizes.hairline,
            color = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.padding(start = spacing.s16),
        )
    }
}

/**
 * Una fila del archivo que no se importa: `Fila 17 · precio` y el motivo debajo, con el icono de
 * error. Con [warning], la fila sí entra pero con un cambio, y lleva el icono de aviso. Es un solo
 * foco para TalkBack.
 */
@Composable
fun ImportIssueRow(
    row: Int,
    column: String,
    reason: String,
    modifier: Modifier = Modifier,
    warning: Boolean = false,
) {
    val spacing = AppTheme.spacing
    val tint = if (warning) AppTheme.colors.warning else AppTheme.colors.loss
    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = spacing.s16, vertical = spacing.s8),
        horizontalArrangement = Arrangement.spacedBy(spacing.s12),
    ) {
        Icon(
            painter = painterResource(if (warning) R.drawable.ic_warning else R.drawable.ic_error),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(AppTheme.sizes.iconTag),
        )
        Column(verticalArrangement = Arrangement.spacedBy(spacing.s2)) {
            Text(
                stringResource(R.string.import_issue_row, row, column),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Warning(text: String, color: androidx.compose.ui.graphics.Color, icon: Int) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s4),
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(AppTheme.sizes.iconTag),
        )
        Text(text, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

@ThemePreviews
@Composable
private fun ImportRowsPreview() {
    val s = PreviewSamples
    PreviewTheme {
        Column {
            ColumnMapRow(
                header = s.LONG_NAME,
                examples = listOf("12.500", s.LONG_NAME),
                field = stringResource(R.string.import_column_choose),
                state = ColumnMapState.UNMATCHED,
                onClick = {},
            )
            ColumnMapRow(
                header = s.CATEGORY_A,
                examples = listOf(s.CATEGORY_A),
                field = s.CATEGORY_A,
                state = ColumnMapState.RECOGNIZED,
                onClick = {},
            )
            ColumnMapRow(
                header = s.CATEGORY_A,
                examples = emptyList(),
                field = s.CATEGORY_A,
                state = ColumnMapState.CHOSEN,
                onClick = {},
                error = stringResource(R.string.import_error_duplicate_column, s.CATEGORY_A),
            )
            ImportIssueRow(17, s.CATEGORY_A, stringResource(R.string.import_issue_amount))
            ImportIssueRow(
                203,
                s.LONG_NAME,
                stringResource(R.string.import_issue_unit_unknown),
                warning = true,
            )
        }
    }
}
