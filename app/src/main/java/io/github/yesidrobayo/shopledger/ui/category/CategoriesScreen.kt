package io.github.yesidrobayo.shopledger.ui.category

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.SeedTable
import io.github.yesidrobayo.shopledger.domain.model.expense.ExpenseCategory
import io.github.yesidrobayo.shopledger.domain.rule.TextNormalizer
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CategoriesOverview
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ExpenseCategoryRow
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ProductCategoryRow
import io.github.yesidrobayo.shopledger.localization.SeedText
import io.github.yesidrobayo.shopledger.ui.components.ActionState
import io.github.yesidrobayo.shopledger.ui.components.ActionVariant
import io.github.yesidrobayo.shopledger.ui.components.AppBottomSheet
import io.github.yesidrobayo.shopledger.ui.components.AppTextField
import io.github.yesidrobayo.shopledger.ui.components.AppTopBar
import io.github.yesidrobayo.shopledger.ui.components.ConfirmDialog
import io.github.yesidrobayo.shopledger.ui.components.ConfirmVariant
import io.github.yesidrobayo.shopledger.ui.components.DelayedSkeleton
import io.github.yesidrobayo.shopledger.ui.components.EmptyState
import io.github.yesidrobayo.shopledger.ui.components.NoticeCard
import io.github.yesidrobayo.shopledger.ui.components.NoticeVariant
import io.github.yesidrobayo.shopledger.ui.components.OverflowAction
import io.github.yesidrobayo.shopledger.ui.components.OverflowMenu
import io.github.yesidrobayo.shopledger.ui.components.PrimaryActionButton
import io.github.yesidrobayo.shopledger.ui.components.SectionHeader
import io.github.yesidrobayo.shopledger.ui.components.SegmentedTabs
import io.github.yesidrobayo.shopledger.ui.components.SettingsRow
import io.github.yesidrobayo.shopledger.ui.components.SettingsRowAction
import io.github.yesidrobayo.shopledger.ui.components.SkeletonBlock
import io.github.yesidrobayo.shopledger.ui.components.TonalFab
import io.github.yesidrobayo.shopledger.ui.components.TopBarNavigation
import io.github.yesidrobayo.shopledger.ui.components.keepsMessagesAbove
import io.github.yesidrobayo.shopledger.ui.components.saveErrorText
import io.github.yesidrobayo.shopledger.ui.help.HelpTopic
import io.github.yesidrobayo.shopledger.ui.help.ScreenHelpLink
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme

/**
 * Las categorías de productos y de gastos: mantenerlas en orden, renombrarlas, eliminar las que
 * sobran y archivar las de gasto con historia.
 *
 * - `Productos` y `Gastos` en segmentos; en `Expanded`, lado a lado y sin segmentos.
 * - Cada fila dice cuántos elementos tiene, y su menú ofrece `Renombrar` y `Eliminar` o `Archivar`
 *   según las reglas. `Otros` no se elimina ni se archiva.
 * - En `Gastos`, las archivadas van al final, con `Reactivar`.
 * - `Nueva categoría` abre la misma hoja que renombrar: el nombre y, en gastos, `Gasto fijo`.
 *
 * Es pura: recibe el estado y avisa cada acción.
 */
@Composable
fun CategoriesScreen(
    uiState: CategoriesUiState,
    onAction: (CategoriesAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= EXPANDED_WIDTH
            Column(Modifier.fillMaxSize()) {
                AppTopBar(
                    title = stringResource(R.string.categories_title),
                    navigation = TopBarNavigation.BACK,
                    onNavigate = { onAction(CategoriesAction.Back) },
                    navigationLabel = stringResource(R.string.a11y_back),
                    actions = { ScreenHelpLink(HelpTopic.CATALOG_SETUP) },
                )
                if (!wide) {
                    SegmentedTabs(
                        options = listOf(
                            stringResource(R.string.nav_products),
                            stringResource(R.string.records_tab_expenses),
                        ),
                        selectedIndex = uiState.tab.ordinal,
                        onSelect = {
                            onAction(CategoriesAction.TabChange(CategoryTab.entries[it]))
                        },
                        modifier = Modifier.padding(horizontal = AppTheme.spacing.s16),
                    )
                }
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    Body(uiState, wide, onAction)
                    TonalFab(
                        text = stringResource(R.string.category_new),
                        onClick = { onAction(CategoriesAction.NewCategory) },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .navigationBarsPadding()
                            .padding(AppTheme.spacing.s16)
                            .keepsMessagesAbove(),
                    )
                }
            }
        }
    }
    uiState.sheet?.let { CategorySheetView(it, onAction) }
    uiState.confirm?.let { ConfirmView(it, onAction) }
}

@Composable
private fun Body(uiState: CategoriesUiState, wide: Boolean, onAction: (CategoriesAction) -> Unit) {
    when (val content = uiState.content) {
        CategoriesContent.Loading -> DelayedSkeleton { ListSkeleton() }

        CategoriesContent.ReadError -> CenteredState {
            EmptyState(
                icon = R.drawable.ic_error,
                title = stringResource(R.string.state_error_read),
            ) {
                PrimaryActionButton(
                    text = stringResource(R.string.action_retry),
                    onClick = { onAction(CategoriesAction.Retry) },
                    variant = ActionVariant.TONAL,
                )
            }
        }

        is CategoriesContent.Ready -> if (wide) {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    TabList(CategoryTab.PRODUCTS, content.overview, onAction, withHeader = true)
                }
                VerticalDivider(
                    thickness = AppTheme.sizes.hairline,
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    TabList(CategoryTab.EXPENSES, content.overview, onAction, withHeader = true)
                }
            }
        } else {
            Box(Modifier.widthIn(max = AppTheme.sizes.maxContentWidth).fillMaxHeight()) {
                TabList(uiState.tab, content.overview, onAction, withHeader = false)
            }
        }
    }
    uiState.failure?.let { failure ->
        Box(Modifier.fillMaxSize().padding(AppTheme.spacing.s16), Alignment.TopCenter) {
            NoticeCard(
                text = stringResource(
                    when (failure) {
                        CategoryFailure.NAME_TAKEN -> R.string.error_category_reactivate_taken
                        CategoryFailure.NOT_SAVED -> R.string.state_error_save
                    },
                ),
                variant = NoticeVariant.ERROR,
                actionLabel = stringResource(R.string.action_close),
                onAction = { onAction(CategoriesAction.FailureShown) },
            )
        }
    }
}

/** La lista de una pestaña, por nombre visible. */
@Composable
private fun TabList(
    tab: CategoryTab,
    overview: CategoriesOverview,
    onAction: (CategoriesAction) -> Unit,
    withHeader: Boolean,
) {
    val resources = LocalResources.current
    // Deja libre lo que ocupa el botón flotante, para que no tape la última fila.
    val bottom = AppTheme.sizes.touchTarget + AppTheme.spacing.s40
    when (tab) {
        CategoryTab.PRODUCTS -> {
            if (overview.products.isEmpty()) {
                CenteredState {
                    if (withHeader) SectionHeader(stringResource(R.string.nav_products))
                    EmptyState(
                        icon = R.drawable.ic_inventory_2,
                        title = stringResource(R.string.empty_categories_title),
                        body = stringResource(R.string.empty_categories_body),
                    ) {
                        PrimaryActionButton(
                            text = stringResource(R.string.empty_categories_action),
                            onClick = { onAction(CategoriesAction.NewCategory) },
                            variant = ActionVariant.TONAL,
                        )
                    }
                }
                return
            }
            val rows = overview.products.sortedBy { it.category.details.normalizedName }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottom)) {
                if (withHeader) header(R.string.nav_products)
                items(rows, key = { "p-${it.category.id}" }) { ProductRow(it, onAction) }
            }
        }

        CategoryTab.EXPENSES -> {
            val byName = { row: ExpenseCategoryRow ->
                TextNormalizer.normalize(expenseCategoryName(resources, row.category))
            }
            val active = overview.expenses.sortedBy(byName)
            val archived = overview.archivedExpenses.sortedBy(byName)
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottom)) {
                if (withHeader) header(R.string.records_tab_expenses)
                items(active, key = { "e-${it.category.id}" }) { ExpenseRow(it, onAction) }
                if (archived.isNotEmpty()) {
                    header(R.string.category_archived)
                    items(archived, key = { "a-${it.category.id}" }) { ExpenseRow(it, onAction) }
                }
            }
        }
    }
}

private fun LazyListScope.header(title: Int) {
    item(key = "h-$title") { SectionHeader(stringResource(title)) }
}

@Composable
private fun ProductRow(row: ProductCategoryRow, onAction: (CategoriesAction) -> Unit) {
    val name = row.category.details.name
    CategoryRowLayout(
        name = name,
        count = pluralStringResource(R.plurals.category_products_count, row.products, row.products),
        actions = listOf(
            OverflowAction(stringResource(R.string.action_rename), {
                onAction(
                    CategoriesAction.Rename(
                        CategoryTab.PRODUCTS,
                        row.category.id,
                        name,
                        isFixed = false,
                        initial = false,
                    ),
                )
            }),
            OverflowAction(stringResource(R.string.action_delete), {
                onAction(
                    CategoriesAction.Ask(
                        CategoryConfirm.DeleteProduct(row.category.id, name, row.products),
                    ),
                )
            }, destructive = true),
        ),
    )
}

@Composable
private fun ExpenseRow(row: ExpenseCategoryRow, onAction: (CategoriesAction) -> Unit) {
    val category = row.category
    val name = expenseCategoryName(LocalResources.current, category)
    val actions = buildList {
        add(
            OverflowAction(stringResource(R.string.action_rename), {
                onAction(
                    CategoriesAction.Rename(
                        CategoryTab.EXPENSES,
                        category.id,
                        category.details.name.orEmpty(),
                        category.details.isFixed,
                        initial = category.systemKey != null,
                    ),
                )
            }),
        )
        if (category.isArchived) {
            add(
                OverflowAction(stringResource(R.string.action_reactivate), {
                    onAction(CategoriesAction.Reactivate(category.id))
                }),
            )
        }
        if (row.canArchive) {
            add(
                OverflowAction(stringResource(R.string.action_archive), {
                    onAction(
                        CategoriesAction.Ask(CategoryConfirm.ArchiveExpense(category.id, name)),
                    )
                }),
            )
        }
        if (row.canDelete) {
            add(
                OverflowAction(stringResource(R.string.action_delete), {
                    onAction(CategoriesAction.Ask(CategoryConfirm.DeleteExpense(category.id, name)))
                }, destructive = true),
            )
        }
    }
    CategoryRowLayout(
        name = name,
        count = pluralStringResource(R.plurals.category_expenses_count, row.expenses, row.expenses),
        actions = actions,
        fixed = category.details.isFixed,
    )
}

/** Una fila: el nombre, cuántos elementos tiene, si es gasto fijo, y su menú. */
@Composable
private fun CategoryRowLayout(
    name: String,
    count: String,
    actions: List<OverflowAction>,
    fixed: Boolean = false,
) {
    val spacing = AppTheme.spacing
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = AppTheme.sizes.listRow)
                .padding(
                    start = spacing.s16,
                    end = spacing.s4,
                    top = spacing.s8,
                    bottom = spacing.s8,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.s2)) {
                Text(
                    name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val detail = if (fixed) {
                    stringResource(
                        R.string.category_row_detail,
                        count,
                        stringResource(R.string.category_fixed_expense),
                    )
                } else {
                    count
                }
                Text(detail, style = MaterialTheme.typography.bodySmall, color = muted)
            }
            OverflowMenu(actions)
        }
        HorizontalDivider(
            modifier = Modifier.padding(start = spacing.s16),
            thickness = AppTheme.sizes.hairline,
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

/** La hoja de crear o renombrar: el nombre y, en una de gasto, `Gasto fijo`. */
@Composable
private fun CategorySheetView(sheet: CategorySheet, onAction: (CategoriesAction) -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AppBottomSheet(
        onDismissRequest = { onAction(CategoriesAction.CloseSheet) },
        title = stringResource(
            if (sheet.editingId == null) R.string.category_new else R.string.action_rename,
        ),
        action = {
            PrimaryActionButton(
                text = stringResource(R.string.category_save),
                onClick = { onAction(CategoriesAction.Save) },
                variant = ActionVariant.PRIMARY,
                state = if (sheet.saving) ActionState.WORKING else ActionState.IDLE,
            )
        },
    ) {
        AppTextField(
            value = sheet.name,
            onValueChange = { onAction(CategoriesAction.NameChange(it)) },
            label = stringResource(R.string.form_name),
            modifier = Modifier.focusRequester(focus),
            imeAction = ImeAction.Done,
            error = sheet.error?.let {
                stringResource(
                    when (it) {
                        CategoryNameError.EMPTY -> R.string.error_category_name_empty
                        CategoryNameError.TOO_LONG -> R.string.error_category_name_long
                        CategoryNameError.DUPLICATE -> R.string.error_category_name_duplicate
                    },
                )
            },
            supportingText = stringResource(R.string.category_initial_name_hint)
                .takeIf { sheet.initial },
        )
        if (sheet.tab == CategoryTab.EXPENSES) {
            SettingsRow(
                title = stringResource(R.string.category_fixed_expense),
                action = SettingsRowAction.Toggle(sheet.isFixed) {
                    onAction(CategoriesAction.FixedChange(it))
                },
            )
        }
        if (sheet.saveFailed) {
            NoticeCard(stringResource(saveErrorText(outOfSpace = false)), NoticeVariant.ERROR)
        }
    }
}

@Composable
private fun ConfirmView(confirm: CategoryConfirm, onAction: (CategoriesAction) -> Unit) {
    val dismiss = { onAction(CategoriesAction.CloseConfirm) }
    val ok = { onAction(CategoriesAction.Confirm) }
    when (confirm) {
        is CategoryConfirm.DeleteProduct -> ConfirmDialog(
            title = stringResource(R.string.dialog_delete_category_title, confirm.name),
            body = pluralStringResource(
                R.plurals.dialog_delete_category_products_body,
                confirm.products,
                confirm.products,
            ),
            confirmLabel = stringResource(R.string.dialog_delete_category_action),
            onConfirm = ok,
            onDismiss = dismiss,
            variant = ConfirmVariant.DESTRUCTIVE,
        )

        is CategoryConfirm.DeleteExpense -> ConfirmDialog(
            title = stringResource(R.string.dialog_delete_category_title, confirm.name),
            body = stringResource(R.string.dialog_delete_category_expenses_body),
            confirmLabel = stringResource(R.string.dialog_delete_category_action),
            onConfirm = ok,
            onDismiss = dismiss,
            variant = ConfirmVariant.DESTRUCTIVE,
        )

        is CategoryConfirm.ArchiveExpense -> ConfirmDialog(
            title = stringResource(R.string.dialog_archive_category_title, confirm.name),
            body = stringResource(R.string.dialog_archive_category_body),
            confirmLabel = stringResource(R.string.dialog_archive_category_action),
            onConfirm = ok,
            onDismiss = dismiss,
        )
    }
}

/** El nombre visible de una categoría de gasto: el de la persona, o el traducido de una inicial. */
internal fun expenseCategoryName(
    resources: android.content.res.Resources,
    category: ExpenseCategory,
): String = SeedText.displayName(
    resources,
    SeedTable.EXPENSE_CATEGORIES,
    category.systemKey,
    category.details.name,
)

@Composable
private fun CenteredState(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}

/** Seis filas de esqueleto con la forma de la lista. */
@Composable
private fun ListSkeleton() {
    Column(
        Modifier.fillMaxWidth().padding(AppTheme.spacing.s16),
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12),
    ) {
        repeat(SKELETON_ROWS) {
            SkeletonBlock(Modifier.fillMaxWidth().heightIn(min = AppTheme.sizes.listRowSingle))
        }
    }
}

// Desde 840 dp, productos y gastos lado a lado.
private val EXPANDED_WIDTH = 840.dp

private const val SKELETON_ROWS = 6
