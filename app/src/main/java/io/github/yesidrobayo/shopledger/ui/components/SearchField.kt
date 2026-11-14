package io.github.yesidrobayo.shopledger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

/**
 * La búsqueda: 56 dp, el papel de tarjeta con borde, y el foco en un borde de 2 dp del color de
 * acción. Filtra en cada letra, sin botón de buscar; quien la usa compara sin mayúsculas ni
 * tildes.
 *
 * Va bajo la barra superior en las listas, o sobre el panel de la venta, al alcance del pulgar: el
 * componente es el mismo. Un lector de códigos externo escribe aquí como un teclado y termina con
 * Enter, que llega a [onSubmit]: el Enter de un teclado físico o de un lector vale lo mismo que la
 * tecla de buscar del teclado en pantalla, y nunca escribe un salto de línea.
 *
 * @param onScan abre el escáner, o `null` si ningún producto tiene código: entonces no hay icono.
 */
@Composable
fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    onSubmit: () -> Unit = {},
    onScan: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val sizes = AppTheme.sizes
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val shape = MaterialTheme.shapes.medium
    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = sizes.field)
                .background(AppTheme.colors.cardSurface, shape)
                .border(
                    width = if (focused) sizes.focusBorder else sizes.hairline,
                    color = if (focused) scheme.primary else scheme.outline,
                    shape = shape,
                ).onPreviewKeyEvent { event ->
                    if (!event.isEnter()) return@onPreviewKeyEvent false
                    // Se toma al bajar la tecla, y la subida se consume para no repetirlo.
                    if (event.type == KeyEventType.KeyDown) onSubmit()
                    true
                },
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface),
        singleLine = true,
        cursorBrush = SolidColor(scheme.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
        interactionSource = interactions,
        decorationBox = { field ->
            Row(
                modifier = Modifier.padding(start = AppTheme.spacing.s12),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_search),
                    contentDescription = null,
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier.size(sizes.icon),
                )
                Box(
                    modifier = Modifier.weight(
                        1f,
                    ).padding(horizontal = AppTheme.spacing.s12, vertical = AppTheme.spacing.s12),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (query.isEmpty()) {
                        Text(
                            text = placeholder,
                            style = MaterialTheme.typography.bodyLarge,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                    field()
                }
                if (query.isNotEmpty()) {
                    TopBarAction(R.drawable.ic_close, stringResource(R.string.a11y_clear_search)) {
                        onQueryChange("")
                    }
                }
                if (onScan != null) {
                    TopBarAction(
                        R.drawable.ic_barcode_scanner,
                        stringResource(R.string.a11y_scan_code),
                        onScan,
                    )
                }
            }
        },
    )
}

/** El Enter de un teclado físico o de un lector de códigos, también el del teclado numérico. */
private fun KeyEvent.isEnter(): Boolean = key == Key.Enter || key == Key.NumPadEnter

@ThemePreviews
@Composable
private fun SearchFieldPreview() {
    PreviewTheme {
        Column(
            Modifier.padding(AppTheme.spacing.s16),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12),
        ) {
            SearchField("", {}, stringResource(R.string.sale_search_hint), onScan = {})
            SearchField(PreviewSamples.SEARCH_QUERY, {}, stringResource(R.string.sale_search_hint))
            EmptyStateInline(
                title = stringResource(R.string.empty_search_title, PreviewSamples.SEARCH_QUERY),
            )
        }
    }
}
