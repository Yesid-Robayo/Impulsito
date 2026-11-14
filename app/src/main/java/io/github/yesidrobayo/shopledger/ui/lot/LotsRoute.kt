package io.github.yesidrobayo.shopledger.ui.lot

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.stock.Location
import io.github.yesidrobayo.shopledger.domain.model.stock.LotsOverview
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementRecord
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.domain.usecase.settings.SetExpiryNoticeDaysResult
import io.github.yesidrobayo.shopledger.domain.usecase.settings.SetExpiryNoticeDaysUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.stock.CorrectLotResult
import io.github.yesidrobayo.shopledger.domain.usecase.stock.CorrectLotUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.stock.ObserveLotMovementsUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.stock.ObserveLotsUseCase
import io.github.yesidrobayo.shopledger.navigation.AppNavKey
import io.github.yesidrobayo.shopledger.navigation.AppNavigator
import io.github.yesidrobayo.shopledger.navigation.PlaceholderRoute
import io.github.yesidrobayo.shopledger.navigation.ScreenDependencies
import io.github.yesidrobayo.shopledger.navigation.ScreenModel
import io.github.yesidrobayo.shopledger.navigation.rememberAppViewModel
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow

/**
 * La pantalla de lotes en la navegación. `Dar salida` abre el ajuste con el motivo `Vencido` y el
 * lote ya elegidos; el lote sin asignar lleva a la compra o al ajuste del producto. Mientras la
 * base abre, o si no abre, la pantalla de reserva de la navegación.
 */
@Composable
fun LotsRoute(key: AppNavKey.Lots, navigator: AppNavigator) {
    val model =
        rememberAppViewModel { dependencies ->
            LotsViewModel(
                LotsUseCases.of(dependencies),
                dependencies.container.dispatchers.default,
                productId = key.productId,
                openLotId = key.lotId,
            )
        }
    when (model) {
        ScreenModel.Opening -> LotsScreen(LotsUiState(), onAction = {})

        ScreenModel.Unavailable -> PlaceholderRoute(key, navigator)

        is ScreenModel.Ready -> {
            val viewModel = model.viewModel
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            LotsScreen(uiState, onAction = { action ->
                viewModel.onAction(action)
                // Solo estas salen de la pantalla; las demás las resuelve el ViewModel.
                when {
                    action == LotsAction.Back -> navigator.back()

                    action == LotsAction.OpenProducts -> navigator.open(AppNavKey.ProductList)

                    action is LotsAction.TakeOut -> navigator.open(
                        AppNavKey.StockAdjustment(
                            action.productId,
                            lotId = action.lotId,
                            expiredOut = true,
                        ),
                    )

                    action is LotsAction.RegisterPurchase ->
                        navigator.open(AppNavKey.Purchase(prefillProductId = action.productId))

                    action is LotsAction.AdjustStock ->
                        navigator.open(AppNavKey.StockAdjustment(action.productId))
                }
            })
        }
    }
}

/** Las operaciones de la pantalla sobre los casos de uso de lotes. */
class LotsUseCases(
    private val observeLots: ObserveLotsUseCase,
    private val observeMovements: ObserveLotMovementsUseCase,
    private val correctLot: CorrectLotUseCase,
    private val setNotice: SetExpiryNoticeDaysUseCase,
    private val unitsSource: () -> Flow<List<MeasureUnit>>,
    private val locationsSource: () -> Flow<List<Location>>,
    private val calendarSource: BusinessCalendarSource,
    private val clock: () -> Instant,
) : LotsOperations {
    override fun lots(productId: Long?): Flow<LotsOverview> = observeLots(productId)

    override fun units(): Flow<List<MeasureUnit>> = unitsSource()

    override fun locations(): Flow<List<Location>> = locationsSource()

    override fun calendar(): Flow<BusinessCalendarResult> = calendarSource.observe()

    override fun now(): Instant = clock()

    override fun movements(productId: Long, lotId: Long): Flow<List<StockMovementRecord>> =
        observeMovements(productId, lotId)

    override suspend fun correct(
        lotId: Long,
        code: String,
        expiryDate: BusinessDate?,
    ): CorrectLotResult = correctLot(lotId, code, expiryDate)

    override suspend fun setNoticeDays(days: Int): SetExpiryNoticeDaysResult = setNotice(days)

    companion object {
        /** Arma las operaciones con los puertos de la base abierta. */
        fun of(dependencies: ScreenDependencies): LotsUseCases {
            val ports = dependencies.ports
            val clock = dependencies.container.clock
            val calendar = BusinessCalendarSource(ports.settings)
            return LotsUseCases(
                observeLots = ObserveLotsUseCase(
                    ports.stockLots,
                    ports.products,
                    calendar,
                    ports.settings,
                    clock,
                ),
                observeMovements = ObserveLotMovementsUseCase(ports.stockLedger),
                correctLot = CorrectLotUseCase(
                    ports.transactions,
                    ports.stockLots,
                    ports.products,
                ),
                setNotice = SetExpiryNoticeDaysUseCase(ports.transactions, ports.settings, clock),
                unitsSource = { ports.units.observeAll() },
                // Solo las vigentes: la parte de una archivada no se nombra.
                locationsSource = { ports.locations.observe(ArchiveFilter.ACTIVE) },
                calendarSource = calendar,
                clock = { clock.now() },
            )
        }
    }
}
