package io.github.yesidrobayo.shopledger.ui.extras

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.catalog.CustomField
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductTag
import io.github.yesidrobayo.shopledger.ui.components.ActionVariant
import io.github.yesidrobayo.shopledger.ui.components.AppSnackbarHost
import io.github.yesidrobayo.shopledger.ui.components.AppTopBar
import io.github.yesidrobayo.shopledger.ui.components.DelayedSkeleton
import io.github.yesidrobayo.shopledger.ui.components.EmptyState
import io.github.yesidrobayo.shopledger.ui.components.EmptyStateInline
import io.github.yesidrobayo.shopledger.ui.components.InlineTextButton
import io.github.yesidrobayo.shopledger.ui.components.OverflowAction
import io.github.yesidrobayo.shopledger.ui.components.OverflowMenu
import io.github.yesidrobayo.shopledger.ui.components.PrimaryActionButton
import io.github.yesidrobayo.shopledger.ui.components.SectionHeader
import io.github.yesidrobayo.shopledger.ui.components.SegmentedTabs
import io.github.yesidrobayo.shopledger.ui.components.SkeletonBlock
import io.github.yesidrobayo.shopledger.ui.components.TonalFab
import io.github.yesidrobayo.shopledger.ui.components.TonalFabDefaults
import io.github.yesidrobayo.shopledger.ui.components.TopBarNavigation
import io.github.yesidrobayo.shopledger.ui.components.keepsMessagesAbove
import io.github.yesidrobayo.shopledger.ui.components.saveErrorText
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.unit.EXPANDED_WIDTH

/**
 * La pantalla de campos propios y etiquetas, sin ViewModel: dibuja [uiState] y la hoja abierta, y
 * avisa cada acción.
 *
 * En una ventana `Expanded` los dos segmentos van lado a lado, cada uno con su botón flotante; en
 * las demás, `Campos` y `Etiquetas` se eligen arriba, en una columna de lectura, y el botón cambia
 * con el segmento.
 */
@Composable
fun CustomFieldsScreen(
    uiState: CustomFieldsUiState,
    onAction: (CustomFieldsAction) -> Unit,
    modifier: Modifier = Modifier,
    editor: ExtraEditor? = null,
    onEditorAction: (ExtraEditorAction) -> Unit = {},
) {
    val snackbar = remember { SnackbarHostState() }
    BoxWithConstraints(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        val expanded = maxWidth >= EXPANDED_WIDTH
        Column(Modifier.fillMaxSize()) {
            AppTopBar(
                title = stringResource(R.string.field_title),
                navigation = TopBarNavigation.BACK,
                onNavigate = { onAction(CustomFieldsAction.Back) },
                navigationLabel = stringResource(R.string.a11y_back),
                titleStyle = MaterialTheme.typography.titleLarge,
            )
            when {
                uiState.loading -> LoadingRows()

                uiState.readFailed -> ReadFailed(onAction)

                expanded ->
                    Row(Modifier.fillMaxSize()) {
                        for (tab in CustomFieldsTab.entries) {
                            Pane(uiState, tab, onAction, Modifier.weight(1f), withHeader = true)
                        }
                    }

                else ->
                    Column(
                        Modifier
                            .align(Alignment.CenterHorizontally)
                            .widthIn(max = AppTheme.sizes.maxContentWidth)
                            .fillMaxWidth(),
                    ) {
                        SegmentedTabs(
                            options = CustomFieldsTab.entries.map { stringResource(tabLabel(it)) },
                            selectedIndex = CustomFieldsTab.entries.indexOf(uiState.tab),
                            onSelect = {
                                onAction(CustomFieldsAction.SelectTab(CustomFieldsTab.entries[it]))
                            },
                            modifier = Modifier.padding(
                                horizontal = AppTheme.spacing.s16,
                                vertical = AppTheme.spacing.s8,
                            ),
                        )
                        Pane(
                            uiState,
                            uiState.tab,
                            onAction,
                            Modifier.weight(1f),
                            withHeader = false,
                        )
                    }
            }
        }
        AppSnackbarHost(
            snackbar,
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = TonalFabDefaults.listEndPadding),
        )
    }
    editor?.let { ExtraEditorSheet(it, onEditorAction) }
    uiState.dialog?.let { CustomFieldsDialogView(it, onAction) }
    val message = uiState.message?.let { messageText(it) }
    LaunchedEffect(uiState.message) {
        if (message != null) {
            snackbar.showSnackbar(message)
            onAction(CustomFieldsAction.MessageShown)
        }
    }
}

/** Un segmento con su lista y su botón flotante. [withHeader] lo titula, lado a lado. */
@Composable
private fun Pane(
    uiState: CustomFieldsUiState,
    tab: CustomFieldsTab,
    onAction: (CustomFieldsAction) -> Unit,
    modifier: Modifier,
    withHeader: Boolean,
) {
    Box(modifier.fillMaxHeight()) {
        Column(Modifier.fillMaxSize()) {
            if (withHeader) SectionHeader(stringResource(tabLabel(tab)))
            when (tab) {
                CustomFieldsTab.FIELDS -> FieldsContent(uiState, onAction)
                CustomFieldsTab.TAGS -> TagsContent(uiState, onAction)
            }
        }
        val (text, action) =
            when (tab) {
                CustomFieldsTab.FIELDS -> R.string.field_new_action to CustomFieldsAction.NewField
                CustomFieldsTab.TAGS -> R.string.tag_new_action to CustomFieldsAction.NewTag
            }
        TonalFab(
            text = stringResource(text),
            onClick = { onAction(action) },
            modifier = Modifier.align(Alignment.BottomEnd).keepsMessagesAbove(),
        )
    }
}

@Composable
private fun LoadingRows() {
    DelayedSkeleton {
        Column(
            Modifier.padding(AppTheme.spacing.s16),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12),
        ) {
            repeat(SKELETON_ROWS) {
                SkeletonBlock(Modifier.fillMaxWidth().heightIn(min = AppTheme.sizes.listRowSingle))
            }
        }
    }
}

@Composable
private fun FieldsContent(uiState: CustomFieldsUiState, onAction: (CustomFieldsAction) -> Unit) {
    if (uiState.fields.isEmpty() && uiState.archived.isEmpty()) {
        EmptyState(
            icon = R.drawable.ic_fact_check,
            title = stringResource(R.string.field_empty_title),
            body = stringResource(R.string.field_empty_body),
        ) {
            PrimaryActionButton(
                text = stringResource(R.string.field_new_action),
                onClick = { onAction(CustomFieldsAction.NewField) },
                variant = ActionVariant.TONAL,
            )
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = TonalFabDefaults.listEndPadding),
    ) {
        if (uiState.fields.isEmpty()) {
            item(key = "fields-empty") {
                EmptyStateInline(
                    title = stringResource(R.string.field_empty_title),
                    body = stringResource(R.string.field_empty_body),
                    modifier = Modifier.padding(horizontal = AppTheme.spacing.s16),
                )
            }
        }
        val last = uiState.fields.lastIndex
        itemsIndexed(uiState.fields, key = { _, field -> "field-${field.id}" }) { index, field ->
            FieldRow(
                field = field,
                archived = false,
                canMoveUp = index > 0,
                canMoveDown = index < last,
                onAction = onAction,
            )
        }
        if (uiState.archived.isNotEmpty()) {
            item(key = "archived-header") {
                SectionHeader(stringResource(R.string.field_archived_group))
            }
            archivedRows(uiState.archived, onAction)
        }
    }
}

private fun LazyListScope.archivedRows(
    fields: List<CustomField>,
    onAction: (CustomFieldsAction) -> Unit,
) {
    items(fields, key = { "archived-${it.id}" }) { field ->
        FieldRow(field, archived = true, canMoveUp = false, canMoveDown = false, onAction)
    }
}

/**
 * Un campo: su nombre y, debajo, su tipo; a la derecha, cuántos productos tienen un valor en él.
 * Un solo foco, que se lee entero, con subir y bajar como acciones de TalkBack, y su menú aparte.
 * Tocar uno vigente abre su hoja; uno de un tipo que esta versión no conoce no se edita.
 */
@Composable
private fun FieldRow(
    field: CustomField,
    archived: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onAction: (CustomFieldsAction) -> Unit,
) {
    val type = field.type?.let { stringResource(fieldTypeLabel(it)) }
    val count =
        pluralStringResource(R.plurals.field_product_count, field.productCount, field.productCount)
    val upLabel = stringResource(R.string.field_move_up)
    val downLabel = stringResource(R.string.field_move_down)
    val editable = !archived && field.type != null
    val moves =
        buildList {
            if (canMoveUp) add(upLabel to CustomFieldsAction.MoveField(field.id, up = true))
            if (canMoveDown) add(downLabel to CustomFieldsAction.MoveField(field.id, up = false))
        }
    val menu =
        buildList {
            if (editable) {
                add(
                    OverflowAction(stringResource(R.string.action_edit), {
                        onAction(CustomFieldsAction.EditField(field.id))
                    }),
                )
            }
            moves.forEach { (label, move) -> add(OverflowAction(label, { onAction(move) })) }
            if (archived) {
                add(
                    OverflowAction(stringResource(R.string.action_reactivate), {
                        onAction(CustomFieldsAction.ReactivateField(field.id))
                    }),
                )
            } else {
                add(
                    OverflowAction(stringResource(R.string.action_archive), {
                        onAction(CustomFieldsAction.AskArchiveField(field.id))
                    }),
                )
            }
            if (field.productCount == 0) {
                add(
                    OverflowAction(
                        stringResource(R.string.action_delete),
                        { onAction(CustomFieldsAction.AskDeleteField(field.id)) },
                        destructive = true,
                    ),
                )
            }
        }
    ExtraRow(
        name = field.name,
        detail = type,
        count = count,
        onClick = if (editable) ({ onAction(CustomFieldsAction.EditField(field.id)) }) else null,
        accessibilityActions =
            moves.map { (label, move) ->
                CustomAccessibilityAction(label) {
                    onAction(move)
                    true
                }
            },
        menu = menu,
    )
}

@Composable
private fun TagsContent(uiState: CustomFieldsUiState, onAction: (CustomFieldsAction) -> Unit) {
    if (uiState.tags.isEmpty()) {
        EmptyState(icon = R.drawable.ic_sell, title = stringResource(R.string.tag_empty_title)) {
            PrimaryActionButton(
                text = stringResource(R.string.tag_new_action),
                onClick = { onAction(CustomFieldsAction.NewTag) },
                variant = ActionVariant.TONAL,
            )
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = TonalFabDefaults.listEndPadding),
    ) {
        items(uiState.tags, key = { "tag-${it.id}" }) { tag -> TagRow(tag, onAction) }
    }
}

/** Una etiqueta con cuántos productos la llevan. Tocarla abre su hoja para cambiarle el nombre. */
@Composable
private fun TagRow(tag: ProductTag, onAction: (CustomFieldsAction) -> Unit) {
    ExtraRow(
        name = tag.name,
        detail = null,
        count =
            pluralStringResource(
                R.plurals.field_product_count,
                tag.productCount,
                tag.productCount,
            ),
        onClick = { onAction(CustomFieldsAction.EditTag(tag.id)) },
        accessibilityActions = emptyList(),
        menu =
            listOf(
                OverflowAction(stringResource(R.string.action_edit), {
                    onAction(CustomFieldsAction.EditTag(tag.id))
                }),
                OverflowAction(
                    stringResource(R.string.action_delete),
                    { onAction(CustomFieldsAction.AskDeleteTag(tag.id)) },
                    destructive = true,
                ),
            ),
    )
}

/**
 * Una fila de la lista: el nombre, debajo [detail] si lo hay, y a la derecha [count]. La parte
 * tocable es un solo foco que se lee entero, y el menú va aparte, con su propio foco.
 */
@Composable
private fun ExtraRow(
    name: String,
    detail: String?,
    count: String,
    onClick: (() -> Unit)?,
    accessibilityActions: List<CustomAccessibilityAction>,
    menu: List<OverflowAction>,
) {
    val spacing = AppTheme.spacing
    val reading = listOfNotNull(name, detail, count).joinToString()
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = AppTheme.sizes.listRowSingle),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val touch = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
        Row(
            modifier =
                Modifier
                    .weight(1f)
                    .heightIn(min = AppTheme.sizes.listRowSingle)
                    .then(touch)
                    .clearAndSetSemantics {
                        contentDescription = reading
                        if (onClick != null) role = Role.Button
                        if (accessibilityActions.isNotEmpty()) customActions = accessibilityActions
                    }
                    .padding(start = spacing.s16, top = spacing.s8, bottom = spacing.s8),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.s12),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.s2)) {
                Text(
                    name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (detail != null) {
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                count,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OverflowMenu(menu, Modifier.padding(end = spacing.s4))
    }
}

private fun tabLabel(tab: CustomFieldsTab): Int = when (tab) {
    CustomFieldsTab.FIELDS -> R.string.field_tab_fields
    CustomFieldsTab.TAGS -> R.string.tag_tab_tags
}

@Composable
private fun messageText(message: CustomFieldsMessage): String = when (message) {
    CustomFieldsMessage.FieldSaved -> stringResource(R.string.field_saved)
    CustomFieldsMessage.TagSaved -> stringResource(R.string.tag_saved)
    CustomFieldsMessage.NameTaken -> stringResource(R.string.field_error_name_repeated)
    is CustomFieldsMessage.Failed -> stringResource(saveErrorText(message.outOfSpace))
}

private const val SKELETON_ROWS = 6

/** La lectura falló: se dice, y `Reintentar` vuelve a leer. Nada de lo guardado se tocó. */
@Composable
private fun ReadFailed(onAction: (CustomFieldsAction) -> Unit) {
    EmptyState(icon = R.drawable.ic_error, title = stringResource(R.string.state_error_read)) {
        InlineTextButton(
            stringResource(R.string.action_retry),
            { onAction(CustomFieldsAction.Retry) },
        )
    }
}
