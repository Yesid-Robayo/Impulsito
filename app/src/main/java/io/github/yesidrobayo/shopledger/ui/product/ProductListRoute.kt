package io.github.yesidrobayo.shopledger.ui.product

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.model.catalog.Category
import io.github.yesidrobayo.shopledger.domain.model.catalog.Supplier
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ObserveBulkPriceChoicesUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ObserveProductCatalogUseCase
import io.github.yesidrobayo.shopledger.navigation.AppNavKey
import io.github.yesidrobayo.shopledger.navigation.AppNavigator
import io.github.yesidrobayo.shopledger.navigation.PlaceholderRoute
import io.github.yesidrobayo.shopledger.navigation.ScanPurpose
import io.github.yesidrobayo.shopledger.navigation.ScreenModel
import io.github.yesidrobayo.shopledger.navigation.rememberAppViewModel
import io.github.yesidrobayo.shopledger.ui.backup.RootBackupBanner
import io.github.yesidrobayo.shopledger.ui.components.TonalFab
import io.github.yesidrobayo.shopledger.ui.media.ProvidePhotos
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * La lista de productos en la navegación: obtiene su ViewModel y resuelve abrir un producto o
 * crear uno. Como pestaña de Productos recibe [tabs], que van bajo su barra. Mientras la base abre, la lista se ve cargando; si la base va al rescate, la pantalla
 * no lee nada y muestra su estado vacío.
 */
@Composable
fun ProductListRoute(navigator: AppNavigator, tabs: (@Composable () -> Unit)? = null) {
    val model =
        rememberAppViewModel { dependencies ->
            ProductListViewModel(
                observeCatalog =
                    ObserveProductCatalogUseCase(
                        dependencies.ports.products,
                        dependencies.ports.units,
                    ),
                computation = dependencies.container.dispatchers.default,
                hasCodes =
                    dependencies.ports.products.observe(ArchiveFilter.ACTIVE)
                        .map { list -> list.any { it.details.barcode != null } }
                        .distinctUntilChanged(),
                choices = ProductListChoices(
                    ObserveBulkPriceChoicesUseCase(
                        dependencies.ports.products,
                        dependencies.ports.categories,
                        dependencies.ports.suppliers,
                        dependencies.ports.settings,
                    ),
                ),
            )
        }
    when (model) {
        ScreenModel.Opening ->
            ProductListScreen(uiState = ProductListUiState(), onAction = {}, tabs = tabs)

        ScreenModel.Unavailable -> PlaceholderRoute(AppNavKey.ProductList, navigator)

        is ScreenModel.Ready -> ProvidePhotos {
            val viewModel = model.viewModel
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val canScan by viewModel.canScan.collectAsStateWithLifecycle()
            // Un código leído abre su producto; uno desconocido ya se resolvió en el escáner.
            val scanned by navigator.scans.toTake.collectAsStateWithLifecycle()
            LaunchedEffect(scanned) {
                navigator.scans.take(ScanPurpose.PRODUCT_SEARCH).lastOrNull()?.productId?.let {
                    navigator.open(AppNavKey.ProductDetail(it))
                }
            }
            ProductListScreen(
                uiState = uiState,
                onAction = { action ->
                    when (action) {
                        is ProductListAction.OpenProduct ->
                            navigator.open(AppNavKey.ProductDetail(action.productId))

                        is ProductListAction.CreateProduct ->
                            navigator.open(AppNavKey.ProductEdit(prefillName = action.name))

                        ProductListAction.UpdatePrices -> navigator.open(
                            AppNavKey.BulkPriceUpdate(),
                        )

                        ProductListAction.OpenLabels -> navigator.open(AppNavKey.Labels())

                        is ProductListAction.QueryChange, ProductListAction.Retry,
                        ProductListAction.OpenFilters, ProductListAction.CloseFilters,
                        is ProductListAction.FilterCategory, is ProductListAction.FilterSupplier,
                        is ProductListAction.FilterArchived, is ProductListAction.SortBy,
                        ProductListAction.ClearFilters,
                        -> viewModel.onAction(action)
                    }
                },
                tabs = tabs,
                onScan =
                    if (canScan) {
                        { navigator.open(AppNavKey.Scanner(ScanPurpose.PRODUCT_SEARCH)) }
                    } else {
                        null
                    },
                floatingAction = {
                    TonalFab(
                        text = stringResource(R.string.product_new_title),
                        onClick = { navigator.open(AppNavKey.ProductEdit()) },
                    )
                },
                backupBanner = { RootBackupBanner(navigator::open) },
            )
        }
    }
}

/** Las categorías y los proveedores vigentes de los filtros, leídos con los del catálogo. */
private class ProductListChoices(private val choices: ObserveBulkPriceChoicesUseCase) :
    ProductFilterChoices {
    override fun categories(): Flow<List<Category>> = choices.categories()

    override fun suppliers(): Flow<List<Supplier>> = choices.suppliers()
}
