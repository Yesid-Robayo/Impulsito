package io.github.yesidrobayo.shopledger.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.res.stringResource
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.ui.format.LocalDiscreetMode
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

/**
 * Enciende o apaga el modo discreto. Lo provee la actividad principal: guarda la preferencia y, con
 * el bloqueo encendido, pide la verificación antes de mostrar de nuevo las cifras. Fuera de ella, en
 * vistas previas y pruebas de una pantalla suelta, no hace nada.
 */
val LocalDiscreetModeSwitch = staticCompositionLocalOf<(Boolean) -> Unit> { {} }

/**
 * El icono del modo discreto en la barra de arriba de Inicio y de Estadísticas. Con las cifras a la
 * vista muestra `visibility_off` y las oculta; con el modo encendido muestra `visibility` y las
 * vuelve a mostrar. TalkBack dice la acción, no el estado.
 */
@Composable
fun DiscreetModeButton() {
    val discreet = LocalDiscreetMode.current
    val switch = LocalDiscreetModeSwitch.current
    if (discreet) {
        TopBarAction(R.drawable.ic_visibility, stringResource(R.string.a11y_discreet_show)) {
            switch(false)
        }
    } else {
        TopBarAction(R.drawable.ic_visibility_off, stringResource(R.string.a11y_discreet_hide)) {
            switch(true)
        }
    }
}

@ThemePreviews
@Composable
private fun DiscreetModeButtonPreview() {
    PreviewTheme { DiscreetModeButton() }
}

@ThemePreviews
@Composable
private fun DiscreetModeButtonOnPreview() {
    PreviewTheme(discreet = true) { DiscreetModeButton() }
}
