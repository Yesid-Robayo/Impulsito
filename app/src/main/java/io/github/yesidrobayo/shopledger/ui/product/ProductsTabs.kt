package io.github.yesidrobayo.shopledger.ui.product

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.navigation.AppNavigator
import io.github.yesidrobayo.shopledger.navigation.InventoryFilter
import io.github.yesidrobayo.shopledger.ui.components.SegmentedTabs
import io.github.yesidrobayo.shopledger.ui.stock.InventoryRoute
import io.github.yesidrobayo.shopledger.ui.supplier.SupplierListRoute
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme

/** Las tres pestañas de la raíz de Productos, en su orden. */
enum class ProductsTab(@param:StringRes val label: Int) {
    PRODUCTS(R.string.nav_products),
    INVENTORY(R.string.term_inventory),
    SUPPLIERS(R.string.product_tab_suppliers),
}

/** Las pestañas de Productos, debajo de la barra superior de cada una. */
@Composable
fun ProductsTabs(
    selected: ProductsTab,
    onSelect: (ProductsTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tabs = ProductsTab.entries
    SegmentedTabs(
        options = tabs.map { stringResource(it.label) },
        selectedIndex = tabs.indexOf(selected),
        onSelect = { onSelect(tabs[it]) },
        modifier = modifier.padding(
            horizontal = AppTheme.spacing.s16,
            vertical = AppTheme.spacing.s8,
        ),
    )
}

/**
 * La raíz de Productos: dibuja en el mismo lugar la pestaña elegida, que sobrevive al giro y a que
 * Android cierre el proceso. Cambiar de pestaña no apila nada: atrás sigue llevando a Inicio. Cada
 * pestaña recibe las pestañas para ponerlas bajo su propia barra.
 */
@Composable
fun ProductsRoute(navigator: AppNavigator) {
    var tab by rememberSaveable { mutableStateOf(ProductsTab.PRODUCTS) }
    val tabs: @Composable () -> Unit = { ProductsTabs(tab, { tab = it }) }
    when (tab) {
        ProductsTab.PRODUCTS -> ProductListRoute(navigator, tabs)

        ProductsTab.INVENTORY ->
            InventoryRoute(InventoryFilter.LOW_STOCK, navigator, asTab = true, tabs = tabs)

        ProductsTab.SUPPLIERS -> SupplierListRoute(navigator, tabs)
    }
}
