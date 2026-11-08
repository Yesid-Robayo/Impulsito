package io.github.yesidrobayo.shopledger.ui.product

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ObserveProductCatalogUseCase
import io.github.yesidrobayo.shopledger.navigation.AppNavKey
import io.github.yesidrobayo.shopledger.navigation.AppNavigator
import io.github.yesidrobayo.shopledger.navigation.PlaceholderRoute
import io.github.yesidrobayo.shopledger.navigation.ScreenModel
import io.github.yesidrobayo.shopledger.navigation.rememberAppViewModel
import io.github.yesidrobayo.shopledger.ui.components.TonalFab

/**
 * La lista de productos en la navegación: obtiene su ViewModel y resuelve abrir un producto o
 * crear uno. Mientras la base abre, la lista se ve cargando; si la base va al rescate, la pantalla
 * no lee nada y muestra su estado vacío.
 */
@Composable
fun ProductListRoute(navigator: AppNavigator) {
    val model =
        rememberAppViewModel { dependencies ->
            ProductListViewModel(
                observeCatalog =
                    ObserveProductCatalogUseCase(
                        dependencies.ports.products,
                        dependencies.ports.units,
                    ),
                computation = dependencies.container.dispatchers.default,
            )
        }
    when (model) {
        ScreenModel.Opening -> ProductListScreen(uiState = ProductListUiState(), onAction = {})

        ScreenModel.Unavailable -> PlaceholderRoute(AppNavKey.ProductList, navigator)

        is ScreenModel.Ready -> {
            val viewModel = model.viewModel
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            ProductListScreen(
                uiState = uiState,
                onAction = { action ->
                    when (action) {
                        is ProductListAction.OpenProduct ->
                            navigator.open(AppNavKey.ProductDetail(action.productId))

                        is ProductListAction.CreateProduct ->
                            navigator.open(AppNavKey.ProductEdit(prefillName = action.name))

                        is ProductListAction.QueryChange, ProductListAction.Retry ->
                            viewModel.onAction(action)
                    }
                },
                floatingAction = {
                    TonalFab(
                        text = stringResource(R.string.product_new_title),
                        onClick = { navigator.open(AppNavKey.ProductEdit()) },
                    )
                },
            )
        }
    }
}
