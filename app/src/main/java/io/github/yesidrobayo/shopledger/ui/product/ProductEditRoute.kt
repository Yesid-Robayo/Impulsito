package io.github.yesidrobayo.shopledger.ui.product

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalResources
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CreateCategoryUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CreateProductUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.DeleteConversionUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.EditProductUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.FindProductNameClashUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.LoadProductForEditUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ObserveCatalogChoicesUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ObserveProductConversionsUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveConversionUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveUnitUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.domain.usecase.region.CurrencyLock
import io.github.yesidrobayo.shopledger.navigation.AppNavKey
import io.github.yesidrobayo.shopledger.navigation.AppNavigator
import io.github.yesidrobayo.shopledger.navigation.PlaceholderRoute
import io.github.yesidrobayo.shopledger.navigation.ScreenDependencies
import io.github.yesidrobayo.shopledger.navigation.ScreenModel
import io.github.yesidrobayo.shopledger.navigation.rememberAppViewModel
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.unit.ResourceUnitNames

/**
 * El formulario de un producto en la navegación. Al guardar, vuelve a donde se abrió, donde el
 * producto ya aparece. Cerrar, también con el gesto de atrás, pregunta antes si hay algo escrito.
 */
@Composable
fun ProductEditRoute(key: AppNavKey.ProductEdit, navigator: AppNavigator) {
    val formats = LocalAppFormats.current
    val resources = LocalResources.current
    val numbers =
        ProductNumberFormat(
            moneySymbols = formats.money.inputSymbols,
            currencyDecimals = formats.money.currencyDecimals,
            quantitySymbols = formats.quantity.inputSymbols,
        )
    val model =
        rememberAppViewModel { dependencies ->
            ProductEditViewModel(
                useCasesOf(dependencies),
                numbers,
                key.productId,
                key.prefillName,
                ResourceUnitNames(resources),
            )
        }
    when (model) {
        ScreenModel.Opening ->
            ProductEditScreen(ProductEditUiState(isNew = key.productId == null, loading = true), {})

        ScreenModel.Unavailable -> PlaceholderRoute(key, navigator)

        is ScreenModel.Ready -> {
            val viewModel = model.viewModel
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val unitEditor by viewModel.unitEditor.editor.collectAsStateWithLifecycle()
            val conversionEditor by viewModel.conversionEditor.editor.collectAsStateWithLifecycle()
            LaunchedEffect(uiState.savedProductId, uiState.closed) {
                if (uiState.savedProductId != null || uiState.closed) navigator.back()
            }
            // Con algo escrito, atrás pregunta antes de descartarlo.
            BackHandler(enabled = uiState.dirty) { viewModel.onAction(ProductEditAction.Close) }
            ProductEditScreen(
                uiState = uiState,
                onAction = { action ->
                    when (action) {
                        ProductEditAction.ConfirmCurrency ->
                            navigator.open(AppNavKey.RegionalSettings())

                        ProductEditAction.ManageUnits -> navigator.open(AppNavKey.Units)

                        else -> viewModel.onAction(action)
                    }
                },
                unitEditor = unitEditor,
                onUnitEditorAction = viewModel.unitEditor::onAction,
                conversionEditor = conversionEditor,
                onConversionEditorAction = viewModel.conversionEditor::onAction,
            )
        }
    }
}

private fun useCasesOf(dependencies: ScreenDependencies): ProductEditUseCases {
    val ports = dependencies.ports
    val clock = dependencies.container.clock
    val calendar = BusinessCalendarSource(ports.settings)
    val currencyLock = CurrencyLock(ports.settings)
    return ProductEditUseCases(
        loadUseCase = LoadProductForEditUseCase(
            ports.transactions,
            ports.products,
            ports.stockLedger,
        ),
        choicesUseCase = ObserveCatalogChoicesUseCase(ports.categories, ports.units),
        nameClashUseCase = FindProductNameClashUseCase(ports.transactions, ports.products),
        createUseCase = CreateProductUseCase(
            ports.transactions, ports.products, ports.categories, ports.suppliers,
            ports.units, ports.systemRows, ports.priceHistory, ports.stockLedger,
            ports.lowStockAlerts, calendar, currencyLock, clock,
        ),
        editUseCase = EditProductUseCase(
            ports.transactions, ports.products, ports.categories, ports.suppliers,
            ports.units, ports.systemRows, ports.priceHistory, ports.stockLedger,
            ports.lowStockAlerts, calendar, currencyLock, clock,
        ),
        createCategoryUseCase = CreateCategoryUseCase(
            ports.transactions,
            ports.categories,
            clock,
        ),
        conversionsUseCase = ObserveProductConversionsUseCase(ports.productUnits),
        saveConversionUseCase =
            SaveConversionUseCase(
                ports.transactions,
                ports.products,
                ports.units,
                ports.productUnits,
                clock,
            ),
        deleteConversionUseCase = DeleteConversionUseCase(
            ports.transactions,
            ports.productUnits,
        ),
        saveUnitUseCase = SaveUnitUseCase(ports.transactions, ports.unitCatalog, clock),
    )
}
