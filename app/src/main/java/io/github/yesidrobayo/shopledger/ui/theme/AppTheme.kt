package io.github.yesidrobayo.shopledger.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import io.github.yesidrobayo.shopledger.domain.model.settings.ColorPalette

/**
 * El tema de la app: la paleta que eligió la persona, en claro o en oscuro, con Figtree y las formas
 * propias.
 *
 * Sin color dinámico de Android: tomaría los colores del fondo de pantalla y borraría la marca. La
 * paleta y el modo son independientes, y cambiar cualquiera de los dos recompone al instante, sin
 * recrear la actividad y sin transición de color.
 */
@Composable
fun AppTheme(palette: ColorPalette, darkTheme: Boolean, content: @Composable () -> Unit) {
    val set = paletteOf(palette)
    CompositionLocalProvider(
        LocalAppColors provides if (darkTheme) set.darkColors else set.lightColors,
        LocalColorPalette provides palette,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) set.darkScheme else set.lightScheme,
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}

/** Los tokens propios de la app. Los de Material se leen de `MaterialTheme`. */
object AppTheme {
    /** Los colores propios de la paleta vigente. */
    val colors: AppColors
        @Composable @ReadOnlyComposable
        get() = LocalAppColors.current

    /** La paleta vigente, como la marca el selector de colores. */
    val palette: ColorPalette
        @Composable @ReadOnlyComposable
        get() = LocalColorPalette.current

    /** La escala de espaciado. */
    val spacing: AppSpacing = AppSpacing()

    /** Las medidas fijas del sistema de diseño. */
    val sizes: AppSizes = AppSizes()

    /** Los estilos de montos y cantidades, todos con cifras tabulares. */
    val amounts: AppAmounts = AppAmounts()
}

/** La paleta que dibuja el tema. Fuera de [AppTheme], la predeterminada. */
private val LocalColorPalette = staticCompositionLocalOf { DefaultColorPalette }
