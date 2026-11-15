package io.github.yesidrobayo.shopledger.widget

import androidx.compose.ui.graphics.Color
import androidx.glance.color.ColorProvider
import androidx.glance.unit.ColorProvider
import io.github.yesidrobayo.shopledger.domain.model.settings.ColorPalette
import io.github.yesidrobayo.shopledger.ui.theme.paletteOf

/**
 * Los colores del widget, de la paleta elegida, cada uno con su par claro y oscuro: el widget sigue
 * el modo del sistema, porque el modo elegido en la app vive en la base. Desde Android 12 el
 * lanzador elige el par; antes, Glance lo elige con el modo noche del sistema al dibujar.
 *
 * Son los mismos tokens de la app, con los contrastes medidos de la guía de accesibilidad: papel
 * `surfaceContainerLow`, filete `cardBorder` y `Vender` en `brandFill` con `onBrandFill`.
 */
internal class WidgetColors(palette: ColorPalette) {
    private val colors = paletteOf(palette)

    val surface: ColorProvider =
        pair(colors.lightScheme.surfaceContainerLow, colors.darkScheme.surfaceContainerLow)
    val border: ColorProvider = pair(colors.lightColors.cardBorder, colors.darkColors.cardBorder)
    val text: ColorProvider = pair(colors.lightScheme.onSurface, colors.darkScheme.onSurface)
    val secondaryText: ColorProvider =
        pair(colors.lightScheme.onSurfaceVariant, colors.darkScheme.onSurfaceVariant)
    val brand: ColorProvider = pair(colors.lightColors.brandFill, colors.darkColors.brandFill)
    val onBrand: ColorProvider =
        pair(colors.lightColors.onBrandFill, colors.darkColors.onBrandFill)
}

private fun pair(light: Color, dark: Color): ColorProvider =
    ColorProvider(day = light, night = dark)
