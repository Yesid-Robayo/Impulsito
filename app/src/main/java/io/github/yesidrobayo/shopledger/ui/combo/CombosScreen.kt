package io.github.yesidrobayo.shopledger.ui.combo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.Margin
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.ui.components.ActionVariant
import io.github.yesidrobayo.shopledger.ui.components.AppTopBar
import io.github.yesidrobayo.shopledger.ui.components.DelayedSkeleton
import io.github.yesidrobayo.shopledger.ui.components.EmptyState
import io.github.yesidrobayo.shopledger.ui.components.NoticeCard
import io.github.yesidrobayo.shopledger.ui.components.NoticeVariant
import io.github.yesidrobayo.shopledger.ui.components.PrimaryActionButton
import io.github.yesidrobayo.shopledger.ui.components.ProductRow
import io.github.yesidrobayo.shopledger.ui.components.ProductRowData
import io.github.yesidrobayo.shopledger.ui.components.ProductRowTrailing
import io.github.yesidrobayo.shopledger.ui.components.SearchField
import io.github.yesidrobayo.shopledger.ui.components.TonalFab
import io.github.yesidrobayo.shopledger.ui.components.TonalFabDefaults
import io.github.yesidrobayo.shopledger.ui.components.TopBarNavigation
import io.github.yesidrobayo.shopledger.ui.components.keepsMessagesAbove
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme

/**
 * La lista de combos y recetas: una búsqueda arriba, una fila por combo con cuántos componentes
 * lleva, cuántos alcanzan, su precio y su margen, y `Nuevo combo` abajo.
 *
 * Sin combos, explica para qué sirven y ofrece crear el primero. El margen va con modo discreto,
 * como en el catálogo; el precio no, porque es lo que se atiende.
 */
@Composable
fun CombosScreen(
    uiState: CombosUiState,
    onAction: (CombosAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxSize()) {
            AppTopBar(
                title = stringResource(R.string.combo_title),
                navigation = TopBarNavigation.BACK,
                onNavigate = { onAction(CombosAction.Back) },
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
                    uiState.loading -> LoadingRows()

                    uiState.loadFailed -> NoticeCard(
                        text = stringResource(R.string.state_error_read),
                        variant = NoticeVariant.ERROR,
                        modifier = Modifier.padding(AppTheme.spacing.s16),
                        actionLabel = stringResource(R.string.action_retry),
                        onAction = { onAction(CombosAction.Retry) },
                    )

                    !uiState.hasCombos -> EmptyState(
                        icon = R.drawable.ic_inventory_2,
                        title = stringResource(R.string.combo_empty_title),
                        body = stringResource(R.string.combo_empty_body),
                        modifier = Modifier.padding(top = AppTheme.spacing.s24),
                    ) {
                        PrimaryActionButton(
                            text = stringResource(R.string.combo_new_action),
                            onClick = { onAction(CombosAction.NewCombo) },
                            variant = ActionVariant.TONAL,
                        )
                    }

                    else -> ComboList(uiState, onAction)
                }
            }
        }
        if (uiState.hasCombos) {
            TonalFab(
                text = stringResource(R.string.combo_new_action),
                onClick = { onAction(CombosAction.NewCombo) },
                modifier = Modifier.align(Alignment.BottomEnd).keepsMessagesAbove(),
            )
        }
    }
}

@Composable
private fun ComboList(uiState: CombosUiState, onAction: (CombosAction) -> Unit) {
    val spacing = AppTheme.spacing
    SearchField(
        query = uiState.query,
        onQueryChange = { onAction(CombosAction.QueryChange(it)) },
        placeholder = stringResource(R.string.sale_search_hint),
        modifier = Modifier.padding(horizontal = spacing.s16, vertical = spacing.s8),
    )
    if (uiState.items.isEmpty()) {
        // Región viva educada: con TalkBack, se oye que la búsqueda no encontró nada.
        Text(
            text = stringResource(R.string.combo_search_no_results, uiState.query.trim()),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(spacing.s16)
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
        return
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = TonalFabDefaults.listEndPadding),
    ) {
        items(uiState.items, key = { it.id }) { item ->
            ProductRow(
                data = rowData(item),
                onClick = { onAction(CombosAction.Open(item.id)) },
            )
        }
    }
}

/** El nombre, los componentes y lo que alcanza a la izquierda; precio y margen a la derecha. */
@Composable
private fun rowData(item: ComboItemUi): ProductRowData {
    val formats = LocalAppFormats.current
    val count = pluralStringResource(
        R.plurals.combo_component_count,
        item.componentCount,
        item.componentCount,
    )
    val available = item.available?.let {
        stringResource(
            R.string.combo_available,
            formats.quantity.format(QuantityMilli.ofUnits(it), allowsFraction = false),
        )
    }
    return ProductRowData(
        name = item.name,
        stock = null,
        trailing = ProductRowTrailing.Catalog(
            price = item.price.takeIf { it > Money.ZERO },
            margin = item.margin,
            hasCost = item.hasCost,
        ),
        tags = listOfNotNull(count, available),
    )
}

/** Tres filas de esqueleto, solo si la lectura tarda más de lo que se nota. */
@Composable
private fun LoadingRows() {
    DelayedSkeleton {
        Column {
            repeat(SKELETON_ROWS) {
                ProductRow(
                    data = ProductRowData(
                        name = "",
                        stock = null,
                        trailing = ProductRowTrailing.Catalog(null, Margin.NoData, false),
                    ),
                    loading = true,
                )
            }
        }
    }
}

private const val SKELETON_ROWS = 3
