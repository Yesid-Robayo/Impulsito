package io.github.yesidrobayo.shopledger.ui.stock

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ObservePricingContextUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.pricing.DismissPriceReviewUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.pricing.ObservePriceReviewUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.domain.usecase.stock.ObserveExpiringLotsUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.stock.ObserveInventoryUseCase
import io.github.yesidrobayo.shopledger.navigation.AppNavKey
import io.github.yesidrobayo.shopledger.navigation.AppNavigator
import io.github.yesidrobayo.shopledger.navigation.InventoryFilter
import io.github.yesidrobayo.shopledger.navigation.PlaceholderRoute
import io.github.yesidrobayo.shopledger.navigation.ScanPurpose
import io.github.yesidrobayo.shopledger.navigation.ScreenModel
import io.github.yesidrobayo.shopledger.navigation.rememberAppViewModel
import io.github.yesidrobayo.shopledger.ui.backup.RootBackupBanner
import io.github.yesidrobayo.shopledger.ui.components.TopBarNavigation
import io.github.yesidrobayo.shopledger.ui.permission.NotificationPermissionPrompt
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * El inventario en la navegación. Como pestaña de Productos recibe las pestañas en [tabs] y va sin
 * flecha; abierto desde otra pantalla, como el aviso de stock negativo de Inicio, va con flecha.
 * Resuelve abrir el producto, reponerlo con una compra, ajustar su stock y crear el primero.
 *
 * El escáner de la búsqueda es el mismo de la lista de productos: un código conocido abre su
 * producto, y uno desconocido se resuelve en el escáner, que ofrece crearlo o asignarlo.
 */
@Composable
fun InventoryRoute(
    filter: InventoryFilter,
    navigator: AppNavigator,
    asTab: Boolean = false,
    tabs: @Composable () -> Unit = {},
) {
    val initialChip = chipOf(filter)
    val model =
        rememberAppViewModel { dependencies ->
            val ports = dependencies.ports
            val observe = ObserveInventoryUseCase(ports.products, ports.units, ports.locationStock)
            val review =
                ObservePriceReviewUseCase(
                    products = ports.products,
                    pricing =
                        ObservePricingContextUseCase(
                            ports.settings,
                            ports.categories,
                            ports.taxRates,
                        ),
                    calendar = BusinessCalendarSource(ports.settings),
                    settings = ports.settings,
                    clock = dependencies.container.clock,
                )
            val expiring =
                ObserveExpiringLotsUseCase(
                    ports.stockLots,
                    BusinessCalendarSource(ports.settings),
                    ports.settings,
                    dependencies.container.clock,
                )
            val dismiss =
                DismissPriceReviewUseCase(
                    ports.transactions,
                    ports.products,
                    dependencies.container.clock,
                )
            InventoryViewModel(
                observe = { observe() },
                computation = dependencies.container.dispatchers.default,
                initialChip = initialChip,
                review = { review() },
                dismissReview = { dismiss(it) },
                locations = { ports.locations.observe(ArchiveFilter.ACTIVE) },
                observeAt = { observe(it) },
                expiring = { expiring() },
                hasCodes =
                    ports.products.observe(ArchiveFilter.ACTIVE)
                        .map { list -> list.any { it.details.barcode != null } }
                        .distinctUntilChanged(),
            )
        }
    val navigation = if (asTab) TopBarNavigation.NONE else TopBarNavigation.BACK
    val context = LocalContext.current
    // Se mira al volver a la pantalla: la persona pudo cambiarlo en los ajustes del teléfono.
    var notificationsBlocked by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        notificationsBlocked = NotificationPermissionPrompt.stockAlertsBlocked(context)
        onPauseOrDispose {}
    }
    when (model) {
        ScreenModel.Opening ->
            InventoryScreen(InventoryUiState(initialChip), {}, navigation = navigation, tabs = tabs)

        ScreenModel.Unavailable -> PlaceholderRoute(AppNavKey.Inventory(filter), navigator)

        is ScreenModel.Ready -> {
            val viewModel = model.viewModel
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val canScan by viewModel.canScan.collectAsStateWithLifecycle()
            // Un código leído abre su producto, como en la lista de productos.
            val scanned by navigator.scans.toTake.collectAsStateWithLifecycle()
            LaunchedEffect(scanned) {
                navigator.scans.take(ScanPurpose.PRODUCT_SEARCH).lastOrNull()?.productId?.let {
                    navigator.open(AppNavKey.ProductDetail(it))
                }
            }
            InventoryScreen(
                uiState = uiState,
                onAction = { action ->
                    when (action) {
                        is InventoryAction.OpenProduct ->
                            navigator.open(AppNavKey.ProductDetail(action.productId))

                        is InventoryAction.Restock ->
                            navigator.open(AppNavKey.Purchase(prefillProductId = action.productId))

                        is InventoryAction.Adjust ->
                            navigator.open(AppNavKey.StockAdjustment(action.productId))

                        is InventoryAction.ChangePrice ->
                            navigator.open(
                                AppNavKey.ProductDetail(action.productId, openPriceSheet = true),
                            )

                        InventoryAction.ReviewAll ->
                            navigator.open(AppNavKey.BulkPriceUpdate(review = true))

                        InventoryAction.CreateProduct -> navigator.open(AppNavKey.ProductEdit())

                        InventoryAction.OpenNotificationSettings ->
                            NotificationPermissionPrompt.openSettings(context)

                        InventoryAction.ManageLocations -> navigator.open(AppNavKey.Locations)

                        InventoryAction.OpenReorder -> navigator.open(AppNavKey.Replenishment())

                        is InventoryAction.ChipChanged, is InventoryAction.QueryChange,
                        InventoryAction.Retry,
                        is InventoryAction.LocationChanged,
                        is InventoryAction.DismissReview, InventoryAction.MessageShown,
                        -> viewModel.onAction(action)
                    }
                },
                navigation = navigation,
                onNavigate = { navigator.back() },
                tabs = tabs,
                notificationsBlocked = notificationsBlocked,
                backupBanner = { if (asTab) RootBackupBanner(navigator::open) },
                onScan =
                    if (canScan) {
                        { navigator.open(AppNavKey.Scanner(ScanPurpose.PRODUCT_SEARCH)) }
                    } else {
                        null
                    },
            )
        }
    }
}

/** El chip con que se abre cada filtro de la navegación. */
internal fun chipOf(filter: InventoryFilter): InventoryChip = when (filter) {
    InventoryFilter.LOW_STOCK -> InventoryChip.LOW_STOCK
    InventoryFilter.EXPIRING -> InventoryChip.EXPIRING
    InventoryFilter.REVIEW -> InventoryChip.REVIEW
    InventoryFilter.NEGATIVE_STOCK -> InventoryChip.NEGATIVE_STOCK
    InventoryFilter.ALL -> InventoryChip.ALL
}
