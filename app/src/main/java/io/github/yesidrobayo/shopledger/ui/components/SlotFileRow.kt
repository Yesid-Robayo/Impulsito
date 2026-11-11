package io.github.yesidrobayo.shopledger.ui.components

import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

/**
 * Un archivo de las ranuras al prepararlas: icono de documento, `Archivo 1 de 3`, el nombre del
 * archivo, que no se traduce, y su estado con icono y texto.
 *
 * - `check_circle` va en el color secundario del texto, nunca en el verde del respaldo: dice que el
 *   archivo existe, no que tenga una copia. El verde se reserva para una copia confirmada.
 * - **El archivo que sigue lleva un fondo tonal suave**, la misma superficie secundaria de la paleta:
 *   se distingue con el pulgar sin usar el primario ni el acento, que quedan para el botón de abajo.
 *   El estado también lo dice en texto, así que nunca depende solo del color.
 * - Con letra grande, el nombre baja a su propia línea, a todo el ancho, y el estado va debajo.
 *
 * Es un solo foco para TalkBack.
 *
 * @param title `Archivo n de 3`.
 * @param fileName el nombre que dio la nube, o el propuesto si todavía no hay archivo.
 * @param statusText `Creado`, `Listo` o `Falta crear`.
 * @param exists si el archivo ya existe.
 * @param next si es el que sigue.
 */
@Composable
fun SlotFileRow(
    title: String,
    fileName: String,
    statusText: String,
    exists: Boolean,
    next: Boolean,
    modifier: Modifier = Modifier,
) {
    val spacing = AppTheme.spacing
    val colors = MaterialTheme.colorScheme
    val content = if (next) colors.onSecondaryContainer else colors.onSurface
    val muted = if (next) colors.onSecondaryContainer else colors.onSurfaceVariant
    val stacked = LocalDensity.current.fontScale >= STACKED_FONT_SCALE
    val container = if (next) colors.secondaryContainer else Color.Transparent
    val rowModifier =
        modifier
            .fillMaxWidth()
            .heightIn(min = AppTheme.sizes.touchTarget)
            .background(container, MaterialTheme.shapes.large)
            .semantics(mergeDescendants = true) {}
            .padding(spacing.s12)
    if (stacked) {
        // Con letra grande el nombre usa todo el ancho, sin la sangría del icono: así no se parte.
        Column(rowModifier, verticalArrangement = Arrangement.spacedBy(spacing.s2)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.s12),
            ) {
                DraftIcon(content)
                Text(title, style = MaterialTheme.typography.titleSmall, color = content)
            }
            Text(fileName, style = MaterialTheme.typography.bodySmall, color = muted)
            SlotFileStatus(statusText, exists, muted)
        }
        return
    }
    Row(rowModifier, horizontalArrangement = Arrangement.spacedBy(spacing.s12)) {
        DraftIcon(content)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.s2)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = content)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.s8),
            ) {
                Text(
                    fileName,
                    style = MaterialTheme.typography.bodySmall,
                    color = muted,
                    modifier = Modifier.weight(1f),
                )
                SlotFileStatus(statusText, exists, muted)
            }
        }
    }
}

@Composable
private fun DraftIcon(tint: Color) {
    Icon(
        painter = painterResource(R.drawable.ic_draft),
        contentDescription = null,
        tint = tint,
        modifier = Modifier.size(AppTheme.sizes.icon),
    )
}

@Composable
private fun SlotFileStatus(text: String, exists: Boolean, color: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s4),
    ) {
        Icon(
            painter = painterResource(if (exists) R.drawable.ic_check_circle else MISSING_ICON),
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(AppTheme.sizes.iconInline),
        )
        Text(text, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

/** Un círculo vacío: el archivo todavía no existe. */
private val MISSING_ICON = R.drawable.ic_radio_button_unchecked

/** Desde esta escala de letra, el estado va en su propia línea: al lado no cabe sin cortar. */
private const val STACKED_FONT_SCALE = 1.3f

@ThemePreviews
@Composable
private fun SlotFileRowPreview() {
    PreviewTheme {
        Column(Modifier.padding(AppTheme.spacing.s16)) {
            SlotFileRow(
                title = stringResource(R.string.slots_file_row, 1, 3),
                fileName = "shopledger-backup-a.slcopy",
                statusText = stringResource(R.string.slots_file_created),
                exists = true,
                next = false,
            )
            SlotFileRow(
                title = stringResource(R.string.slots_file_row, 2, 3),
                fileName = PreviewSamples.LONG_NAME,
                statusText = stringResource(R.string.slots_file_pending),
                exists = false,
                next = true,
            )
            SlotFileRow(
                title = stringResource(R.string.slots_file_row, 3, 3),
                fileName = "shopledger-backup-c.slcopy",
                statusText = stringResource(R.string.slots_file_pending),
                exists = false,
                next = false,
            )
        }
    }
}
