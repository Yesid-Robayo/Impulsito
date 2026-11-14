package io.github.yesidrobayo.shopledger.ui.components

import androidx.annotation.DrawableRes
import androidx.annotation.PluralsRes
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
 * @param pinned unos pocos elementos que van arriba, bajo su propio título, mientras no se busca
 *   nada: los recientes, por ejemplo. La lista completa sigue debajo, con [itemsTitle].
 * @param footer una acción fija bajo la lista, siempre a la vista aunque la lista sea larga.
 * @param onScan abre el escáner desde la búsqueda, o `null` si no se ofrece.
 * @param resultsCount el conteo que se lee al buscar, con el sustantivo de lo que se elige:
 *   productos, monedas o, por defecto, resultados. Nunca el de otra lista.
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
    pinned: SelectionPinned<T>? = null,
    footer: SelectionFooter? = null,
    onScan: (() -> Unit)? = null,
    @PluralsRes resultsCount: Int = R.plurals.a11y_search_results_any,
) {
    AppBottomSheet(onDismissRequest = onDismissRequest, title = title, scrollable = false) {
        SelectionSheetContent(
            items,
            key,
            query,
            onQueryChange,
            searchHint,
            itemContent,
            onCreate,
            pinned,
            footer,
            onScan,
            resultsCount,
        )
    }
}

/**
 * Los elementos que una hoja de selección pone arriba, bajo [title], mientras no se busca nada. La
 * lista completa sigue debajo con [itemsTitle], y puede repetirlos: así conserva su orden.
 */
data class SelectionPinned<T>(val title: String, val items: List<T>, val itemsTitle: String)

/** Una acción fija bajo la lista de una hoja de selección, con su icono. */
data class SelectionFooter(
    val text: String,
    @param:DrawableRes val icon: Int,
    val onClick: () -> Unit,
)

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
    pinned: SelectionPinned<T>? = null,
    footer: SelectionFooter? = null,
    onScan: (() -> Unit)? = null,
    @PluralsRes resultsCount: Int = R.plurals.a11y_search_results_any,
) {
    SearchField(query, onQueryChange, searchHint, onScan = onScan)
    if (query.isNotBlank()) {
        // Lo lee TalkBack al cambiar; no ocupa lugar en pantalla más allá de una línea discreta.
        Text(
            text = pluralStringResource(resultsCount, items.size, items.size),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
    // Los fijados solo se ven sin búsqueda: al buscar, cada elemento aparece una sola vez.
    val shownPinned = pinned?.takeIf { query.isBlank() && it.items.isNotEmpty() }
    LazyColumn(Modifier.weight(1f, fill = false)) {
        if (shownPinned != null) {
            item(key = PINNED_TITLE_KEY) { SectionHeader(shownPinned.title) }
            items(shownPinned.items, key = { "$PINNED_PREFIX${key(it)}" }) { itemContent(it) }
            if (items.isNotEmpty()) {
                item(key = ITEMS_TITLE_KEY) { SectionHeader(shownPinned.itemsTitle) }
            }
        }
        items(items, key = key) { itemContent(it) }
        if (items.isEmpty() && query.isNotBlank()) {
            item(key = EMPTY_KEY) {
                EmptyStateInline(title = stringResource(R.string.empty_search_title, query.trim()))
            }
        }
        if (onCreate != null && query.isNotBlank()) {
            item(key = CREATE_KEY) {
                ActionRow(
                    stringResource(R.string.selection_create, query.trim()),
                    R.drawable.ic_add,
                ) {
                    onCreate(query.trim())
                }
            }
        }
    }
    footer?.let { ActionRow(it.text, it.icon, it.onClick) }
}

/**
 * `Crear «…»` o la acción fija de la hoja: una fila de 56 dp con su icono, en el color de acción.
 */
@Composable
private fun ActionRow(text: String, @DrawableRes icon: Int, onClick: () -> Unit) {
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
            painter = painterResource(icon),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(AppTheme.sizes.icon),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

private const val EMPTY_KEY = "selection-empty"
private const val CREATE_KEY = "selection-create"
private const val PINNED_TITLE_KEY = "selection-pinned-title"
private const val ITEMS_TITLE_KEY = "selection-items-title"

/**
 * El prefijo de la clave de un elemento fijado: la lista completa puede repetirlo, y cada clave de
 * la lista debe ser única y caber en el estado guardado, así que va como texto.
 */
private const val PINNED_PREFIX = "selection-pinned-"

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
