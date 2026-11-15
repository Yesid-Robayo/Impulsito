package io.github.yesidrobayo.shopledger.ui.product

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.model.catalog.Category
import io.github.yesidrobayo.shopledger.domain.model.catalog.Supplier
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ObserveBulkPriceChoicesUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ObserveProductCatalogUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ObserveTagSearchIndexUseCase
import io.github.yesidrobayo.shopledger.navigation.AppNavKey
import io.github.yesidrobayo.shopledger.navigation.AppNavigator
import io.github.yesidrobayo.shopledger.navigation.PlaceholderRoute
import io.github.yesidrobayo.shopledger.navigation.ScanPurpose
import io.github.yesidrobayo.shopledger.navigation.ScreenModel
import io.github.yesidrobayo.shopledger.navigation.rememberAppViewModel
import io.github.yesidrobayo.shopledger.ui.backup.RootBackupBanner
import io.github.yesidrobayo.shopledger.ui.components.EmptyState
import io.github.yesidrobayo.shopledger.ui.components.TonalFab
import io.github.yesidrobayo.shopledger.ui.media.ProvidePhotos
import io.github.yesidrobayo.shopledger.ui.payment.ListDetailPanes
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * La lista de productos en la navegación: obtiene su ViewModel y resuelve abrir un producto o
 * crear uno. En una ventana `Expanded`, el producto elegido se abre en el panel derecho en vez de
 * encima, y atrás cierra primero ese panel. Como pestaña de Productos recibe [tabs], que van bajo
 * su barra. Mientras la base abre, la lista se ve cargando; si la base va al rescate, la pantalla
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
                tagIndex = ObserveTagSearchIndexUseCase(dependencies.ports.tags)(),
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
            var selectedId by rememberSaveable { mutableStateOf<Long?>(null) }
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val expanded = maxWidth >= EXPANDED_WIDTH
                BackHandler(enabled = expanded && selectedId != null) { selectedId = null }
                val list: @Composable (Modifier) -> Unit = { paneModifier ->
                    ProductList(
                        uiState = uiState,
                        viewModel = viewModel,
                        navigator = navigator,
                        canScan = canScan,
                        tabs = tabs,
                        modifier = paneModifier,
                        onOpen = { productId ->
                            if (expanded) {
                                selectedId = productId
                            } else {
                                navigator.open(AppNavKey.ProductDetail(productId))
                            }
                        },
                    )
                }
                val id = selectedId
                if (!expanded) {
                    list(Modifier)
                } else {
                    ListDetailPanes(
                        list = list,
                        detail = {
                            if (id == null) {
                                EmptyState(
                                    icon = R.drawable.ic_inventory_2,
                                    title = stringResource(R.string.nav_products),
                                )
                            } else {
                                // Cada producto elegido tiene su propio detalle y su estado.
                                key(id) {
                                    ProductDetailRoute(
                                        key = AppNavKey.ProductDetail(id),
                                        navigator = navigator,
                                        onBack = { selectedId = null },
                                    )
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

/** Desde este ancho la ventana es `Expanded`: lista a la izquierda y producto a la derecha. */
private val EXPANDED_WIDTH = 840.dp

/** La lista con sus acciones: [onOpen] abre un producto, encima o en el panel derecho. */
@Composable
private fun ProductList(
    uiState: ProductListUiState,
    viewModel: ProductListViewModel,
    navigator: AppNavigator,
    canScan: Boolean,
    tabs: (@Composable () -> Unit)?,
    modifier: Modifier,
    onOpen: (Long) -> Unit,
) {
    ProductListScreen(
        uiState = uiState,
        modifier = modifier,
        onAction = { action ->
            when (action) {
                is ProductListAction.OpenProduct -> onOpen(action.productId)

                is ProductListAction.CreateProduct ->
                    navigator.open(AppNavKey.ProductEdit(prefillName = action.name))

                ProductListAction.UpdatePrices -> navigator.open(
                    AppNavKey.BulkPriceUpdate(),
                )

                ProductListAction.OpenLabels -> navigator.open(AppNavKey.Labels())

                ProductListAction.OpenImport -> navigator.open(AppNavKey.ImportData)

                is ProductListAction.QueryChange, ProductListAction.Retry,
                ProductListAction.OpenFilters, ProductListAction.CloseFilters,
                is ProductListAction.FilterCategory, is ProductListAction.FilterSupplier,
                is ProductListAction.FilterArchived, is ProductListAction.SortBy,
                ProductListAction.ClearFilters, is ProductListAction.FilterInputs,
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

/** Las categorías y los proveedores vigentes de los filtros, leídos con los del catálogo. */
private class ProductListChoices(private val choices: ObserveBulkPriceChoicesUseCase) :
    ProductFilterChoices {
    override fun categories(): Flow<List<Category>> = choices.categories()

    override fun suppliers(): Flow<List<Supplier>> = choices.suppliers()
}
