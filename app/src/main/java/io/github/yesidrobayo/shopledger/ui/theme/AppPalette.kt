package io.github.yesidrobayo.shopledger.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Immutable
import io.github.yesidrobayo.shopledger.domain.model.settings.ColorPalette
import io.github.yesidrobayo.shopledger.ui.theme.palette.BerryHoneyPalette
import io.github.yesidrobayo.shopledger.ui.theme.palette.CarmineGoldPalette
import io.github.yesidrobayo.shopledger.ui.theme.palette.ForestHoneyPalette
import io.github.yesidrobayo.shopledger.ui.theme.palette.GarnetHoneyPalette
import io.github.yesidrobayo.shopledger.ui.theme.palette.IndigoMustardPalette
import io.github.yesidrobayo.shopledger.ui.theme.palette.PlumCoralPalette
import io.github.yesidrobayo.shopledger.ui.theme.palette.RaspberryGoldPalette
import io.github.yesidrobayo.shopledger.ui.theme.palette.VividRedCreamPalette
import io.github.yesidrobayo.shopledger.ui.theme.palette.WineChampagnePalette

/**
 * Una paleta completa: los roles de Material 3 y los colores propios, en claro y en oscuro.
 *
 * Cada esquema se arma con el constructor de [ColorScheme] y todos sus roles escritos, también los
 * fijos: un rol olvidado no compila, en vez de caer en el morado de Material.
 */
@Immutable
data class AppPalette(
    val lightScheme: ColorScheme,
    val darkScheme: ColorScheme,
    val lightColors: AppColors,
    val darkColors: AppColors,
)

/**
 * La paleta de cada valor de [ColorPalette]. El `when` no tiene rama por defecto: un valor nuevo del
 * enum no compila hasta tener su paleta completa.
 */
fun paletteOf(palette: ColorPalette): AppPalette = when (palette) {
    ColorPalette.INDIGO_MUSTARD -> IndigoMustardPalette
    ColorPalette.BERRY_HONEY -> BerryHoneyPalette
    ColorPalette.RASPBERRY_GOLD -> RaspberryGoldPalette
    ColorPalette.WINE_CHAMPAGNE -> WineChampagnePalette
    ColorPalette.PLUM_CORAL -> PlumCoralPalette
    ColorPalette.FOREST_HONEY -> ForestHoneyPalette
    ColorPalette.CARMINE_GOLD -> CarmineGoldPalette
    ColorPalette.GARNET_HONEY -> GarnetHoneyPalette
    ColorPalette.VIVID_RED_CREAM -> VividRedCreamPalette
}

/** Las nueve paletas, en el orden del enum, que es el del selector. */
val AppPalettes: Map<ColorPalette, AppPalette> = ColorPalette.entries.associateWith(::paletteOf)

/**
 * La paleta mientras la base no se ha leído, o si no abre: la persona todavía no puede haber
 * elegido otra que se vea.
 */
val DefaultColorPalette: ColorPalette = ColorPalette.INDIGO_MUSTARD
