package io.github.yesidrobayo.shopledger.ui.components

import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme

/**
 * Dónde va la navegación principal: la barra inferior en un teléfono, o el riel lateral desde una
 * ventana mediana. Lo decide la clase de ventana, nunca el modelo ni la orientación.
 */
enum class NavigationLayout {
    /** `AppBottomBar` sobre las cuatro raíces, con Vender sobresaliendo. */
    BAR,

    /** `AppNavigationRail` a la izquierda, visible también en los detalles. */
    RAIL,
}

/**
 * La navegación que la app está mostrando. Fuera de la navegación, como en una vista previa o en
 * una prueba de una sola pantalla, es la barra: así se ve un teléfono.
 */
val LocalNavigationLayout = staticCompositionLocalOf { NavigationLayout.BAR }

/** La navegación que pide la ventana: el riel desde 600 dp de ancho, la barra por debajo. */
@Composable
fun navigationLayoutForWindow(): NavigationLayout {
    val sizeClass = currentWindowAdaptiveInfo().windowSizeClass
    val medium = sizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)
    return if (medium) NavigationLayout.RAIL else NavigationLayout.BAR
}

/**
 * Lo que una raíz deja libre al final para la barra inferior y lo que sobresale Vender. Con el
 * riel no hay barra abajo, y la raíz usa todo el alto.
 */
@Composable
fun bottomBarClearance(): Dp = when (LocalNavigationLayout.current) {
    NavigationLayout.BAR -> AppTheme.sizes.bottomBar + AppTheme.sizes.sellButtonOverhang
    NavigationLayout.RAIL -> 0.dp
}
