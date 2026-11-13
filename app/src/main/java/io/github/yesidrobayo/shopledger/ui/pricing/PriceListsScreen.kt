package io.github.yesidrobayo.shopledger.ui.pricing

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.dp
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceListPricing
import io.github.yesidrobayo.shopledger.domain.rule.pricing.ListPriceOrigin
import io.github.yesidrobayo.shopledger.ui.components.AppCard
import io.github.yesidrobayo.shopledger.ui.components.AppSnackbarHost
import io.github.yesidrobayo.shopledger.ui.components.AppTopBar
import io.github.yesidrobayo.shopledger.ui.components.ConfirmDialog
import io.github.yesidrobayo.shopledger.ui.components.DelayedSkeleton
import io.github.yesidrobayo.shopledger.ui.components.EmptyStateInline
import io.github.yesidrobayo.shopledger.ui.components.InlineTextButton
import io.github.yesidrobayo.shopledger.ui.components.OverflowAction
import io.github.yesidrobayo.shopledger.ui.components.OverflowMenu
import io.github.yesidrobayo.shopledger.ui.components.ProductRow
import io.github.yesidrobayo.shopledger.ui.components.ProductRowData
import io.github.yesidrobayo.shopledger.ui.components.ProductRowTrailing
import io.github.yesidrobayo.shopledger.ui.components.SearchField
import io.github.yesidrobayo.shopledger.ui.components.SectionHeader
import io.github.yesidrobayo.shopledger.ui.components.SkeletonBlock
import io.github.yesidrobayo.shopledger.ui.components.TonalFab
import io.github.yesidrobayo.shopledger.ui.components.TonalFabDefaults
import io.github.yesidrobayo.shopledger.ui.components.TopBarNavigation
import io.github.yesidrobayo.shopledger.ui.components.keepsMessagesAbove
import io.github.yesidrobayo.shopledger.ui.components.saveErrorText
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme

/** Desde este ancho la ventana es `Expanded`: listas a la izquierda y detalle a la derecha. */
internal val PRICE_LISTS_EXPANDED_WIDTH = 840.dp

/**
 * La pantalla de listas de precios, sin ViewModel: dibuja [uiState] y las hojas abiertas, y avisa
 * cada acción.
 *
 * En una ventana `Expanded` las listas van a la izquierda y el detalle de la elegida a la derecha;
 * en las demás, una sola columna de lectura, y el detalle reemplaza a la lista hasta volver.
 */
@Composable
fun PriceListsScreen(
    uiState: PriceListsUiState,
    onAction: (PriceListsAction) -> Unit,
    modifier: Modifier = Modifier,
    listEditor: PriceListEditor? = null,
    onListEditorAction: (PriceListEditorAction) -> Unit = {},
    priceEditor: ListPriceEditor? = null,
    onPriceEditorAction: (ListPriceEditorAction) -> Unit = {},
) {
    val snackbar = remember { SnackbarHostState() }
    BoxWithConstraints(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        val expanded = maxWidth >= PRICE_LISTS_EXPANDED_WIDTH
        val selected = uiState.selected
        if (expanded) {
            Row(Modifier.fillMaxSize()) {
                Lists(uiState, onAction, Modifier.width(AppTheme.sizes.sideSheet).fillMaxHeight())
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    if (selected != null) Detail(uiState, selected, onAction, closable = false)
                }
            }
        } else if (selected != null) {
            Detail(uiState, selected, onAction, closable = true)
        } else {
            Lists(uiState, onAction, Modifier.fillMaxSize())
        }
        AppSnackbarHost(
            snackbar,
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = TonalFabDefaults.listEndPadding),
        )
    }
    listEditor?.let { PriceListEditorSheet(it, onListEditorAction) }
    priceEditor?.let { ListPriceEditorSheet(it, onPriceEditorAction) }
    uiState.dialog?.let { PriceListDialog(it, onAction) }
    val message = uiState.message?.let { messageText(it) }
    LaunchedEffect(uiState.message) {
        if (message != null) {
            snackbar.showSnackbar(message)
            onAction(PriceListsAction.MessageShown)
        }
    }
}

@Composable
private fun Lists(
    uiState: PriceListsUiState,
    onAction: (PriceListsAction) -> Unit,
    modifier: Modifier,
) {
    Box(modifier) {
        Column(Modifier.fillMaxSize()) {
            AppTopBar(
                title = stringResource(R.string.price_list_title),
                navigation = TopBarNavigation.BACK,
                onNavigate = { onAction(PriceListsAction.Back) },
                navigationLabel = stringResource(R.string.a11y_back),
                titleStyle = MaterialTheme.typography.titleLarge,
            )
            Column(
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .widthIn(max = AppTheme.sizes.maxContentWidth)
                    .fillMaxWidth(),
            ) {
                when {
                    uiState.loading -> LoadingRows(LIST_SKELETON_ROWS)
                    uiState.readFailed -> ReadFailed(onAction)
                    else -> ListsContent(uiState, onAction)
                }
            }
        }
        if (!uiState.loading && !uiState.readFailed) {
            TonalFab(
                text = stringResource(R.string.price_list_new_action),
                onClick = { onAction(PriceListsAction.NewList) },
                modifier = Modifier.align(Alignment.BottomEnd).keepsMessagesAbove(),
            )
        }
    }
}

@Composable
private fun ListsContent(uiState: PriceListsUiState, onAction: (PriceListsAction) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = TonalFabDefaults.listEndPadding),
    ) {
        items(uiState.active, key = { "list-${it.list.id}" }) { summary ->
            ListRow(
                summary,
                selected = summary.list.id == uiState.selectedId,
                onClick = { onAction(PriceListsAction.Open(summary.list.id)) },
            )
        }
        if (uiState.onlyDefault) {
            item(key = "empty") {
                EmptyStateInline(
                    title = stringResource(R.string.price_list_empty_title),
                    modifier = Modifier.padding(AppTheme.spacing.s16),
                )
            }
        }
        if (uiState.archived.isNotEmpty()) {
            item(key = "archived-header") {
                SectionHeader(stringResource(R.string.price_list_archived_group))
            }
            items(uiState.archived, key = { "archived-${it.list.id}" }) { summary ->
                ListRow(
                    summary,
                    selected = summary.list.id == uiState.selectedId,
                    onClick = { onAction(PriceListsAction.Open(summary.list.id)) },
                    trailing = {
                        InlineTextButton(
                            text = stringResource(R.string.action_reactivate),
                            onClick = { onAction(PriceListsAction.Reactivate(summary.list.id)) },
                        )
                    },
                )
            }
        }
    }
}

/**
 * Una lista: su nombre, debajo su tipo (`Precios fijos` o `−10,0 % sobre Lista general`) y cuántos
 * productos tienen precio propio. Un solo foco, que se lee entero.
 */
@Composable
private fun ListRow(
    summary: PriceListSummary,
    selected: Boolean,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    val spacing = AppTheme.spacing
    val kind = kindText(summary)
    val count =
        summary.ownPrices.takeIf { it > 0 && !summary.list.isDefault }?.let {
            pluralStringResource(R.plurals.price_list_own_prices_count, it, it)
        }
    val reading = listOfNotNull(summary.name, kind, count).joinToString()
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier.background(if (selected) scheme.surfaceContainerHigh else scheme.surface),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = AppTheme.sizes.listRow),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                Modifier
                    .weight(1f)
                    .heightIn(min = AppTheme.sizes.listRow)
                    .clickable(onClick = onClick)
                    .clearAndSetSemantics {
                        contentDescription = reading
                        role = Role.Button
                    }
                    .padding(horizontal = spacing.s16, vertical = spacing.s12),
                verticalArrangement = Arrangement.spacedBy(spacing.s2),
            ) {
                Text(
                    summary.name,
                    style = MaterialTheme.typography.titleSmall,
                    color =
                        if (summary.list.isArchived) scheme.onSurfaceVariant else scheme.onSurface,
                )
                Text(
                    kind,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
                if (count != null) {
                    Text(
                        count,
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
            trailing?.let { Box(Modifier.padding(end = spacing.s8)) { it() } }
        }
        HorizontalDivider(
            modifier = Modifier.padding(start = spacing.s16),
            thickness = AppTheme.sizes.hairline,
            color = scheme.outlineVariant,
        )
    }
}

/** El tipo de una lista en palabras: `Precios fijos`, o el ajuste con signo sobre su base. */
@Composable
private fun kindText(summary: PriceListSummary): String =
    when (val pricing = summary.list.pricing) {
        PriceListPricing.Fixed -> stringResource(R.string.price_list_fixed_desc)

        is PriceListPricing.Relative ->
            stringResource(
                R.string.price_list_relative_desc,
                LocalAppFormats.current.percent.formatChange(pricing.adjustment),
                summary.baseName.orEmpty(),
            )
    }

@Composable
private fun Detail(
    uiState: PriceListsUiState,
    selected: PriceListSummary,
    onAction: (PriceListsAction) -> Unit,
    closable: Boolean,
) {
    val list = selected.list
    val editable = !list.isDefault && !list.isArchived
    Column(Modifier.fillMaxSize()) {
        AppTopBar(
            title = selected.name,
            navigation = if (closable) TopBarNavigation.BACK else TopBarNavigation.NONE,
            onNavigate = { onAction(PriceListsAction.CloseDetail) },
            navigationLabel = stringResource(R.string.a11y_back),
            titleStyle = MaterialTheme.typography.titleLarge,
            actions = {
                if (editable) {
                    OverflowMenu(
                        listOf(
                            OverflowAction(stringResource(R.string.action_rename), {
                                onAction(PriceListsAction.EditSelected)
                            }),
                            OverflowAction(stringResource(R.string.action_archive), {
                                onAction(PriceListsAction.AskArchiveSelected)
                            }),
                        ),
                    )
                }
            },
        )
        Column(
            Modifier
                .align(Alignment.CenterHorizontally)
                .widthIn(max = AppTheme.sizes.maxContentWidth)
                .fillMaxWidth(),
        ) {
            KindCard(
                selected,
                onClick = if (editable) {
                    (
                        {
                            onAction(PriceListsAction.EditSelected)
                        }
                        )
                } else {
                    null
                },
            )
            SearchField(
                query = uiState.query,
                onQueryChange = { onAction(PriceListsAction.Search(it)) },
                placeholder = stringResource(R.string.sale_search_hint),
                modifier = Modifier.padding(horizontal = AppTheme.spacing.s16),
            )
            when {
                uiState.rowsLoading -> LoadingRows(DETAIL_SKELETON_ROWS)

                uiState.rows.isEmpty() && uiState.query.isNotBlank() ->
                    EmptyStateInline(
                        title = stringResource(
                            R.string.price_list_search_no_results,
                            uiState.query.trim(),
                        ),
                        modifier = Modifier.padding(AppTheme.spacing.s16),
                    )

                else -> ProductRows(
                    uiState,
                    list.isArchived || list.pricing is PriceListPricing.Relative,
                    onAction,
                )
            }
        }
    }
}

/** El tipo de la lista y, si es relativa, su base y su ajuste. Tocarla abre la hoja de edición. */
@Composable
private fun KindCard(selected: PriceListSummary, onClick: (() -> Unit)?) {
    val spacing = AppTheme.spacing
    AppCard(
        modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.s16, vertical = spacing.s8),
        onClick = onClick,
    ) {
        val kind =
            when (selected.list.pricing) {
                PriceListPricing.Fixed -> R.string.price_list_kind_fixed
                is PriceListPricing.Relative -> R.string.price_list_kind_relative
            }
        Text(
            stringResource(kind),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            kindText(selected),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ProductRows(
    uiState: PriceListsUiState,
    readOnly: Boolean,
    onAction: (PriceListsAction) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = AppTheme.spacing.s24),
    ) {
        productRows(uiState.rows, readOnly, onAction)
    }
}

private fun LazyListScope.productRows(
    rows: List<ListProductRow>,
    readOnly: Boolean,
    onAction: (PriceListsAction) -> Unit,
) {
    items(rows, key = { "product-${it.productId}" }) { row ->
        val tag =
            when (row.origin) {
                null -> null
                ListPriceOrigin.SALE_PRICE -> stringResource(R.string.price_list_tag_general)
                ListPriceOrigin.OWN -> stringResource(R.string.price_list_tag_own)
                ListPriceOrigin.CALCULATED -> stringResource(R.string.price_list_tag_calculated)
            }
        ProductRow(
            data = ProductRowData(
                name = row.name,
                stock = null,
                trailing = ProductRowTrailing.Catalog(
                    price = row.price?.takeIf { it.minor > 0L },
                    margin = row.margin,
                    hasCost = row.hasCost,
                ),
                tag = tag,
            ),
            onClick = if (readOnly) {
                null
            } else {
                (
                    {
                        onAction(PriceListsAction.EditPrice(row.productId))
                    }
                    )
            },
        )
    }
}

@Composable
private fun LoadingRows(rows: Int) {
    DelayedSkeleton {
        Column(
            Modifier.padding(AppTheme.spacing.s16),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12),
        ) {
            repeat(rows) {
                SkeletonBlock(Modifier.fillMaxWidth().heightIn(min = AppTheme.sizes.listRow))
            }
        }
    }
}

@Composable
private fun ReadFailed(onAction: (PriceListsAction) -> Unit) {
    EmptyStateInline(
        title = stringResource(R.string.state_error_read),
        modifier = Modifier.padding(AppTheme.spacing.s16),
        action = {
            InlineTextButton(
                text = stringResource(R.string.action_retry),
                onClick = { onAction(PriceListsAction.Retry) },
            )
        },
    )
}

@Composable
private fun PriceListDialog(dialog: PriceListsDialog, onAction: (PriceListsAction) -> Unit) {
    when (dialog) {
        is PriceListsDialog.Archive ->
            ConfirmDialog(
                title = stringResource(R.string.price_list_archive_confirm_title, dialog.name),
                body = pluralStringResource(
                    R.plurals.price_list_archive_confirm_body,
                    dialog.customers,
                    dialog.customers,
                ),
                confirmLabel = stringResource(R.string.price_list_archive_confirm_action),
                onConfirm = { onAction(PriceListsAction.ConfirmDialog) },
                onDismiss = { onAction(PriceListsAction.DismissDialog) },
            )
    }
}

@Composable
private fun messageText(message: PriceListsMessage): String = when (message) {
    PriceListsMessage.ListSaved -> stringResource(R.string.price_list_saved)
    PriceListsMessage.PriceSaved -> stringResource(R.string.price_list_price_saved)
    PriceListsMessage.NameTaken -> stringResource(R.string.price_list_error_name_repeated)
    is PriceListsMessage.Failed -> stringResource(saveErrorText(message.outOfSpace))
}

private const val LIST_SKELETON_ROWS = 4
private const val DETAIL_SKELETON_ROWS = 6
