package io.github.yesidrobayo.shopledger.ui.reorder

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.Scale
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.domain.usecase.stock.ObserveReorderPlanUseCase
import io.github.yesidrobayo.shopledger.navigation.AppNavKey
import io.github.yesidrobayo.shopledger.navigation.AppNavigator
import io.github.yesidrobayo.shopledger.navigation.PlaceholderRoute
import io.github.yesidrobayo.shopledger.navigation.ScreenModel
import io.github.yesidrobayo.shopledger.navigation.rememberAppViewModelWithState
import io.github.yesidrobayo.shopledger.ui.components.UnitLabel
import io.github.yesidrobayo.shopledger.ui.components.quantityText
import io.github.yesidrobayo.shopledger.ui.components.unitLabel
import io.github.yesidrobayo.shopledger.ui.sale.shareIntent

/**
 * La reposición sugerida en la navegación. `Crear compra` abre la compra nueva con el proveedor y
 * las líneas del grupo, y `Compartir lista` abre el selector del sistema con productos y
 * cantidades, sin costos: la persona elige con qué app enviarla, y nada sale solo.
 */
@Composable
fun ReorderRoute(key: AppNavKey.Replenishment, navigator: AppNavigator) {
    val model =
        rememberAppViewModelWithState { dependencies, savedState ->
            val ports = dependencies.ports
            val container = dependencies.container
            val observe = ObserveReorderPlanUseCase(
                products = ports.products,
                suppliers = ports.suppliers,
                categories = ports.categories,
                units = ports.units,
                productUnits = ports.productUnits,
                outflow = ports.saleOutflow,
                locationStock = ports.locationStock,
                calendar = BusinessCalendarSource(ports.settings),
                clock = container.clock,
            )
            ReorderViewModel(
                planSource = { query, locationId -> observe(query, locationId) },
                locations = ports.locations.observe(ArchiveFilter.ACTIVE),
                savedState = savedState,
                computation = container.dispatchers.default,
                initialSupplierId = key.supplierId,
            )
        }
    when (model) {
        ScreenModel.Opening -> ReorderScreen(ReorderUiState(), onAction = {})

        ScreenModel.Unavailable -> PlaceholderRoute(key, navigator)

        is ScreenModel.Ready -> {
            val viewModel = model.viewModel
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val context = LocalContext.current
            val chooserTitle = stringResource(R.string.action_share)
            val shareTexts = shareTexts(uiState)
            ReorderScreen(
                uiState = uiState,
                onAction = { action ->
                    viewModel.onAction(action)
                    when (action) {
                        ReorderAction.Back -> navigator.back()

                        ReorderAction.ManageLocations -> navigator.open(AppNavKey.Locations)

                        is ReorderAction.Share -> shareTexts[action.supplierId]?.let { text ->
                            context.startActivity(shareIntent(text, chooserTitle))
                        }

                        is ReorderAction.CreatePurchase ->
                            viewModel.purchaseOf(action.supplierId)?.let(navigator::open)

                        ReorderAction.Retry,
                        is ReorderAction.CoverChange,
                        is ReorderAction.SupplierFilterChange,
                        is ReorderAction.CategoryChange,
                        is ReorderAction.LocationChange,
                        is ReorderAction.Increment,
                        is ReorderAction.Decrement,
                        -> Unit
                    }
                },
            )
        }
    }
}

/**
 * El texto que se comparte de cada grupo, por id de proveedor: el título, el proveedor si tiene, y
 * una línea por producto que no va en cero, con su cantidad. Nunca lleva costos.
 */
@Composable
private fun shareTexts(uiState: ReorderUiState): Map<Long?, String> {
    val heading = stringResource(R.string.reorder_share_heading)
    return uiState.groups.associate { group ->
        val lines = group.toBuy.map { line ->
            val base = line.unit?.let { unitLabel(it) } ?: UnitLabel("", allowsFraction = true)
            stringResource(R.string.reorder_share_line, shareAmount(line, base), line.name)
        }
        group.supplierId to (listOfNotNull(heading, group.supplierName) + lines).joinToString("\n")
    }
}

/** La cantidad compartida: en la unidad de compra si es un múltiplo de ella, si no en la base. */
@Composable
private fun shareAmount(line: ReorderLineUi, base: UnitLabel): String {
    val unit = line.purchaseUnit
    val factor = line.purchaseFactor
    if (unit == null || factor == null || line.quantity.milli % factor != 0L) {
        return quantityText(line.quantity, base)
    }
    return quantityText(QuantityMilli(line.quantity.milli / factor * Scale.MILLI), unitLabel(unit))
}
