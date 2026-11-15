package io.github.yesidrobayo.shopledger.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.res.stringResource
import io.github.yesidrobayo.shopledger.R

/**
 * Las marcas de trabajo sin guardar de la navegación. Fuera de ella, como en una prueba de una
 * sola pantalla o una vista previa, no hay registro y marcar no hace nada.
 */
val LocalUnsavedWork = staticCompositionLocalOf<UnsavedWork?> { null }

/**
 * Marca [key] con trabajo sin guardar mientras [unsaved], con la misma condición que enciende su
 * atrás de descartar, para que el riel pregunte con [texts] antes de quitarla de la pila.
 */
@Composable
fun MarkUnsavedWork(
    key: AppNavKey,
    unsaved: Boolean,
    texts: DiscardTexts = discardChangesTexts(),
) {
    val work = LocalUnsavedWork.current ?: return
    SideEffect { if (unsaved) work.mark(key, texts) else work.clear(key) }
}

/**
 * La pregunta de descartar de los formularios, `¿Descartar los cambios?`. [withExit] lleva
 * `Seguir editando`, como la mayoría; sin él, el diálogo usa su texto de siempre.
 */
@Composable
fun discardChangesTexts(withExit: Boolean = true): DiscardTexts = DiscardTexts(
    title = stringResource(R.string.dialog_discard_title),
    body = stringResource(R.string.dialog_discard_body),
    confirm = stringResource(R.string.dialog_discard_action),
    dismiss = if (withExit) stringResource(R.string.dialog_discard_exit) else null,
)
