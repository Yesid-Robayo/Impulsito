package io.github.yesidrobayo.shopledger.ui.product

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ImportProductPhotoUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.LoadProductForEditUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ObserveCatalogChoicesUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ObservePricingContextUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ObserveProductConversionsUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveConversionUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveUnitUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.pricing.ObserveProductListPricesUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.pricing.ObserveReferenceRateUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.pricing.SetListPriceUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.domain.usecase.region.CurrencyLock
import io.github.yesidrobayo.shopledger.navigation.AppNavKey
import io.github.yesidrobayo.shopledger.navigation.AppNavigator
import io.github.yesidrobayo.shopledger.navigation.PlaceholderRoute
import io.github.yesidrobayo.shopledger.navigation.ScanPurpose
import io.github.yesidrobayo.shopledger.navigation.ScreenDependencies
import io.github.yesidrobayo.shopledger.navigation.ScreenModel
import io.github.yesidrobayo.shopledger.navigation.rememberAppViewModel
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.media.ProvidePhotos
import io.github.yesidrobayo.shopledger.ui.permission.LocalNotificationPermission
import io.github.yesidrobayo.shopledger.ui.unit.ResourceUnitNames
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

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
            percentSymbols = formats.percent.inputSymbols,
        )
    val model =
        rememberAppViewModel { dependencies ->
            ProductEditViewModel(
                useCasesOf(dependencies),
                numbers,
                key.productId,
                key.prefillName,
                ResourceUnitNames(resources),
                listPrices = { id ->
                    ObserveProductListPricesUseCase(
                        dependencies.ports.priceLists,
                        dependencies.ports.productPrices,
                        dependencies.ports.products,
                    )(id)
                },
                setListPrice = { listId, id, price ->
                    val ports = dependencies.ports
                    SetListPriceUseCase(
                        ports.transactions,
                        ports.priceLists,
                        ports.productPrices,
                        ports.products,
                        ports.priceHistory,
                        BusinessCalendarSource(ports.settings),
                        CurrencyLock(ports.settings),
                        dependencies.container.clock,
                    )(listId, id, price)
                },
                referenceCurrency = {
                    val ports = dependencies.ports
                    ObserveReferenceRateUseCase(ports.settings, ports.fxRates)()
                        .map { it.reference }
                        .distinctUntilChanged()
                },
            )
        }
    when (model) {
        ScreenModel.Opening ->
            ProductEditScreen(ProductEditUiState(isNew = key.productId == null, loading = true), {})

        ScreenModel.Unavailable -> PlaceholderRoute(key, navigator)

        is ScreenModel.Ready -> ProvidePhotos {
            val viewModel = model.viewModel
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val unitEditor by viewModel.unitEditor.editor.collectAsStateWithLifecycle()
            val conversionEditor by viewModel.conversionEditor.editor.collectAsStateWithLifecycle()
            val listPriceEditor by viewModel.listPriceEditor.editor.collectAsStateWithLifecycle()
            val notificationPermission = LocalNotificationPermission.current
            LaunchedEffect(uiState.savedProductId, uiState.closed) {
                // Con el primer producto se entiende para qué sirven los avisos de stock.
                if (uiState.savedProductId != null && key.productId == null) {
                    notificationPermission?.offerAfterNewProduct()
                }
                if (uiState.savedProductId != null || uiState.closed) navigator.back()
            }
            // El código que dejó el escáner llega al volver a verse, como si se hubiera escrito.
            val scanned by navigator.scans.toTake.collectAsStateWithLifecycle()
            LaunchedEffect(scanned) {
                navigator.scans.take(ScanPurpose.PRODUCT_BARCODE).lastOrNull()?.let {
                    viewModel.onAction(ProductEditAction.BarcodeChange(it.code))
                }
            }
            // Con algo escrito, atrás pregunta antes de descartarlo.
            BackHandler(enabled = uiState.dirty) { viewModel.onAction(ProductEditAction.Close) }
            // El selector de fotos del sistema: no pide permisos, y en Android viejos abre el de
            // documentos. Lo elegido solo se lee una vez, al recodificarlo.
            val picker =
                rememberLauncherForActivityResult(
                    ActivityResultContracts.PickVisualMedia(),
                ) { uri ->
                    if (uri != null) viewModel.onAction(ProductEditAction.PickPhoto(uri.toString()))
                }
            ProductEditScreen(
                uiState = uiState,
                onAction = { action ->
                    when (action) {
                        ProductEditAction.ConfirmCurrency ->
                            navigator.open(AppNavKey.RegionalSettings())

                        ProductEditAction.ManageUnits -> navigator.open(AppNavKey.Units)

                        ProductEditAction.OpenVariants ->
                            uiState.product?.let { navigator.open(AppNavKey.Variants(it.id)) }

                        ProductEditAction.ChoosePhoto -> picker.launch(
                            PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly,
                            ),
                        )

                        ProductEditAction.ScanBarcode ->
                            navigator.open(AppNavKey.Scanner(ScanPurpose.PRODUCT_BARCODE))

                        else -> viewModel.onAction(action)
                    }
                },
                unitEditor = unitEditor,
                onUnitEditorAction = viewModel.unitEditor::onAction,
                conversionEditor = conversionEditor,
                onConversionEditorAction = viewModel.conversionEditor::onAction,
                listPriceEditor = listPriceEditor,
                onListPriceEditorAction = viewModel.listPriceEditor::onAction,
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
            ports.lowStockAlerts, calendar, currencyLock, clock, ports.mediaAssets,
        ),
        editUseCase = EditProductUseCase(
            ports.transactions, ports.products, ports.categories, ports.suppliers,
            ports.units, ports.systemRows, ports.priceHistory, ports.stockLedger,
            ports.lowStockAlerts, calendar, currencyLock, clock, ports.productComponents,
            ports.mediaAssets,
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
        pricingUseCase =
            ObservePricingContextUseCase(ports.settings, ports.categories, ports.taxRates),
        importPhotoUseCase =
            ImportProductPhotoUseCase(dependencies.container.data.imageImporter),
    )
}
