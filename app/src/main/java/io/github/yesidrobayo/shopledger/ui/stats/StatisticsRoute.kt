package io.github.yesidrobayo.shopledger.ui.stats

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yesidrobayo.shopledger.domain.usecase.customer.ObserveCustomersUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.money.ObserveOtherIncomeUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.domain.usecase.report.ObserveDailySeriesUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.report.ObservePeriodBreakdownUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.report.ObservePeriodComparisonUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.report.ObservePeriodReportUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.report.ObserveReplacementProfitUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.report.ObserveReportCalendarUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.report.ObserveSoldAndCollectedUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.tax.ObserveTaxEstimateUseCase
import io.github.yesidrobayo.shopledger.navigation.AppNavKey
import io.github.yesidrobayo.shopledger.navigation.AppNavigator
import io.github.yesidrobayo.shopledger.navigation.PlaceholderRoute
import io.github.yesidrobayo.shopledger.navigation.ScreenModel
import io.github.yesidrobayo.shopledger.navigation.rememberAppViewModel
import io.github.yesidrobayo.shopledger.ui.backup.RootBackupBanner
import kotlinx.coroutines.flow.map

/**
 * `Números` en la navegación. Cada vez que la pantalla vuelve a primer plano mira de nuevo qué día
 * es: así el período en curso pasa al nuevo a medianoche sin recargar nada a mano.
 */
@Composable
fun StatisticsRoute(navigator: AppNavigator) {
    val model =
        rememberAppViewModel { dependencies ->
            val ports = dependencies.ports
            val calendar =
                ObserveReportCalendarUseCase(
                    BusinessCalendarSource(ports.settings),
                    ports.periodReports,
                    dependencies.container.clock,
                )
            val report = ObservePeriodReportUseCase(ports.periodReports)
            val series = ObserveDailySeriesUseCase(ports.periodReports)
            val collection = ObserveSoldAndCollectedUseCase(
                ports.collectionReports,
                ObserveCustomersUseCase(ports.customers)().map { it.receivable },
            )
            val tax = ObserveTaxEstimateUseCase(ports.taxSettings, ports.taxReports)
            val comparison = ObservePeriodComparisonUseCase(ports.periodReports)
            val breakdown =
                ObservePeriodBreakdownUseCase(ports.periodReports, ports.periodBreakdowns)
            val replacement =
                ObserveReplacementProfitUseCase(ports.periodReports, ports.replacementReports)
            StatisticsViewModel(
                calendar = { checks -> calendar(checks) },
                lastActiveDate = ports.periodReports.observeLastActiveDate(),
                report = { report(it) },
                series = { collection.series(it, series(it)) },
                tax = { tax(it) },
                comparison = { period, today -> comparison(period, today) },
                breakdown = { period, calendar -> breakdown(period, calendar) },
                units = ports.units.observeAll(),
                otherIncome = { ObserveOtherIncomeUseCase(ports.settings, ports.moneyLedger)(it) },
                collection = { collection(it) },
                replacement = { period -> replacement(period).map { it.replacement } },
            )
        }
    when (model) {
        ScreenModel.Opening -> StatisticsScreen(StatisticsUiState.Loading, onAction = {})

        ScreenModel.Unavailable -> PlaceholderRoute(AppNavKey.Statistics, navigator)

        is ScreenModel.Ready -> {
            val viewModel = model.viewModel
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            LifecycleResumeEffect(viewModel) {
                viewModel.onReturnToForeground()
                onPauseOrDispose {}
            }
            StatisticsScreen(
                uiState = uiState,
                onAction = { action ->
                    when (action) {
                        StatisticsAction.NewSale -> navigator.open(AppNavKey.NewSale())

                        StatisticsAction.ChooseZone ->
                            navigator.open(AppNavKey.RegionalSettings())

                        StatisticsAction.OpenSummary -> navigator.open(AppNavKey.ManagementSummary)
                        StatisticsAction.OpenPriceIndex -> navigator.open(AppNavKey.PriceIndex)

                        is StatisticsAction.SelectKind,
                        is StatisticsAction.SelectRanking,
                        is StatisticsAction.ToggleVariants,
                        StatisticsAction.ChooseRange,
                        StatisticsAction.DismissRange,
                        is StatisticsAction.ShowRange,
                        StatisticsAction.Previous,
                        StatisticsAction.Next,
                        is StatisticsAction.SelectMetric,
                        is StatisticsAction.SelectDay,
                        StatisticsAction.ToggleList,
                        StatisticsAction.Retry,
                        -> viewModel.onAction(action)
                    }
                },
                backupBanner = { RootBackupBanner(navigator::open) },
                tools =
                    StatisticsTools(
                        analysis = { StatisticsAnalysis(it) },
                        comparison = { StatisticsComparison(it, navigator) },
                    ),
            )
        }
    }
}
