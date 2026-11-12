package io.github.yesidrobayo.shopledger.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationInfo
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import io.github.yesidrobayo.shopledger.domain.usecase.backup.BackupDestinationReader
import io.github.yesidrobayo.shopledger.domain.usecase.backup.ObserveBackupOverviewUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.home.ObserveHomeProfitUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.home.ObserveTodaySalesUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.domain.usecase.report.ObservePeriodComparisonUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.report.ObservePeriodReportUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.sale.SaleHistoryReader
import io.github.yesidrobayo.shopledger.domain.usecase.stock.ObserveInventoryUseCase
import io.github.yesidrobayo.shopledger.navigation.AppNavKey
import io.github.yesidrobayo.shopledger.navigation.AppNavigator
import io.github.yesidrobayo.shopledger.navigation.InventoryFilter
import io.github.yesidrobayo.shopledger.navigation.PlaceholderRoute
import io.github.yesidrobayo.shopledger.navigation.ScreenModel
import io.github.yesidrobayo.shopledger.navigation.TopLevelDestination
import io.github.yesidrobayo.shopledger.navigation.rememberAppViewModel
import io.github.yesidrobayo.shopledger.ui.safety.LocalPolicyPrompt
import kotlinx.coroutines.flow.map

/**
 * Inicio en la navegación. Cada vez que la pantalla vuelve a primer plano, mira de nuevo qué día
 * es: así las cifras de hoy reinician al día siguiente sin recargar nada a mano.
 */
@Composable
fun HomeRoute(navigator: AppNavigator) {
    val model =
        rememberAppViewModel { dependencies ->
            val ports = dependencies.ports
            val container = dependencies.container
            val todaySales =
                ObserveTodaySalesUseCase(
                    BusinessCalendarSource(ports.settings),
                    ports.salesReports,
                    container.clock,
                )
            HomeViewModel(
                todaySales = { checks -> todaySales(checks) },
                hasProducts = ports.catalogPresence.observeHasProducts(),
                history = SaleHistoryReader(ports.saleHistory, container.clock),
                computation = container.dispatchers.io,
                backupOverview = { refreshes ->
                    ObserveBackupOverviewUseCase(ports.backupStatus, container.clock)(refreshes)
                },
                providerLabel = {
                    val reader = BackupDestinationReader(container.data.backupDestination)
                    (reader.describe() as? BackupDestinationInfo.Slots)?.providerLabel
                },
                zones = BusinessCalendarSource(ports.settings).observe().map {
                    (it as? BusinessCalendarResult.Ready)?.calendar?.zone
                },
                inventory = ObserveInventoryUseCase(ports.products, ports.units)(),
                homeProfit = { checks ->
                    ObserveHomeProfitUseCase(
                        BusinessCalendarSource(ports.settings),
                        ObservePeriodReportUseCase(ports.periodReports),
                        container.clock,
                        ObservePeriodComparisonUseCase(ports.periodReports),
                    )(checks)
                },
            )
        }
    when (model) {
        ScreenModel.Opening -> HomeScreen(HomeUiState.Loading, onAction = {})

        ScreenModel.Unavailable -> PlaceholderRoute(AppNavKey.Home, navigator)

        is ScreenModel.Ready -> {
            val viewModel = model.viewModel
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val backup by viewModel.backup.collectAsStateWithLifecycle()
            val lowStock by viewModel.lowStock.collectAsStateWithLifecycle()
            val profit by viewModel.profit.collectAsStateWithLifecycle()
            val policy = LocalPolicyPrompt.current
            LifecycleResumeEffect(viewModel) {
                viewModel.onReturnToForeground()
                onPauseOrDispose {}
            }
            HomeScreen(
                backup = backup,
                lowStock = lowStock,
                profit = profit,
                uiState = uiState,
                onAction = { action ->
                    when (action) {
                        HomeAction.OpenSettings -> navigator.open(AppNavKey.Settings)

                        HomeAction.OpenBusiness -> navigator.open(AppNavKey.BusinessHub)

                        HomeAction.CreateFirstProduct -> navigator.open(AppNavKey.ProductEdit())

                        HomeAction.OpenAllSales -> navigator.selectTab(TopLevelDestination.RECORDS)

                        is HomeAction.OpenSale -> navigator.open(
                            AppNavKey.SaleDetail(action.saleId),
                        )

                        HomeAction.OpenBackup -> navigator.open(AppNavKey.Backup)

                        HomeAction.ChooseDestination ->
                            navigator.open(AppNavKey.BackupDestinationChoice())

                        HomeAction.ReviewPolicy -> policy.review()

                        HomeAction.OpenPurchase -> navigator.open(AppNavKey.Purchase())

                        HomeAction.OpenExpense ->
                            navigator.open(AppNavKey.Expenses(startNew = true))

                        is HomeAction.OpenLowStock ->
                            navigator.open(
                                AppNavKey.Inventory(
                                    if (action.negativeOnly) {
                                        InventoryFilter.NEGATIVE_STOCK
                                    } else {
                                        InventoryFilter.LOW_STOCK
                                    },
                                ),
                            )

                        is HomeAction.OpenProduct ->
                            navigator.open(AppNavKey.ProductDetail(action.productId))

                        HomeAction.Retry -> viewModel.onAction(action)
                    }
                },
            )
        }
    }
}
