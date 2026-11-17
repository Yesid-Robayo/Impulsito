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
 * El micrófono de `Decir o escribir` en la barra de arriba de las pestañas y de la venta: así se
 * registra diciendo una frase desde cualquier parte, sin volver a Inicio. Va justo antes del
 * engranaje, que sigue último.
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
