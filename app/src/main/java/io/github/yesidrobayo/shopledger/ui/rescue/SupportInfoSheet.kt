package io.github.yesidrobayo.shopledger.ui.rescue

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.ui.components.ActionVariant
import io.github.yesidrobayo.shopledger.ui.components.AppBottomSheet
import io.github.yesidrobayo.shopledger.ui.components.PrimaryActionButton
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

/**
 * La hoja `Información para soporte`, en el rescate y en Ajustes: qué incluye y qué no, una línea por
 * dato técnico y `Copiar`. Nunca muestra ni copia un dato del negocio.
 *
 * @param extra lo que se suma al final, como el registro de fallos del rescate.
 */
@Composable
fun SupportInfoSheet(
    lines: List<SupportLine>,
    onCopy: () -> Unit,
    onDismiss: () -> Unit,
    extra: @Composable () -> Unit = {},
) {
    AppBottomSheet(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.settings_support),
        action = {
            PrimaryActionButton(stringResource(R.string.action_copy), onCopy, ActionVariant.PRIMARY)
        },
    ) {
        SupportInfoContent(lines, extra)
    }
}

/** Lo de adentro de la hoja, aparte para verlo sin abrirla. */
@Composable
fun SupportInfoContent(lines: List<SupportLine>, extra: @Composable () -> Unit = {}) {
    Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12)) {
        Text(
            stringResource(R.string.support_help),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        lines.forEach { line ->
            // Etiqueta y valor son un solo foco: TalkBack los lee juntos.
            Column(Modifier.semantics(mergeDescendants = true) {}) {
                Text(
                    line.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(line.value, style = MaterialTheme.typography.bodyLarge)
            }
        }
        extra()
    }
}

@ThemePreviews
@Composable
private fun SupportInfoContentPreview() {
    PreviewTheme {
        SupportInfoContent(
            listOf(
                SupportLine(stringResource(R.string.support_field_android), "16 (36)"),
                SupportLine(stringResource(R.string.support_field_last_error), "LOCAL_INVARIANT"),
            ),
        )
    }
}
