package io.github.yesidrobayo.shopledger.ui.theme

import android.app.Activity
import android.content.res.Resources
import android.os.Build
import androidx.annotation.StyleRes
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.settings.ColorPalette

/**
 * La pantalla de inicio de cada paleta. La del manifiesto es la de la predeterminada, porque antes de
 * abrir la base no se sabe cuál eligió la persona.
 *
 * Desde Android 12 el sistema dibuja la pantalla de inicio antes de que corra la app, así que la
 * paleta elegida solo puede llegar al arranque siguiente: la app le pasa al sistema el tema de esa
 * paleta, y el sistema lo guarda. Con la predeterminada le pasa `ID_NULL`, que vuelve al del
 * manifiesto. En Android 8 a 11 no hay a quién pasarlo: el arranque queda con la predeterminada.
 */
object SplashThemes {
    /** El tema de arranque de [palette]. Sus nombres no cambian nunca: el sistema guarda el nombre. */
    @StyleRes
    fun of(palette: ColorPalette): Int = when (palette) {
        ColorPalette.INDIGO_MUSTARD -> R.style.Theme_App_Starting_IndigoMustard
        ColorPalette.BERRY_HONEY -> R.style.Theme_App_Starting_BerryHoney
        ColorPalette.RASPBERRY_GOLD -> R.style.Theme_App_Starting_RaspberryGold
        ColorPalette.WINE_CHAMPAGNE -> R.style.Theme_App_Starting_WineChampagne
        ColorPalette.PLUM_CORAL -> R.style.Theme_App_Starting_PlumCoral
        ColorPalette.FOREST_HONEY -> R.style.Theme_App_Starting_ForestHoney
        ColorPalette.CARMINE_GOLD -> R.style.Theme_App_Starting_CarmineGold
        ColorPalette.GARNET_HONEY -> R.style.Theme_App_Starting_GarnetHoney
        ColorPalette.VIVID_RED_CREAM -> R.style.Theme_App_Starting_VividRedCream
    }

    /**
     * Lo que se le pasa al sistema para [palette]: `ID_NULL` con la predeterminada, que es la del
     * manifiesto, y el tema de la paleta con las demás.
     */
    fun requestFor(palette: ColorPalette): Int =
        if (palette == DefaultColorPalette) Resources.ID_NULL else of(palette)

    /**
     * Deja [palette] para el arranque siguiente. Va cada vez que la base abre y al cambiar de paleta:
     * así una restauración también cambia el arranque. Antes de Android 12 no hace nada.
     */
    fun remember(activity: Activity, palette: ColorPalette) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        activity.splashScreen.setSplashScreenTheme(requestFor(palette))
    }
}
