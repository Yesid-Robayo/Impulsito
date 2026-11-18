package io.github.yesidrobayo.shopledger.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.res.stringResource
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

/**
 * Abre `Decir o escribir`. Lo provee la navegación, que la abre encima de la pestaña visible. Fuera
 * de ella, en vistas previas y pruebas de una pantalla suelta, es `null` y el botón no aparece.
 */
val LocalSayOpener = staticCompositionLocalOf<(() -> Unit)?> { null }

/**
 * El micrófono de `Decir o escribir` en la barra de arriba de la venta a mano, donde la barra
 * inferior y su botón redondo `Decir` no se ven: así se registra diciendo una frase sin salir de la
 * venta. En las pestañas, `Decir` es el botón redondo [SayFloatingButton].
 */
@Composable
fun SayButton() {
    val open = LocalSayOpener.current ?: return
    TopBarAction(R.drawable.ic_mic, stringResource(R.string.phrase_title), open)
}

@ThemePreviews
@Composable
private fun SayButtonPreview() {
    PreviewTheme { SayButton() }
}
