package io.github.yesidrobayo.shopledger.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.res.stringResource
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

/**
 * Abre los ajustes. Lo provee la navegación, que los abre encima de la pestaña visible. Fuera de
 * ella, en vistas previas y pruebas de una pantalla suelta, no hace nada.
 */
val LocalSettingsOpener = staticCompositionLocalOf<() -> Unit> { {} }

/**
 * El engranaje de los ajustes en la barra de arriba de las cuatro pestañas. Va siempre último,
 * al borde derecho, después del menú si lo hay: así queda en el mismo punto en cada pestaña, el
 * que el pulgar ya conoce de Inicio.
 */
@Composable
fun SettingsButton() {
    val open = LocalSettingsOpener.current
    // El micrófono acompaña al engranaje en cada pestaña, justo antes de él.
    SayButton()
    TopBarAction(R.drawable.ic_settings, stringResource(R.string.a11y_settings), open)
}

@ThemePreviews
@Composable
private fun SettingsButtonPreview() {
    PreviewTheme { SettingsButton() }
}
