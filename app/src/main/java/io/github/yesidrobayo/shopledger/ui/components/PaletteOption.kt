package io.github.yesidrobayo.shopledger.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.settings.ColorPalette
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.DefaultColorPalette
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews
import io.github.yesidrobayo.shopledger.ui.theme.paletteOf

/** El nombre de cada paleta, en el idioma de la interfaz. */
@StringRes
fun paletteName(palette: ColorPalette): Int = when (palette) {
    ColorPalette.INDIGO_MUSTARD -> R.string.settings_palette_indigo_mustard
    ColorPalette.BERRY_HONEY -> R.string.settings_palette_berry_honey
    ColorPalette.RASPBERRY_GOLD -> R.string.settings_palette_raspberry_gold
    ColorPalette.WINE_CHAMPAGNE -> R.string.settings_palette_wine_champagne
    ColorPalette.PLUM_CORAL -> R.string.settings_palette_plum_coral
    ColorPalette.FOREST_HONEY -> R.string.settings_palette_forest_honey
    ColorPalette.CARMINE_GOLD -> R.string.settings_palette_carmine_gold
    ColorPalette.GARNET_HONEY -> R.string.settings_palette_garnet_honey
    ColorPalette.VIVID_RED_CREAM -> R.string.settings_palette_vivid_red_cream
}

/**
 * Una opción del selector de colores: la muestra de la paleta en claro y en oscuro, su nombre y, en
 * la predeterminada, la etiqueta `Predeterminada`. La elegida lleva un borde y el ícono `check`, así
 * que el estado no depende del color.
 *
 * Toda la fila es un solo foco, que TalkBack lee como botón de opción con el nombre, si es la
 * predeterminada y si está elegida. Las muestras no tienen texto: no se leen. Con la letra al
 * 200 %, la muestra pasa encima del nombre, para que el nombre no quede apretado.
 */
@Composable
fun PaletteOption(
    palette: ColorPalette,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = AppTheme.spacing
    val name = stringResource(paletteName(palette))
    val isDefault = palette == DefaultColorPalette
    val defaultLabel = stringResource(R.string.settings_palette_default)
    val shape = MaterialTheme.shapes.medium
    val border =
        if (selected) {
            Modifier.border(AppTheme.sizes.selectedBorder, MaterialTheme.colorScheme.primary, shape)
        } else {
            Modifier
        }
    val stacked = LocalDensity.current.fontScale >= STACKED_FONT_SCALE
    val content: @Composable () -> Unit = {
        PaletteSwatches(palette)
        Column(
            if (stacked) Modifier.padding(top = spacing.s8) else Modifier,
            verticalArrangement = Arrangement.spacedBy(spacing.s4),
        ) {
            Text(
                name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (isDefault) StatusTag(defaultLabel)
        }
    }
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = AppTheme.sizes.listRow)
                .clip(shape)
                .then(border)
                // Un solo foco: junta el nombre y `Predeterminada`, y dice si está elegida.
                .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
                .padding(horizontal = spacing.s12, vertical = spacing.s8),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.s12),
    ) {
        if (stacked) {
            Column(Modifier.weight(1f)) { content() }
        } else {
            Row(
                Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.s16),
            ) { content() }
        }
        if (selected) {
            Icon(
                painter = painterResource(R.drawable.ic_check),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(AppTheme.sizes.icon),
            )
        }
    }
}

/** La muestra de [palette] en claro y en oscuro, una junto a la otra. Decorativa: no se lee. */
@Composable
fun PaletteSwatches(palette: ColorPalette) {
    val set = paletteOf(palette)
    Row(horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s4)) {
        Swatch(
            hero = set.lightColors.heroContainer,
            accent = set.lightColors.heroAccent,
            surface = set.lightScheme.surface,
            brand = set.lightColors.brandFill,
        )
        Swatch(
            hero = set.darkColors.heroContainer,
            accent = set.darkColors.heroAccent,
            surface = set.darkScheme.surface,
            brand = set.darkColors.brandFill,
        )
    }
}

/**
 * Una muestra: el héroe arriba con un punto del acento, y una franja del papel abajo con un bloque
 * del relleno de marca. Sin cifras ni texto.
 */
@Composable
private fun Swatch(hero: Color, accent: Color, surface: Color, brand: Color) {
    val spacing = AppTheme.spacing
    val sizes = AppTheme.sizes
    Column(
        Modifier
            .size(sizes.paletteSwatchWidth, sizes.paletteSwatchHeight)
            .clip(MaterialTheme.shapes.small)
            .border(
                sizes.hairline,
                MaterialTheme.colorScheme.outlineVariant,
                MaterialTheme.shapes.small,
            ),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .weight(HERO_WEIGHT)
                .background(hero)
                .padding(spacing.s4),
        ) {
            Box(Modifier.size(spacing.s8).background(accent, CircleShape))
        }
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f - HERO_WEIGHT)
                .background(surface)
                .padding(horizontal = spacing.s4, vertical = spacing.s2),
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                Modifier
                    .size(width = spacing.s16, height = spacing.s4)
                    .background(brand, CircleShape),
            )
        }
    }
}

/** Desde esta escala de letra, la muestra va encima del nombre. */
private const val STACKED_FONT_SCALE = 2f

/** La parte de la muestra que ocupa el héroe. */
private const val HERO_WEIGHT = 0.6f

@ThemePreviews
@Composable
private fun PaletteOptionPreview() {
    PreviewTheme {
        Column {
            for (palette in ColorPalette.entries) {
                PaletteOption(palette, selected = palette == ColorPalette.FOREST_HONEY, onSelect = {
                })
            }
        }
    }
}
