package io.github.yesidrobayo.shopledger.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.format.NO_VALUE_TEXT
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

/**
 * Una copia de la lista, con sus textos ya escritos.
 *
 * @param whenText la fecha y la hora de la copia, o nulo si no se sabe.
 * @param sizeText su tamaño.
 * @param originText de qué teléfono salió, o con ranuras, en qué archivo está.
 * @param recordsText cuántos registros trae, o nulo si sus metadatos no lo dicen: nunca un cero.
 * @param tags las etiquetas que la acompañan: fijada, con diferencias o imposible de restaurar.
 */
@Immutable
data class BackupCopyRowData(
    val whenText: String?,
    val sizeText: String,
    val originText: String,
    val mostRecent: Boolean,
    val recordsText: String?,
    val tags: List<String>,
)

/**
 * Una fila de copia: la fecha y el tamaño en la primera línea, el teléfono de origen con
 * `Más reciente` y sus etiquetas en la segunda, y los registros en la tercera. Las cifras van a la
 * derecha, y un filete fino la separa de la siguiente. Es un solo foco para TalkBack.
 *
 * @param onClick abre la hoja de la copia para restaurarla; nulo si la copia no se puede restaurar.
 */
@Composable
fun BackupCopyRow(
    data: BackupCopyRowData,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val spacing = AppTheme.spacing
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier) {
        Column(
            Modifier
                .fillMaxWidth()
                .then(
                    if (onClick == null) {
                        Modifier
                    } else {
                        Modifier.clickable(role = Role.Button, onClick = onClick)
                    },
                )
                .heightIn(min = AppTheme.sizes.listRow)
                .semantics(mergeDescendants = true) {}
                .padding(horizontal = spacing.s16, vertical = spacing.s12),
            verticalArrangement = Arrangement.spacedBy(spacing.s2),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.s12),
            ) {
                Text(
                    data.whenText ?: NO_VALUE_TEXT,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Text(data.sizeText, style = AppTheme.amounts.row)
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(spacing.s8),
                verticalArrangement = Arrangement.spacedBy(spacing.s4),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                val origin =
                    if (data.mostRecent) {
                        stringResource(
                            R.string.backup_copy_origin_newest,
                            data.originText,
                            stringResource(R.string.backup_most_recent),
                        )
                    } else {
                        data.originText
                    }
                Text(origin, style = MaterialTheme.typography.bodyMedium, color = muted)
                data.tags.forEach { StatusTag(it) }
            }
            data.recordsText?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = muted)
            }
        }
        HorizontalDivider(
            modifier = Modifier.padding(start = spacing.s16),
            thickness = AppTheme.sizes.hairline,
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

@ThemePreviews
@Composable
private fun BackupCopyRowPreview() {
    PreviewTheme {
        Column {
            BackupCopyRow(
                BackupCopyRowData(
                    whenText = PreviewSamples.SHORT_NAME,
                    sizeText = PreviewSamples.SETTING_VALUE,
                    originText = stringResource(R.string.backup_device_this),
                    mostRecent = true,
                    recordsText = null,
                    tags = emptyList(),
                ),
            )
            BackupCopyRow(
                BackupCopyRowData(
                    whenText = null,
                    sizeText = PreviewSamples.SETTING_VALUE,
                    originText = stringResource(R.string.backup_device_other),
                    mostRecent = false,
                    recordsText = PreviewSamples.LONG_NAME,
                    tags = listOf(
                        stringResource(R.string.backup_pinned_tag),
                        stringResource(R.string.backup_differences_tag),
                    ),
                ),
            )
        }
    }
}
