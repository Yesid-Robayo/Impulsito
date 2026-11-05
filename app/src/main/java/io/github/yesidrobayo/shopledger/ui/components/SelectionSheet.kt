package io.github.yesidrobayo.shopledger.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

/**
 * Una hoja para elegir una categoría, un proveedor o un producto: búsqueda arriba, lista perezosa y,
 * al final, `Crear «…»` con lo escrito, si se permite crear.
 *
 * La lista que recibe ya viene filtrada por quien la usa, que compara sin mayúsculas ni tildes.
 *
 * @param onCreate crea un elemento con lo escrito, o `null` si aquí no se crea nada.
 */
@Composable
fun <T> SelectionSheet(
    title: String,
    items: List<T>,
    key: (T) -> Any,
    query: String,
    onQueryChange: (String) -> Unit,
    onDismissRequest: () -> Unit,
    searchHint: String,
    itemContent: @Composable (T) -> Unit,
    onCreate: ((String) -> Unit)? = null,
) {
    AppBottomSheet(onDismissRequest = onDismissRequest, title = title, scrollable = false) {
        SelectionSheetContent(items, key, query, onQueryChange, searchHint, itemContent, onCreate)
    }
}

/** El contenido de la hoja de selección, aparte para poder verlo sin abrirla. */
@Composable
fun <T> ColumnScope.SelectionSheetContent(
    items: List<T>,
    key: (T) -> Any,
    query: String,
    onQueryChange: (String) -> Unit,
    searchHint: String,
    itemContent: @Composable (T) -> Unit,
    onCreate: ((String) -> Unit)? = null,
) {
    SearchField(query, onQueryChange, searchHint)
    if (query.isNotBlank()) {
        // Lo lee TalkBack al cambiar; no ocupa lugar en pantalla más allá de una línea discreta.
        Text(
            text = pluralStringResource(R.plurals.a11y_search_results, items.size, items.size),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
    LazyColumn(Modifier.weight(1f, fill = false)) {
        items(items, key = key) { itemContent(it) }
        if (items.isEmpty() && query.isNotBlank()) {
            item(key = EMPTY_KEY) {
                EmptyStateInline(title = stringResource(R.string.empty_search_title, query.trim()))
            }
        }
        if (onCreate != null && query.isNotBlank()) {
            item(key = CREATE_KEY) { CreateRow(query.trim()) { onCreate(query.trim()) } }
        }
    }
}

/** `Crear «…»`: una fila de 56 dp con el icono de agregar, en el color de acción. */
@Composable
private fun CreateRow(text: String, onClick: () -> Unit) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = AppTheme.sizes.listRowSingle)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(vertical = AppTheme.spacing.s8),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_add),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(AppTheme.sizes.icon),
        )
        Text(
            text = stringResource(R.string.selection_create, text),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

private const val EMPTY_KEY = "selection-empty"
private const val CREATE_KEY = "selection-create"

@ThemePreviews
@Composable
private fun SelectionSheetPreview() {
    val s = PreviewSamples
    PreviewTheme {
        Column(Modifier.padding(AppTheme.spacing.s16)) {
            SelectionSheetContent(
                items = listOf(s.CATEGORY_A, s.CATEGORY_B),
                key = { it },
                query = s.CATEGORY_A.take(3),
                onQueryChange = {},
                searchHint = stringResource(R.string.sale_search_hint),
                itemContent = { SettingsRow(it, SettingsRowAction.Navigation {}) },
                onCreate = {},
            )
        }
    }
}
