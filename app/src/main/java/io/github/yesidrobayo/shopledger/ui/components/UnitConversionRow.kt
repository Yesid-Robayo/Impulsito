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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

/**
 * Una conversión que se lee como frase: el producto, si hace falta decirlo, `1 caja = 24 und`, y
 * debajo dónde se ofrece, como `Para comprar · Para vender`.
 *
 * Toda la fila es un solo foco y un solo objetivo táctil, que abre la conversión. TalkBack lee
 * [reading], con la cantidad en palabras y sin el signo igual. Una línea por dato, sin recortar: con
 * letra grande cada una crece hacia abajo.
 *
 * @param title el producto, en la lista de todas las conversiones; `null` dentro del producto mismo.
 * @param uses dónde se ofrece, ya traducido. Vacío, no ocupa línea.
 * @param inset falso dentro de un formulario, que ya tiene su margen lateral.
 */
@Composable
fun UnitConversionRow(
    phrase: String,
    uses: List<String>,
    reading: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    inset: Boolean = true,
) {
    val spacing = AppTheme.spacing
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = AppTheme.sizes.listRowSingle)
                .clickable(onClick = onClick)
                .clearAndSetSemantics {
                    contentDescription = reading
                    role = Role.Button
                }
                .then(if (inset) Modifier.padding(horizontal = spacing.s16) else Modifier)
                .padding(vertical = spacing.s8),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.s16),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.s2)) {
            if (title != null) {
                Text(title, style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface)
            }
            Text(
                phrase,
                style =
                    if (title == null) {
                        MaterialTheme.typography.bodyLarge
                    } else {
                        MaterialTheme.typography.bodyMedium
                    },
                color = scheme.onSurface,
            )
            if (uses.isNotEmpty()) {
                Text(
                    uses.joinToString(USES_SEPARATOR),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
        Icon(
            painter = painterResource(R.drawable.ic_chevron_right),
            contentDescription = null,
            tint = scheme.onSurfaceVariant,
            modifier = Modifier.size(AppTheme.sizes.icon),
        )
    }
}

/** Separa las etiquetas de uso, como en las demás filas de la app: un punto medio entre espacios. */
private const val USES_SEPARATOR = " · "

@ThemePreviews
@Composable
private fun UnitConversionRowPreview() {
    PreviewTheme {
        Column {
            UnitConversionRow(
                phrase = "1 caja = 24 und",
                uses = listOf("Para comprar"),
                reading = "Arroz 1 kg: una caja equivale a 24 unidades, Para comprar",
                onClick = {},
                title = PreviewSamples.SHORT_NAME,
            )
            UnitConversionRow(
                phrase = "1 bulto = 50 kg",
                uses = listOf("Para comprar", "Para vender"),
                reading = "bulto",
                onClick = {},
                title = PreviewSamples.LONG_NAME,
            )
            UnitConversionRow(
                phrase = "1 docena = 12 und",
                uses = emptyList(),
                reading = "docena",
                onClick = {},
            )
        }
    }
}
