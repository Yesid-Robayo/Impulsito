package io.github.yesidrobayo.shopledger.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.settings.ColorPalette
import io.github.yesidrobayo.shopledger.ui.components.AppBottomSheet
import io.github.yesidrobayo.shopledger.ui.components.NoticeCard
import io.github.yesidrobayo.shopledger.ui.components.NoticeVariant
import io.github.yesidrobayo.shopledger.ui.components.PaletteOption
import io.github.yesidrobayo.shopledger.ui.components.PaletteSwatches
import io.github.yesidrobayo.shopledger.ui.components.paletteName
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

/**
 * La fila `Colores` de `Apariencia`: el nombre de la paleta en pantalla y su muestra, que abre la
 * hoja de colores. Toda la fila es un solo foco.
 */
@Composable
fun PaletteRow(palette: ColorPalette, onClick: () -> Unit) {
    val spacing = AppTheme.spacing
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = AppTheme.sizes.listRow)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = spacing.s16, vertical = spacing.s8),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.s16),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.s2)) {
            Text(
                stringResource(R.string.settings_palette_title),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                stringResource(paletteName(palette)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        PaletteSwatches(palette)
        Icon(
            painter = painterResource(R.drawable.ic_chevron_right),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(AppTheme.sizes.icon),
        )
    }
}

/**
 * `Colores`: las nueve paletas en su orden, con la de pantalla marcada y a la vista al abrir. Tocar
 * una la aplica a toda la app al instante, también a esta hoja, y la hoja queda abierta para
 * comparar. Atrás o deslizar la cierran. No cambia el tema.
 *
 * Si la última elegida no se pudo guardar, vuelve la anterior y la hoja lo dice arriba, con
 * `Reintentar`: el aviso va dentro de la hoja, que tapa los mensajes de la pantalla.
 *
 * @param selected la paleta en pantalla.
 * @param failed la última elegida no se guardó.
 */
@Composable
fun PaletteSheet(
    selected: ColorPalette,
    onSelect: (ColorPalette) -> Unit,
    onDismiss: () -> Unit,
    failed: Boolean = false,
    onRetry: () -> Unit = {},
) {
    AppBottomSheet(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.settings_palette_title),
    ) {
        PaletteSheetContent(selected, onSelect, failed, onRetry)
    }
}

/** Lo de adentro de la hoja de colores, aparte para verlo sin abrirla. */
@Composable
fun PaletteSheetContent(
    selected: ColorPalette,
    onSelect: (ColorPalette) -> Unit,
    failed: Boolean = false,
    onRetry: () -> Unit = {},
) {
    // La elegida queda a la vista al abrir, también con letra grande.
    val chosen = remember { BringIntoViewRequester() }
    LaunchedEffect(Unit) { chosen.bringIntoView() }
    Text(
        stringResource(R.string.settings_palette_help),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (failed) {
        NoticeCard(
            text = stringResource(R.string.settings_palette_error),
            variant = NoticeVariant.ERROR,
            actionLabel = stringResource(R.string.action_retry),
            onAction = onRetry,
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s4)) {
        for (palette in ColorPalette.entries) {
            val isSelected = palette == selected
            PaletteOption(
                palette = palette,
                selected = isSelected,
                onSelect = { onSelect(palette) },
                modifier = if (isSelected) Modifier.bringIntoViewRequester(chosen) else Modifier,
            )
        }
    }
}

@ThemePreviews
@Composable
private fun PaletteSheetPreview() {
    PreviewTheme {
        Column(Modifier.padding(AppTheme.spacing.s16)) {
            PaletteRow(ColorPalette.GARNET_HONEY) {}
            PaletteSheetContent(ColorPalette.GARNET_HONEY, {}, failed = true)
        }
    }
}
