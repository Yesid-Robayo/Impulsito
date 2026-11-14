package io.github.yesidrobayo.shopledger.ui.variant

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yesidrobayo.shopledger.domain.model.catalog.VariantAxes
import io.github.yesidrobayo.shopledger.domain.model.catalog.VariantDraft
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CreateVariantsResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CreateVariantsUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.domain.usecase.region.CurrencyLock
import io.github.yesidrobayo.shopledger.navigation.AppNavKey
import io.github.yesidrobayo.shopledger.navigation.AppNavigator
import io.github.yesidrobayo.shopledger.navigation.PlaceholderRoute
import io.github.yesidrobayo.shopledger.navigation.ScreenDependencies
import io.github.yesidrobayo.shopledger.navigation.ScreenModel
import io.github.yesidrobayo.shopledger.navigation.rememberAppViewModel
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.product.ProductNumberFormat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Las variantes de un producto en la navegación. Al crearlas vuelve a donde se abrió, y cerrar,
 * también con el gesto de atrás, pregunta antes si hay algo escrito.
 */
@Composable
fun VariantsRoute(key: AppNavKey.Variants, navigator: AppNavigator) {
    val formats = LocalAppFormats.current
    val numbers =
        ProductNumberFormat(
            moneySymbols = formats.money.inputSymbols,
            currencyDecimals = formats.money.currencyDecimals,
            quantitySymbols = formats.quantity.inputSymbols,
        )
    val model =
        rememberAppViewModel { dependencies ->
            VariantsViewModel(operationsOf(dependencies, key.productId), numbers)
        }
    when (model) {
        ScreenModel.Opening -> VariantsScreen(VariantsUiState(), {})

        ScreenModel.Unavailable -> PlaceholderRoute(key, navigator)

        is ScreenModel.Ready -> {
            val viewModel = model.viewModel
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            LaunchedEffect(uiState.createdCount, uiState.closed) {
                if (uiState.createdCount != null || uiState.closed) navigator.back()
            }
            BackHandler(enabled = uiState.dirty) { viewModel.onAction(VariantsAction.Close) }
            VariantsScreen(
                uiState = uiState,
                onAction = { action ->
                    if (action == VariantsAction.ConfirmCurrency) {
                        navigator.open(AppNavKey.RegionalSettings())
                    }
                    viewModel.onAction(action)
                },
            )
        }
    }
}

private fun operationsOf(dependencies: ScreenDependencies, productId: Long): VariantsOperations {
    val ports = dependencies.ports
    val createVariants = CreateVariantsUseCase(
        ports.transactions, ports.products, ports.productVariants, ports.units,
        ports.systemRows, ports.priceHistory, ports.stockLedger, ports.lowStockAlerts,
        BusinessCalendarSource(ports.settings), CurrencyLock(ports.settings),
        dependencies.container.clock,
    )
    return object : VariantsOperations {
        override fun parent(): Flow<VariantParent?> = combine(
            ports.products.observeById(productId),
            ports.units.observeAll(),
            ports.productVariants.observeVariants(productId),
        ) { product, units, variants ->
            product?.let { found ->
                units.firstOrNull { it.id == found.details.baseUnitId }?.let { unit ->
                    VariantParent(found, unit, variants)
                }
            }
        }

        override suspend fun create(
            axes: VariantAxes,
            drafts: List<VariantDraft>,
        ): CreateVariantsResult = createVariants(productId, axes, drafts)
    }
}
