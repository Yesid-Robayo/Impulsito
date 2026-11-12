package io.github.yesidrobayo.shopledger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isSensitiveData
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

/**
 * El código de recuperación en 7 grupos de 4 caracteres, para leerlo y anotarlo.
 *
 * - **No se copia.** El texto no es seleccionable, y cada grupo va marcado como dato sensible. La
 *   pantalla que lo muestra pone `FLAG_SECURE`: esto no lo hace por sí solo.
 * - **Grupo por grupo.** TalkBack lee cada grupo por separado, letra por letra, y solo cuando la
 *   persona avanza: el código no se lee entero de una vez.
 * - Cuatro grupos por línea, y dos con la letra grande, sin partir ningún grupo. Cada grupo mide lo
 *   que su texto: con un ancho mínimo, en un teléfono de 360 dp cabían solo tres.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RecoveryCodeBlock(groups: List<String>, modifier: Modifier = Modifier) {
    val spacing = AppTheme.spacing
    val large = LocalDensity.current.fontScale >= LARGE_FONT_SCALE
    FlowRow(
        modifier =
            modifier
                .fillMaxWidth()
                .background(
                    MaterialTheme.colorScheme.surfaceContainerHigh,
                    MaterialTheme.shapes.medium,
                ).padding(spacing.s16),
        horizontalArrangement = Arrangement.spacedBy(spacing.s12),
        verticalArrangement = Arrangement.spacedBy(spacing.s12),
        maxItemsInEachRow = if (large) LARGE_GROUPS_PER_ROW else GROUPS_PER_ROW,
    ) {
        groups.forEachIndexed { index, group ->
            val position = stringResource(R.string.a11y_recovery_code_group, index + 1, groups.size)
            // Letra por letra: leído como palabra, un grupo se confunde.
            val spelled = group.toList().joinToString(" ")
            Text(
                text = group,
                style = AppTheme.amounts.title,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier =
                    Modifier.semantics {
                        contentDescription = spelled
                        stateDescription = position
                        isSensitiveData = true
                    },
            )
        }
    }
}

private const val GROUPS_PER_ROW = 4
private const val LARGE_GROUPS_PER_ROW = 2
private const val LARGE_FONT_SCALE = 1.5f

@ThemePreviews
@Composable
private fun RecoveryCodeBlockPreview() {
    PreviewTheme {
        RecoveryCodeBlock(
            // El código sintético de la especificación del formato: nunca uno real.
            groups = listOf("G7K2", "M9PQ", "R4ST", "V6WX", "Y8Z3", "ABCD", "EF51"),
            modifier = Modifier.padding(AppTheme.spacing.s16),
        )
    }
}
