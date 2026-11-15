package io.github.yesidrobayo.shopledger.ui.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.stringResource
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceList
import io.github.yesidrobayo.shopledger.domain.rule.document.CatalogFilter
import io.github.yesidrobayo.shopledger.ui.components.ActionState
import io.github.yesidrobayo.shopledger.ui.components.ActionVariant
import io.github.yesidrobayo.shopledger.ui.components.AppTextField
import io.github.yesidrobayo.shopledger.ui.components.AppTopBar
import io.github.yesidrobayo.shopledger.ui.components.DelayedSkeleton
import io.github.yesidrobayo.shopledger.ui.components.EmptyState
import io.github.yesidrobayo.shopledger.ui.components.InlineTextButton
import io.github.yesidrobayo.shopledger.ui.components.NoticeCard
import io.github.yesidrobayo.shopledger.ui.components.NoticeVariant
import io.github.yesidrobayo.shopledger.ui.components.OverflowAction
import io.github.yesidrobayo.shopledger.ui.components.OverflowMenu
import io.github.yesidrobayo.shopledger.ui.components.PrimaryActionButton
import io.github.yesidrobayo.shopledger.ui.components.ReceiptPreview
import io.github.yesidrobayo.shopledger.ui.components.SectionHeader
import io.github.yesidrobayo.shopledger.ui.components.SettingsRow
import io.github.yesidrobayo.shopledger.ui.components.SettingsRowAction
import io.github.yesidrobayo.shopledger.ui.components.TopBarNavigation
import io.github.yesidrobayo.shopledger.ui.components.keepsMessagesAbove
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme

/**
 * El catálogo compartible: qué productos, la lista de precios si hay más de una, fotos, agrupar y
 * agotados, el título, el resumen y la primera página tal como sale. `Compartir PDF` va fija abajo
 * y `Guardar PDF` en el menú. Es pura: todo llega en [uiState] y sale por [onAction]; la vista
 * previa llega dibujada en [preview], con [pages] páginas, y [listName] da el nombre de una lista.
 */
@Composable
fun CatalogScreen(
    uiState: CatalogUiState,
    onAction: (CatalogAction) -> Unit,
    modifier: Modifier = Modifier,
    preview: ImageBitmap? = null,
    pages: Int? = null,
    listName: @Composable (PriceList) -> String = { it.name.orEmpty() },
) {
    val ready = uiState as? CatalogUiState.Ready
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        AppTopBar(
            title = stringResource(R.string.catalog_title),
            navigation = TopBarNavigation.BACK,
            onNavigate = { onAction(CatalogAction.Back) },
            navigationLabel = stringResource(R.string.a11y_back),
            actions = {
                if (ready != null) {
                    OverflowMenu(
                        listOf(
                            OverflowAction(stringResource(R.string.catalog_save_action), {
                                onAction(CatalogAction.Create(CatalogOutput.SAVE))
                            }),
                        ),
                    )
                }
            },
        )
        when (uiState) {
            CatalogUiState.Loading ->
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    DelayedSkeleton {
                        ReceiptPreview(
                            null,
                            stringResource(R.string.a11y_loading),
                            Modifier.padding(AppTheme.spacing.s16),
                        )
                    }
                }

            CatalogUiState.ReadFailed -> EmptyState(
                icon = R.drawable.ic_error,
                title = stringResource(R.string.state_error_read),
                modifier = Modifier.weight(1f),
            ) {
                InlineTextButton(
                    stringResource(R.string.action_retry),
                    { onAction(CatalogAction.Retry) },
                )
            }

            CatalogUiState.NoPricedProducts -> EmptyState(
                icon = R.drawable.ic_error,
                title = stringResource(R.string.catalog_empty_title),
                modifier = Modifier.weight(1f),
            ) {
                PrimaryActionButton(
                    stringResource(R.string.catalog_empty_action),
                    { onAction(CatalogAction.OpenProducts) },
                    ActionVariant.TONAL,
                )
            }

            CatalogUiState.CurrencyUnconfirmed -> EmptyState(
                icon = R.drawable.ic_error,
                title = stringResource(R.string.catalog_title),
                modifier = Modifier.weight(1f),
            ) {
                PrimaryActionButton(
                    stringResource(R.string.catalog_confirm_currency_action),
                    { onAction(CatalogAction.ConfirmCurrency) },
                    ActionVariant.TONAL,
                )
            }

            is CatalogUiState.Ready -> {
                Options(uiState, onAction, preview, pages, listName, Modifier.weight(1f))
                ShareBar(uiState, onAction)
            }
        }
    }
}

@Composable
private fun Options(
    ready: CatalogUiState.Ready,
    onAction: (CatalogAction) -> Unit,
    preview: ImageBitmap?,
    pages: Int?,
    listName: @Composable (PriceList) -> String,
    modifier: Modifier,
) {
    val spacing = AppTheme.spacing
    val choices = ready.choices
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .widthIn(max = AppTheme.sizes.maxContentWidth)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(spacing.s4),
        ) {
            SectionHeader(stringResource(R.string.catalog_which))
            SettingsRow(
                title = stringResource(R.string.catalog_filter_all),
                action = SettingsRowAction.Toggle(choices.filter == CatalogFilter.All) {
                    onAction(CatalogAction.Filter(CatalogFilter.All))
                },
            )
            ready.categories.forEach { category ->
                val chosen = (choices.filter as? CatalogFilter.InCategory)?.categoryId
                SettingsRow(
                    title = category.details.name,
                    summary = stringResource(R.string.catalog_filter_category),
                    action = SettingsRowAction.Toggle(chosen == category.id) {
                        onAction(CatalogAction.Filter(CatalogFilter.InCategory(category.id)))
                    },
                )
            }
            if (ready.lists.size > 1) {
                SectionHeader(stringResource(R.string.catalog_price_list))
                ready.lists.forEach { list ->
                    SettingsRow(
                        title = listName(list),
                        action = SettingsRowAction.Toggle(list.id == ready.listId) {
                            onAction(CatalogAction.PriceListChosen(list.id))
                        },
                    )
                }
            }
            if (ready.hasPhotos) {
                SettingsRow(
                    title = stringResource(R.string.catalog_show_photos),
                    action = SettingsRowAction.Toggle(choices.showPhotos) {
                        onAction(CatalogAction.ShowPhotos(it))
                    },
                )
            }
            SettingsRow(
                title = stringResource(R.string.catalog_group_by_category),
                action = SettingsRowAction.Toggle(choices.groupByCategory) {
                    onAction(CatalogAction.GroupByCategory(it))
                },
            )
            SettingsRow(
                title = stringResource(R.string.catalog_include_out_of_stock),
                action = SettingsRowAction.Toggle(choices.includeOutOfStock) {
                    onAction(CatalogAction.IncludeOutOfStock(it))
                },
            )
            AppTextField(
                value = choices.title,
                onValueChange = { onAction(CatalogAction.TitleChanged(it)) },
                label = stringResource(R.string.catalog_custom_title_label),
                supportingText = stringResource(R.string.catalog_default_title),
                modifier = Modifier.padding(horizontal = spacing.s16),
            )
            val count = ready.document.productCount
            val summary = when {
                count == 0 -> stringResource(R.string.catalog_filter_empty)
                pages != null -> stringResource(R.string.catalog_summary, count, pages)
                else -> null
            }
            if (count == 0) {
                Text(
                    stringResource(R.string.catalog_filter_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = spacing.s16),
                )
            } else if (pages != null) {
                Text(
                    stringResource(R.string.catalog_summary, count, pages),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = spacing.s16),
                )
            }
            // Un foco: `Vista previa del catálogo. Productos: 24 · Páginas: 3`.
            ReceiptPreview(
                image = preview,
                description = stringResource(
                    R.string.a11y_catalog_preview,
                    summary ?: stringResource(R.string.a11y_loading),
                ),
                modifier = Modifier.padding(spacing.s16),
            )
        }
    }
}

/** `Compartir PDF`: mientras se crea el archivo dice `Generando…`, y una falla lo dice encima. */
@Composable
private fun ShareBar(ready: CatalogUiState.Ready, onAction: (CatalogAction) -> Unit) {
    val spacing = AppTheme.spacing
    val width = Modifier.widthIn(max = AppTheme.sizes.maxContentWidth).fillMaxWidth()
    Column(
        Modifier
            .fillMaxWidth()
            .keepsMessagesAbove()
            .navigationBarsPadding()
            .imePadding()
            .padding(spacing.s16),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(spacing.s8),
    ) {
        if (ready.shareFailed) {
            NoticeCard(
                text = stringResource(R.string.catalog_error_generate),
                variant = NoticeVariant.ERROR,
                actionLabel = stringResource(R.string.action_retry),
                onAction = { onAction(CatalogAction.Create(CatalogOutput.SHARE)) },
                modifier = width,
            )
        }
        val working = ready.sharing != null
        PrimaryActionButton(
            text = stringResource(
                if (working) R.string.catalog_generating else R.string.catalog_share_action,
            ),
            onClick = { onAction(CatalogAction.Create(CatalogOutput.SHARE)) },
            variant = ActionVariant.PRIMARY,
            state = if (working) ActionState.WORKING else ActionState.IDLE,
            enabled = ready.document.productCount > 0,
            modifier = width,
        )
    }
}
