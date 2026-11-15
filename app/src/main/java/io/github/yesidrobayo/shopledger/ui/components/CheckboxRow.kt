package io.github.yesidrobayo.shopledger.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

/**
 * Una casilla con su texto, como `Admite decimales` o `Para vender`: toda la fila es un solo objetivo
 * de 48 dp y un solo foco, que TalkBack lee como casilla con su estado.
 *
 * Marcada, la casilla va en el color de acción. La casilla de la confirmación reforzada de
 * [ConfirmDialog] va en el color de error, porque confirma algo que no se deshace.
 *
 * @param supporting una ayuda debajo del texto, que crece en alto con la letra grande. TalkBack la
 *   lee junto con la casilla, en el mismo foco.
 * @param enabled apagada, la casilla no cambia y se ve atenuada.
 */
@Composable
fun CheckboxRow(
    text: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    enabled: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = AppTheme.sizes.touchTarget)
                .toggleable(
                    value = checked,
                    enabled = enabled,
                    role = Role.Checkbox,
                    onValueChange = onCheckedChange,
                ),
        verticalAlignment = if (supporting == null) Alignment.CenterVertically else Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12),
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            colors =
                CheckboxDefaults.colors(
                    checkedColor = scheme.primary,
                    checkmarkColor = scheme.onPrimary,
                    uncheckedColor = scheme.outline,
                ),
        )
        if (supporting == null) {
            Text(text, style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface)
        } else {
            Column(
                Modifier.padding(vertical = AppTheme.spacing.s12),
                verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s2),
            ) {
                Text(text, style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface)
                Text(
                    supporting,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
    }
}

@ThemePreviews
@Composable
private fun CheckboxRowPreview() {
    PreviewTheme {
        Column {
            var first by remember { mutableStateOf(true) }
            var second by remember { mutableStateOf(false) }
            CheckboxRow(PreviewSamples.LONG_NAME, first, { first = it })
            CheckboxRow("Para comprar", second, { second = it })
            CheckboxRow(
                PreviewSamples.SETTING_TITLE,
                checked = true,
                onCheckedChange = {},
                supporting = PreviewSamples.NOTICE_TEXT,
            )
            CheckboxRow("Para vender", false, {}, enabled = false)
        }
    }
}
