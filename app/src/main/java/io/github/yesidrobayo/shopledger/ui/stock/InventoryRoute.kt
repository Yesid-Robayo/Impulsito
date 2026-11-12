package io.github.yesidrobayo.shopledger.ui.stock

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yesidrobayo.shopledger.domain.usecase.stock.ObserveInventoryUseCase
import io.github.yesidrobayo.shopledger.navigation.AppNavKey
import io.github.yesidrobayo.shopledger.navigation.AppNavigator
import io.github.yesidrobayo.shopledger.navigation.InventoryFilter
import io.github.yesidrobayo.shopledger.navigation.PlaceholderRoute
import io.github.yesidrobayo.shopledger.navigation.ScreenModel
import io.github.yesidrobayo.shopledger.navigation.rememberAppViewModel
import io.github.yesidrobayo.shopledger.ui.components.TopBarNavigation
import io.github.yesidrobayo.shopledger.ui.permission.NotificationPermissionPrompt

/**
 * El inventario en la navegación. Como pestaña de Productos recibe las pestañas en [tabs] y va sin
 * flecha; abierto desde otra pantalla, como el aviso de stock negativo de Inicio, va con flecha.
 * Resuelve abrir el producto, reponerlo con una compra, ajustar su stock y crear el primero.
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
            val observe =
                ObserveInventoryUseCase(dependencies.ports.products, dependencies.ports.units)
            InventoryViewModel(
                observe = { observe() },
                computation = dependencies.container.dispatchers.default,
                initialChip = initialChip,
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

                        InventoryAction.CreateProduct -> navigator.open(AppNavKey.ProductEdit())

                        InventoryAction.OpenNotificationSettings ->
                            NotificationPermissionPrompt.openSettings(context)

                        is InventoryAction.ChipChanged, InventoryAction.Retry ->
                            viewModel.onAction(action)
                    }
                },
                navigation = navigation,
                onNavigate = { navigator.back() },
                tabs = tabs,
                notificationsBlocked = notificationsBlocked,
            )
        }
    }
}

/** El chip con que se abre cada filtro de la navegación. Los que aún no existen, lo urgente. */
internal fun chipOf(filter: InventoryFilter): InventoryChip = when (filter) {
    InventoryFilter.LOW_STOCK, InventoryFilter.REVIEW, InventoryFilter.EXPIRING ->
        InventoryChip.LOW_STOCK

    InventoryFilter.NEGATIVE_STOCK -> InventoryChip.NEGATIVE_STOCK

    InventoryFilter.ALL -> InventoryChip.ALL
}
