package io.github.yesidrobayo.shopledger.ui.supplier

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.catalog.SupplierListEntry
import io.github.yesidrobayo.shopledger.ui.components.ActionVariant
import io.github.yesidrobayo.shopledger.ui.components.AppTopBar
import io.github.yesidrobayo.shopledger.ui.components.DelayedSkeleton
import io.github.yesidrobayo.shopledger.ui.components.EmptyState
import io.github.yesidrobayo.shopledger.ui.components.InlineTextButton
import io.github.yesidrobayo.shopledger.ui.components.PrimaryActionButton
import io.github.yesidrobayo.shopledger.ui.components.SearchField
import io.github.yesidrobayo.shopledger.ui.components.SkeletonBlock
import io.github.yesidrobayo.shopledger.ui.components.StatusTag
import io.github.yesidrobayo.shopledger.ui.components.TonalFab
import io.github.yesidrobayo.shopledger.ui.components.TonalFabDefaults
import io.github.yesidrobayo.shopledger.ui.components.TopBarNavigation
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

/**
 * La lista de proveedores, sin ViewModel. Busca por nombre, sin mayúsculas ni tildes, y muestra de
 * cada uno su teléfono o, sin teléfono, cuántos productos lo tienen como habitual. Los archivados van
 * en su propio grupo, cerrado al abrir, cada uno con `Reactivar`.
 *
 * Como pestaña de Productos ([tabs] con las pestañas), es una raíz: deja libre la barra inferior. Como
 * destino propio ([navigation] en `BACK`), vuelve atrás.
 *
 * @param selectedId el proveedor abierto en el panel derecho, en una ventana ancha.
 */
@Composable
fun SupplierListScreen(
    uiState: SupplierListUiState,
    onAction: (SupplierListAction) -> Unit,
    modifier: Modifier = Modifier,
    navigation: TopBarNavigation = TopBarNavigation.NONE,
    onBack: () -> Unit = {},
    tabs: (@Composable () -> Unit)? = null,
    selectedId: Long? = null,
) {
    val spacing = AppTheme.spacing
    val sizes = AppTheme.sizes
    val isRoot = navigation == TopBarNavigation.NONE
    // Una raíz deja libre el alto de la barra inferior, más lo que sobresale Vender.
    val bottomClearance = if (isRoot) sizes.bottomBar + sizes.sellButtonOverhang else 0.dp
    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxSize()) {
            AppTopBar(
                title = stringResource(
                    if (isRoot) R.string.nav_products else R.string.product_tab_suppliers,
                ),
                navigation = navigation,
                onNavigate = onBack,
                navigationLabel = if (isRoot) null else stringResource(R.string.a11y_back),
            )
            Column(
                Modifier
                    .weight(1f)
                    .align(Alignment.CenterHorizontally)
                    .widthIn(max = sizes.maxContentWidth)
                    .fillMaxWidth()
                    .navigationBarsPadding(),
            ) {
                tabs?.invoke()
                if (uiState.content != SupplierListContent.Empty) {
                    SearchField(
                        query = uiState.query,
                        onQueryChange = { onAction(SupplierListAction.QueryChange(it)) },
                        placeholder = stringResource(R.string.supplier_search_hint),
                        modifier = Modifier.padding(
                            horizontal = spacing.s16,
                            vertical = spacing.s8,
                        ),
                    )
                }
                SupplierListBody(
                    uiState,
                    onAction,
                    PaddingValues(bottom = bottomClearance + TonalFabDefaults.listEndPadding),
                    selectedId,
                )
            }
        }
        if (uiState.content != SupplierListContent.Empty) {
            TonalFab(
                text = stringResource(R.string.supplier_new_title),
                onClick = { onAction(SupplierListAction.NewSupplier()) },
                icon = R.drawable.ic_add,
                modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = bottomClearance),
            )
        }
    }
}

@Composable
private fun SupplierListBody(
    uiState: SupplierListUiState,
    onAction: (SupplierListAction) -> Unit,
    bottomClearance: PaddingValues,
    selectedId: Long?,
) {
    when (val content = uiState.content) {
        SupplierListContent.Loading -> DelayedSkeleton {
            Column(
                Modifier.padding(AppTheme.spacing.s16),
                verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12),
            ) {
                repeat(SKELETON_ROWS) {
                    SkeletonBlock(Modifier.fillMaxWidth().heightIn(min = AppTheme.sizes.listRow))
                }
            }
        }

        SupplierListContent.Empty -> CenteredState {
            EmptyState(
                icon = R.drawable.ic_storefront,
                title = stringResource(R.string.empty_suppliers_title),
                body = stringResource(R.string.empty_suppliers_body),
            ) {
                PrimaryActionButton(
                    text = stringResource(R.string.empty_suppliers_action),
                    onClick = { onAction(SupplierListAction.NewSupplier()) },
                    variant = ActionVariant.TONAL,
                )
            }
        }

        is SupplierListContent.NoResults -> CenteredState {
            EmptyState(
                icon = R.drawable.ic_search,
                title = stringResource(R.string.empty_search_title, content.query),
            ) {
                PrimaryActionButton(
                    text = stringResource(R.string.supplier_new_title),
                    onClick = { onAction(SupplierListAction.NewSupplier(content.query)) },
                    variant = ActionVariant.TONAL,
                )
            }
        }

        is SupplierListContent.Suppliers ->
            LazyColumn(Modifier.fillMaxSize(), contentPadding = bottomClearance) {
                supplierRows(content.active, archived = false, selectedId, onAction)
                if (content.archived.isNotEmpty()) {
                    item(key = "archived-header", contentType = HEADER) {
                        ArchivedHeader(content.archived.size, uiState.showArchived) {
                            onAction(SupplierListAction.ToggleArchived)
                        }
                    }
                    if (uiState.showArchived) {
                        supplierRows(content.archived, archived = true, selectedId, onAction)
                    }
                }
            }

        SupplierListContent.ReadError -> CenteredState {
            EmptyState(
                icon = R.drawable.ic_error,
                title = stringResource(R.string.state_error_read),
            ) {
                PrimaryActionButton(
                    text = stringResource(R.string.action_retry),
                    onClick = { onAction(SupplierListAction.Retry) },
                    variant = ActionVariant.TONAL,
                )
            }
        }
    }
}

private fun LazyListScope.supplierRows(
    entries: List<SupplierListEntry>,
    archived: Boolean,
    selectedId: Long?,
    onAction: (SupplierListAction) -> Unit,
) {
    items(entries, key = { it.supplier.id }, contentType = { ROW }) { entry ->
        SupplierRow(
            entry = entry,
            selected = entry.supplier.id == selectedId,
            onClick = { onAction(SupplierListAction.OpenSupplier(entry.supplier.id)) },
            onReactivate =
                if (archived) {
                    { onAction(SupplierListAction.Reactivate(entry.supplier.id)) }
                } else {
                    null
                },
        )
    }
}

/**
 * Un proveedor: su nombre y, debajo, su teléfono o cuántos productos tiene como habitual. Un
 * archivado lleva su etiqueta y `Reactivar`. Una fila, un foco.
 */
@Composable
private fun SupplierRow(
    entry: SupplierListEntry,
    selected: Boolean,
    onClick: () -> Unit,
    onReactivate: (() -> Unit)?,
) {
    val spacing = AppTheme.spacing
    val scheme = MaterialTheme.colorScheme
    val details = entry.supplier.details
    val nameColor = if (onReactivate == null) scheme.onSurface else scheme.onSurfaceVariant
    val secondary =
        details.phone
            ?: pluralStringResource(
                R.plurals.supplier_products_count,
                entry.usualProducts,
                entry.usualProducts,
            )
    Surface(color = if (selected) scheme.secondaryContainer else scheme.surface) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button, onClick = onClick)
                    .heightIn(min = AppTheme.sizes.listRow)
                    .padding(horizontal = spacing.s16, vertical = spacing.s12),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.s12),
            ) {
                Column(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(spacing.s2),
                ) {
                    Text(
                        details.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = nameColor,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        secondary,
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                    // Un archivado lleva su etiqueta y `Reactivar` debajo del nombre: a su lado le
                    // quitarían el ancho al nombre con letra grande.
                    if (onReactivate != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(spacing.s12),
                        ) {
                            StatusTag(stringResource(R.string.status_archived))
                            InlineTextButton(
                                stringResource(R.string.action_reactivate),
                                onReactivate,
                            )
                        }
                    }
                }
                if (onReactivate == null) {
                    Icon(
                        painterResource(R.drawable.ic_chevron_right),
                        contentDescription = null,
                        tint = scheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = spacing.s4),
                    )
                }
            }
            HorizontalDivider(
                modifier = Modifier.padding(start = spacing.s16),
                thickness = AppTheme.sizes.hairline,
                color = scheme.outlineVariant,
            )
        }
    }
}

/** El encabezado del grupo de archivados, que lo abre o lo cierra. */
@Composable
private fun ArchivedHeader(count: Int, open: Boolean, onToggle: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val state =
        stringResource(
            if (open) {
                R.string.payment_method_a11y_archived_expanded
            } else {
                R.string.payment_method_a11y_archived_collapsed
            },
        )
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onToggle)
            .heightIn(min = AppTheme.sizes.touchTarget)
            .padding(horizontal = AppTheme.spacing.s16, vertical = AppTheme.spacing.s8)
            .semantics(mergeDescendants = true) {
                heading()
                stateDescription = state
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.supplier_group_archived, count),
            style = MaterialTheme.typography.labelLarge,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Icon(
            painterResource(R.drawable.ic_expand_more),
            contentDescription = null,
            tint = scheme.onSurfaceVariant,
            modifier = Modifier.size(AppTheme.sizes.icon).rotate(if (open) HALF_TURN else 0f),
        )
    }
}

/** Un estado que ocupa la pantalla: centrado y desplazable con letra grande. */
@Composable
private fun CenteredState(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}

private const val SKELETON_ROWS = 6
private const val HALF_TURN = 180f
private const val HEADER = "header"
private const val ROW = "row"

// ---------------------------------------------------------------- vistas previas

@ThemePreviews
@Composable
private fun SupplierListPreview() {
    PreviewTheme {
        SupplierListScreen(
            SupplierListUiState(
                content =
                    SupplierListContent.Suppliers(SupplierSamples.active, SupplierSamples.archived),
                showArchived = true,
            ),
            onAction = {},
            selectedId = 2L,
        )
    }
}

@ThemePreviews
@Composable
private fun SupplierListEmptyPreview() {
    PreviewTheme {
        SupplierListScreen(SupplierListUiState(content = SupplierListContent.Empty), {})
    }
}

@Preview(name = "Sin resultados")
@Composable
private fun SupplierListNoResultsPreview() {
    PreviewTheme {
        SupplierListScreen(
            SupplierListUiState(query = "sol", content = SupplierListContent.NoResults("sol")),
            {},
            navigation = TopBarNavigation.BACK,
        )
    }
}

@Preview(name = "Error")
@Composable
private fun SupplierListErrorPreview() {
    PreviewTheme {
        SupplierListScreen(SupplierListUiState(content = SupplierListContent.ReadError), {})
    }
}

@Preview(name = "Cargando")
@Composable
private fun SupplierListLoadingPreview() {
    PreviewTheme { SupplierListScreen(SupplierListUiState(), {}) }
}
