package io.github.yesidrobayo.shopledger.ui.theme

import io.github.yesidrobayo.shopledger.domain.model.settings.ColorPalette
import io.github.yesidrobayo.shopledger.domain.model.settings.ThemeMode

/** Cómo se ve la app: la paleta y el modo claro u oscuro, que son independientes. */
data class Appearance(val palette: ColorPalette, val themeMode: ThemeMode)

/**
 * Si la app va en oscuro. Sin ajustes leídos, sigue al teléfono, igual que con [ThemeMode.SYSTEM]:
 * el modo elegido vive en la base, y antes de leerla no se puede saber.
 */
fun Appearance?.isDark(systemDark: Boolean): Boolean = when (this?.themeMode) {
    null, ThemeMode.SYSTEM -> systemDark
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}
