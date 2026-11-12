package io.github.yesidrobayo.shopledger.ui.components

import android.view.inputmethod.EditorInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.NativeClipboard
import androidx.compose.ui.platform.PlatformTextInputMethodRequest

/**
 * Lo que se escribe dentro no sale del campo: ni al portapapeles ni al aprendizaje del teclado.
 *
 * - **Sin copiar ni cortar.** Los campos de adentro reciben un portapapeles que lee, para poder
 *   pegar desde un gestor de contraseñas, pero nunca escribe: `Copiar` y `Cortar` no hacen nada.
 * - **Sin aprendizaje del teclado.** El teclado recibe `IME_FLAG_NO_PERSONALIZED_LEARNING`: no
 *   guarda lo escrito para sugerirlo después. Es una pista que el teclado puede ignorar; por eso los
 *   campos van además con teclado de contraseña y sin sugerencias.
 *
 * Es para el código de recuperación y la contraseña del servicio, nunca para datos del negocio.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PrivateInput(content: @Composable () -> Unit) {
    val clipboard = LocalClipboard.current
    val readOnly = remember(clipboard) { ReadOnlyClipboard(clipboard) }
    CompositionLocalProvider(LocalClipboard provides readOnly) {
        InterceptPlatformTextInput(
            interceptor = { request, nextHandler ->
                val private =
                    PlatformTextInputMethodRequest { outAttributes ->
                        request.createInputConnection(outAttributes).also {
                            outAttributes.imeOptions =
                                outAttributes.imeOptions or
                                EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                        }
                    }
                nextHandler.startInputMethod(private)
            },
        ) { content() }
    }
}

/** Un portapapeles que deja pegar y nunca guarda nada. */
private class ReadOnlyClipboard(private val system: Clipboard) : Clipboard {
    override val nativeClipboard: NativeClipboard get() = system.nativeClipboard

    override suspend fun getClipEntry(): ClipEntry? = system.getClipEntry()

    // Copiar o cortar desde un campo privado no deja nada en el portapapeles del teléfono.
    override suspend fun setClipEntry(clipEntry: ClipEntry?) = Unit
}
