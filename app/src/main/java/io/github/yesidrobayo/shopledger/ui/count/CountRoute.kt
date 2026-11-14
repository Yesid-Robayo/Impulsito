package io.github.yesidrobayo.shopledger.ui.count

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalResources
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.Category
import io.github.yesidrobayo.shopledger.domain.model.stock.OpenCountRequest
import io.github.yesidrobayo.shopledger.domain.rule.stock.LocationRules
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.domain.usecase.region.CurrencyLock
import io.github.yesidrobayo.shopledger.domain.usecase.stock.ApplyCountSessionUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.stock.CancelCountSessionUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.stock.CountOverview
import io.github.yesidrobayo.shopledger.domain.usecase.stock.CountReview
import io.github.yesidrobayo.shopledger.domain.usecase.stock.CountSessionView
import io.github.yesidrobayo.shopledger.domain.usecase.stock.FinishCountResult
import io.github.yesidrobayo.shopledger.domain.usecase.stock.ObserveCountOverviewUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.stock.ObserveCountSessionUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.stock.OpenCountResult
import io.github.yesidrobayo.shopledger.domain.usecase.stock.OpenCountSessionUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.stock.RecordCountResult
import io.github.yesidrobayo.shopledger.domain.usecase.stock.RecordCountUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.stock.ReviewCountSessionUseCase
import io.github.yesidrobayo.shopledger.navigation.AppNavKey
import io.github.yesidrobayo.shopledger.navigation.AppNavigator
import io.github.yesidrobayo.shopledger.navigation.LocalAppMessages
import io.github.yesidrobayo.shopledger.navigation.PlaceholderRoute
import io.github.yesidrobayo.shopledger.navigation.ScanPurpose
import io.github.yesidrobayo.shopledger.navigation.ScreenDependencies
import io.github.yesidrobayo.shopledger.navigation.ScreenModel
import io.github.yesidrobayo.shopledger.navigation.rememberAppViewModel
import io.github.yesidrobayo.shopledger.ui.components.AppSnackbarVisuals
import io.github.yesidrobayo.shopledger.ui.components.SnackbarVariant
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * El conteo físico en la navegación: sin sesión, su inicio; con una, esa sesión. Al aplicar o
 * cancelar vuelve con su aviso. Atrás en la revisión vuelve a contar.
 */
@Composable
fun StockCountRoute(key: AppNavKey.StockCount, navigator: AppNavigator) {
    val sessionId = key.sessionId
    if (sessionId == null) CountHomeRoute(key, navigator) else CountSessionRoute(key, navigator)
}

@Composable
private fun CountHomeRoute(key: AppNavKey.StockCount, navigator: AppNavigator) {
    val model = rememberAppViewModel { dependencies ->
        CountHomeViewModel(CountHomeUseCases(dependencies))
    }
    when (model) {
        ScreenModel.Opening -> CountHomeScreen(CountHomeUiState(), onAction = {})

        ScreenModel.Unavailable -> PlaceholderRoute(key, navigator)

        is ScreenModel.Ready -> {
            val viewModel = model.viewModel
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            LaunchedEffect(uiState.openedId) {
                val opened = uiState.openedId ?: return@LaunchedEffect
                viewModel.onAction(CountHomeAction.OpenedShown)
                navigator.open(AppNavKey.StockCount(opened))
            }
            CountHomeScreen(
                uiState = uiState,
                onAction = { action ->
                    viewModel.onAction(action)
                    // Solo estas salen de la pantalla; las demás las resuelve el ViewModel.
                    when {
                        action == CountHomeAction.Back -> navigator.back()

                        action is CountHomeAction.OpenSession ->
                            navigator.open(AppNavKey.StockCount(action.sessionId))

                        action == CountHomeAction.ChooseZone ->
                            navigator.open(AppNavKey.RegionalSettings())

                        action == CountHomeAction.CreateProduct ->
                            navigator.open(AppNavKey.ProductEdit())
                    }
                },
            )
        }
    }
}

@Composable
private fun CountSessionRoute(key: AppNavKey.StockCount, navigator: AppNavigator) {
    val sessionId = requireNotNull(key.sessionId) { "La sesión se abre con su id" }
    val formats = LocalAppFormats.current
    val model = rememberAppViewModel { dependencies ->
        CountSessionViewModel(
            sessionId = sessionId,
            operations = CountSessionUseCases(dependencies),
            quantitySymbols = formats.quantity.inputSymbols,
        )
    }
    when (model) {
        ScreenModel.Opening -> CountSessionScreen(CountSessionUiState(), onAction = {})

        ScreenModel.Unavailable -> PlaceholderRoute(key, navigator)

        is ScreenModel.Ready -> {
            val viewModel = model.viewModel
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val messages = LocalAppMessages.current
            val resources = LocalResources.current
            LaunchedEffect(uiState.finished) {
                val finished = uiState.finished ?: return@LaunchedEffect
                if (finished is CountFinished.Applied) {
                    val text = resources.getQuantityString(
                        R.plurals.count_applied,
                        finished.adjustments,
                        finished.adjustments,
                    )
                    messages.show(AppSnackbarVisuals(text, SnackbarVariant.SUCCESS))
                }
                viewModel.onAction(CountSessionAction.FinishedShown)
                navigator.back()
            }
            // Lo que trajo el escáner llega al volver a verse: cada producto, con cuántas lecturas.
            LaunchedEffect(viewModel) {
                navigator.scans.toTake.collect { pending ->
                    if (pending.none { it.purpose == ScanPurpose.STOCK_COUNT }) return@collect
                    navigator.scans.take(ScanPurpose.STOCK_COUNT)
                        .mapNotNull { it.productId }
                        .groupingBy { it }
                        .eachCount()
                        .forEach { (productId, times) ->
                            viewModel.onAction(CountSessionAction.Scanned(productId, times))
                        }
                }
            }
            BackHandler(enabled = uiState.reviewing && !uiState.readOnly) {
                viewModel.onAction(CountSessionAction.Back)
            }
            CountSessionScreen(
                uiState = uiState,
                onAction = { action ->
                    viewModel.onAction(action)
                    when {
                        action == CountSessionAction.Back &&
                            (!uiState.reviewing || uiState.readOnly) -> navigator.back()

                        action == CountSessionAction.ChooseZone ->
                            navigator.open(AppNavKey.RegionalSettings())

                        action == CountSessionAction.Scan ->
                            navigator.open(AppNavKey.Scanner(ScanPurpose.STOCK_COUNT))
                    }
                },
            )
        }
    }
}

/** El inicio del conteo sobre los casos de uso de la base abierta. */
private class CountHomeUseCases(dependencies: ScreenDependencies) : CountHomeOperations {
    private val ports = dependencies.ports
    private val overviewUseCase =
        ObserveCountOverviewUseCase(ports.countSessions, ports.locations, ports.categories)
    private val openUseCase = OpenCountSessionUseCase(
        ports.transactions,
        ports.countSessions,
        ports.products,
        ports.categories,
        ports.locations,
        ports.locationStock,
        BusinessCalendarSource(ports.settings),
        dependencies.container.clock,
        ports.stockLots,
    )

    override fun overview(): Flow<CountOverview> = overviewUseCase()

    override fun categories(): Flow<List<Category>> = ports.categories.observeAll()

    override suspend fun open(request: OpenCountRequest): OpenCountResult = openUseCase(request)
}

/** Una sesión de conteo sobre los casos de uso de la base abierta. */
private class CountSessionUseCases(dependencies: ScreenDependencies) : CountSessionOperations {
    private val ports = dependencies.ports
    private val clock = dependencies.container.clock
    private val observeUseCase = ObserveCountSessionUseCase(
        ports.countSessions,
        ports.products,
        ports.units,
        ports.locations,
        ports.categories,
        ports.stockLots,
    )
    private val recordUseCase =
        RecordCountUseCase(ports.transactions, ports.countSessions, ports.products, ports.units)
    private val reviewUseCase = ReviewCountSessionUseCase(ports.countSessions)
    private val applyUseCase = ApplyCountSessionUseCase(
        ports.transactions,
        ports.countSessions,
        ports.products,
        ports.stockLedger,
        ports.lowStockAlerts,
        BusinessCalendarSource(ports.settings),
        CurrencyLock(ports.settings),
        clock,
        ports.stockLots,
    )
    private val cancelUseCase =
        CancelCountSessionUseCase(ports.transactions, ports.countSessions, clock)

    override fun session(sessionId: Long): Flow<CountSessionView?> = observeUseCase(sessionId)

    override fun showsLocations(): Flow<Boolean> = ports.locations.observe(ArchiveFilter.ACTIVE)
        .map { LocationRules.showsLocations(it.size) }
        .distinctUntilChanged()

    override suspend fun record(
        sessionId: Long,
        lineId: Long,
        counted: QuantityMilli?,
    ): RecordCountResult = recordUseCase(sessionId, lineId, counted)

    override suspend fun review(view: CountSessionView): CountReview = reviewUseCase(view)

    override suspend fun apply(sessionId: Long): FinishCountResult = applyUseCase(sessionId)

    override suspend fun cancel(sessionId: Long): FinishCountResult = cancelUseCase(sessionId)
}
