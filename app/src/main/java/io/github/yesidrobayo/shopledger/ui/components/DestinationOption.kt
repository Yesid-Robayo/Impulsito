package io.github.yesidrobayo.shopledger.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

/**
 * Una opción de dónde guardar las copias: una tarjeta tocable a ancho completo, con el título, la
 * etiqueta de lo que la distingue, el cuerpo con lo que exige y una nota.
 *
 * Las opciones pesan lo mismo: mismo fondo, mismo borde y ninguna preseleccionada, porque la app no
 * empuja hacia ninguna. La conectada solo suma la etiqueta [currentLabel], en el tono informativo y
 * con un visto, para que se reconozca sin que la tarjeta pese más que la otra.
 *
 * Es un solo foco para TalkBack, con el papel de botón. Con letra grande, las etiquetas bajan bajo el
 * título y la tarjeta crece en alto.
 *
 * @param currentLabel el texto de la etiqueta del destino conectado, o `null` si no es esta.
 * @param enabled falso para mostrarla sin que se pueda tocar.
 */
@Composable
fun DestinationOption(
    title: String,
    tag: String,
    body: String,
    note: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    currentLabel: String? = null,
    enabled: Boolean = true,
) {
    val spacing = AppTheme.spacing
    AppCard(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = AppTheme.sizes.touchTarget)
                .semantics(mergeDescendants = true) {
                    role = Role.Button
                    // Sin toque, TalkBack la dice desactivada: no un botón que no responde.
                    if (!enabled) disabled()
                },
        onClick = if (enabled) onClick else null,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.s8),
        ) {
            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(spacing.s8),
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(spacing.s8),
                    verticalArrangement = Arrangement.spacedBy(spacing.s4),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    StatusTag(tag)
                    if (currentLabel != null) {
                        StatusTag(currentLabel, icon = R.drawable.ic_check, tone = StatusTone.INFO)
                    }
                }
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (enabled) {
                Icon(
                    painter = painterResource(R.drawable.ic_chevron_right),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(AppTheme.sizes.icon),
                )
            }
        }
    }
}

@ThemePreviews
@Composable
private fun DestinationOptionPreview() {
    PreviewTheme {
        val app = stringResource(R.string.app_name)
        Column(
            Modifier.padding(AppTheme.spacing.s16),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12),
        ) {
            DestinationOption(
                title = stringResource(R.string.destination_slots_title),
                tag = stringResource(R.string.destination_slots_tag),
                body = stringResource(R.string.destination_slots_body, app),
                note = stringResource(R.string.destination_slots_note),
                onClick = {},
            )
            DestinationOption(
                title = stringResource(R.string.destination_webdav_title),
                tag = stringResource(R.string.destination_webdav_tag),
                body = stringResource(R.string.destination_webdav_body, app),
                note = stringResource(R.string.destination_webdav_note),
                onClick = {},
                currentLabel = stringResource(R.string.destination_current_tag),
            )
        }
    }
}
