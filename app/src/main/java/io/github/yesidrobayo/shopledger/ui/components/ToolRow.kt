package io.github.yesidrobayo.shopledger.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

/**
 * Una fila del centro `Negocio`: el nombre de la herramienta, su dato vivo o su descripción debajo, y
 * una flecha. Un dato que pide atención va a la derecha, con icono y color de aviso; con letra grande
 * pasa a su propia línea. Sin iconos de colores: se lee como una libreta, no como un menú de apps.
 *
 * @param attention el dato que pide atención, como `2 vencidas`, o `null`.
 */
@Composable
fun ToolRow(
    name: String,
    detail: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    attention: String? = null,
) {
    val spoken =
        if (attention == null) {
            stringResource(R.string.a11y_hub_tool, name, detail)
        } else {
            stringResource(R.string.a11y_hub_tool_attention, name, attention)
        }
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = AppTheme.sizes.listRow)
                .clickable(onClick = onClick)
                .clearAndSetSemantics {
                    contentDescription = spoken
                    role = Role.Button
                    onClick {
                        onClick()
                        true
                    }
                }.padding(
                    start = AppTheme.spacing.s16,
                    end = AppTheme.spacing.s8,
                    top = AppTheme.spacing.s12,
                    bottom = AppTheme.spacing.s12,
                ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8),
    ) {
        LedgerLineLayout(
            gap = AppTheme.spacing.s12,
            modifier = Modifier.weight(1f),
            leading = {
                Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s2)) {
                    Text(
                        name,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            trailing = {
                if (attention != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s4),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_warning),
                            contentDescription = null,
                            tint = AppTheme.colors.warning,
                            modifier = Modifier.size(AppTheme.sizes.iconTag),
                        )
                        Text(
                            attention,
                            style = MaterialTheme.typography.labelMedium,
                            color = AppTheme.colors.warning,
                        )
                    }
                }
            },
        )
        Icon(
            painter = painterResource(R.drawable.ic_chevron_right),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(AppTheme.sizes.icon),
        )
    }
}

/**
 * El encabezado plegable de un grupo del centro `Negocio`: el nombre del grupo, cuántas herramientas
 * guarda y una flecha que apunta abajo si está plegado y arriba si está abierto. Toda la fila se
 * toca; TalkBack la lee como encabezado y dice si está abierta o plegada.
 */
@Composable
fun ToolGroupRow(
    title: String,
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = AppTheme.sizes.listRowSingle)
                .clickable(role = Role.Button, onClick = onToggle)
                .semantics(mergeDescendants = true) {
                    heading()
                    if (expanded) {
                        collapse {
                            onToggle()
                            true
                        }
                    } else {
                        expand {
                            onToggle()
                            true
                        }
                    }
                }.padding(start = AppTheme.spacing.s16, end = AppTheme.spacing.s8),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            count.toString(),
            style = AppTheme.amounts.small,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Icon(
            painter = painterResource(R.drawable.ic_chevron_right),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            // La flecha de la derecha, girada: abajo para abrir, arriba para plegar.
            modifier =
                Modifier
                    .size(AppTheme.sizes.icon)
                    .rotate(if (expanded) CHEVRON_UP else CHEVRON_DOWN),
        )
    }
}

private const val CHEVRON_DOWN = 90f
private const val CHEVRON_UP = 270f

@ThemePreviews
@Composable
private fun ToolRowPreview() {
    val s = PreviewSamples
    PreviewTheme {
        Column {
            ToolRow(s.TOOL_NAME, s.TOOL_DETAIL, {})
            ToolRow(s.TOOL_NAME, s.TOOL_DETAIL, {}, attention = s.TOOL_ATTENTION)
            ToolGroupRow(s.CATEGORY_A, count = 2, expanded = false, onToggle = {})
            ToolGroupRow(s.LONG_NAME, count = 10, expanded = true, onToggle = {})
        }
    }
}
