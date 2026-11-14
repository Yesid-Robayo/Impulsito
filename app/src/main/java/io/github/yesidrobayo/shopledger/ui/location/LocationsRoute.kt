package io.github.yesidrobayo.shopledger.ui.location

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yesidrobayo.shopledger.domain.model.VoidReason
import io.github.yesidrobayo.shopledger.domain.model.stock.LocationDraft
import io.github.yesidrobayo.shopledger.domain.model.stock.LocationsOverview
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.domain.usecase.stock.ArchiveLocationUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.stock.InventoryOverview
import io.github.yesidrobayo.shopledger.domain.usecase.stock.LocationLifecycleResult
import io.github.yesidrobayo.shopledger.domain.usecase.stock.ObserveInventoryUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.stock.ObserveLocationsUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.stock.ObserveStockTransfersUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.stock.ReactivateLocationUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.stock.ReadStockTransferUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.stock.SaveLocationResult
import io.github.yesidrobayo.shopledger.domain.usecase.stock.SaveLocationUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.stock.StockTransferDetail
import io.github.yesidrobayo.shopledger.domain.usecase.stock.StockTransfersRead
import io.github.yesidrobayo.shopledger.domain.usecase.stock.VoidStockTransferResult
import io.github.yesidrobayo.shopledger.domain.usecase.stock.VoidStockTransferUseCase
import io.github.yesidrobayo.shopledger.navigation.AppNavKey
import io.github.yesidrobayo.shopledger.navigation.AppNavigator
import io.github.yesidrobayo.shopledger.navigation.PlaceholderRoute
import io.github.yesidrobayo.shopledger.navigation.ScreenDependencies
import io.github.yesidrobayo.shopledger.navigation.ScreenModel
import io.github.yesidrobayo.shopledger.navigation.rememberAppViewModel
import kotlinx.coroutines.flow.Flow

/**
 * La pantalla de ubicaciones y transferencias en la navegación. `Nueva transferencia` abre su
 * formulario, desde la ubicación abierta si la hay. Mientras la base abre, o si no abre, la
 * pantalla de reserva de la navegación.
 */
@Composable
fun LocationsRoute(navigator: AppNavigator) {
    val model =
        rememberAppViewModel { dependencies ->
            LocationsViewModel(LocationsUseCases.of(dependencies))
        }
    when (model) {
        ScreenModel.Opening -> LocationsScreen(LocationsUiState(), onAction = {})

        ScreenModel.Unavailable -> PlaceholderRoute(AppNavKey.Locations, navigator)

        is ScreenModel.Ready -> {
            val viewModel = model.viewModel
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            LocationsScreen(
                uiState = uiState,
                onAction = { action ->
                    viewModel.onAction(action)
                    // Solo estas salen de la pantalla; las demás las resuelve el ViewModel.
                    when {
                        action == LocationsAction.Back -> navigator.back()

                        action is LocationsAction.NewTransfer ->
                            navigator.open(AppNavKey.NewStockTransfer(action.fromLocationId))

                        action == LocationsAction.ChooseZone ->
                            navigator.open(AppNavKey.RegionalSettings())
                    }
                },
            )
        }
    }
}

/** Las operaciones de la pantalla sobre los casos de uso de ubicaciones y transferencias. */
class LocationsUseCases(
    private val observeLocations: ObserveLocationsUseCase,
    private val observeInventory: ObserveInventoryUseCase,
    private val observeTransfers: ObserveStockTransfersUseCase,
    private val readTransferUseCase: ReadStockTransferUseCase,
    private val saveUseCase: SaveLocationUseCase,
    private val archiveUseCase: ArchiveLocationUseCase,
    private val reactivateUseCase: ReactivateLocationUseCase,
    private val voidUseCase: VoidStockTransferUseCase,
) : LocationsOperations {
    override fun locations(): Flow<LocationsOverview> = observeLocations()

    override fun stockAt(locationId: Long): Flow<InventoryOverview> = observeInventory(locationId)

    override fun transfers(days: Int): Flow<StockTransfersRead> = observeTransfers(days)

    override suspend fun readTransfer(transferId: Long): StockTransferDetail? =
        readTransferUseCase(transferId)

    override suspend fun saveLocation(editingId: Long?, draft: LocationDraft): SaveLocationResult =
        saveUseCase(editingId, draft)

    override suspend fun archive(locationId: Long): LocationLifecycleResult =
        archiveUseCase(locationId)

    override suspend fun reactivate(locationId: Long): LocationLifecycleResult =
        reactivateUseCase(locationId)

    // Anular una transferencia es corregir un registro errado: no hay otro motivo que elegir.
    override suspend fun voidTransfer(transferId: Long): VoidStockTransferResult =
        voidUseCase(transferId, VoidReason.ENTRY_ERROR)

    companion object {
        /** Arma las operaciones con los puertos de la base abierta. */
        fun of(dependencies: ScreenDependencies): LocationsUseCases {
            val ports = dependencies.ports
            val clock = dependencies.container.clock
            val tx = ports.transactions
            val calendar = BusinessCalendarSource(ports.settings)
            return LocationsUseCases(
                observeLocations = ObserveLocationsUseCase(ports.locations, ports.locationStock),
                observeInventory =
                    ObserveInventoryUseCase(ports.products, ports.units, ports.locationStock),
                observeTransfers = ObserveStockTransfersUseCase(
                    ports.stockTransfers,
                    ports.locations,
                    calendar,
                    clock,
                ),
                readTransferUseCase = ReadStockTransferUseCase(
                    ports.stockTransfers,
                    ports.products,
                    ports.units,
                    ports.locationStock,
                    ports.stockLots,
                ),
                saveUseCase = SaveLocationUseCase(tx, ports.locations, clock),
                archiveUseCase =
                    ArchiveLocationUseCase(tx, ports.locations, ports.locationStock, clock),
                reactivateUseCase = ReactivateLocationUseCase(tx, ports.locations, clock),
                voidUseCase = VoidStockTransferUseCase(
                    tx,
                    ports.products,
                    ports.stockTransfers,
                    ports.stockLedger,
                    calendar,
                    clock,
                    ports.stockLots,
                ),
            )
        }
    }
}
