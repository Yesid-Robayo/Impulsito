package io.github.yesidrobayo.shopledger.ui.product

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.ShopLedgerApp
import io.github.yesidrobayo.shopledger.data.DataAccess
import io.github.yesidrobayo.shopledger.data.DataPorts
import io.github.yesidrobayo.shopledger.domain.model.catalog.ImageImport
import io.github.yesidrobayo.shopledger.domain.model.region.NumberSymbols
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CreateCategoryUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CreateProductUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.DeleteConversionUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.DeleteProductResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.DeleteProductUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.EditProductUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.FindProductNameClashUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ImportProductPhotoUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.LoadProductForEditUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ObserveCatalogChoicesUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ObservePricingContextUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ObserveProductCatalogUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ObserveProductConversionsUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveConversionUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveUnitUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.domain.usecase.region.CurrencyLock
import io.github.yesidrobayo.shopledger.ui.unit.ResourceUnitNames
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * En el teléfono, con la base de la app: un producto guardado desde el formulario aparece en la
 * lista de productos, sin reabrirla, porque la lista observa la base. Al final se elimina, para
 * dejar la base como estaba. El producto va sin precio ni costo: no fija ninguna moneda.
 */
@RunWith(AndroidJUnit4::class)
class ProductCreateFlowTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val app = context.applicationContext as ShopLedgerApp

    @Test
    fun aProductSavedFromTheFormAppearsInTheList() = runBlocking {
        val container = app.container
        val ports = (container.data.open() as DataAccess.Ready).ports
        val name = "Producto de prueba ${System.nanoTime()}"
        val symbols = NumberSymbols(decimalSeparator = ',', minusSign = '-', grouping = null)

        val savedId = withContext(Dispatchers.Main) {
            val form = ProductEditViewModel(
                operationsOf(ports, container.clock),
                ProductNumberFormat(symbols, currencyDecimals = 0, quantitySymbols = symbols),
                productId = null,
                prefillName = name,
                unitNames = ResourceUnitNames(context.resources),
            )
            form.onAction(ProductEditAction.MinStockChange("2"))
            form.onAction(ProductEditAction.Save)
            withTimeout(TIMEOUT_MS) {
                form.uiState.first { it.savedProductId != null }
            }.savedProductId
        }

        val listed = withContext(Dispatchers.Main) {
            val list = ProductListViewModel(
                ObserveProductCatalogUseCase(ports.products, ports.units),
                Dispatchers.Default,
            )
            list.onAction(ProductListAction.QueryChange(name))
            withTimeout(TIMEOUT_MS) {
                list.uiState.first { state ->
                    val content = state.content
                    content is ProductListContent.Products &&
                        content.sections.any { section -> section.items.any { it.id == savedId } }
                }
            }
        }

        val item = (listed.content as ProductListContent.Products).sections.flatMap {
            it.items
        }.single()
        assertEquals(name, item.name)
        assertEquals(
            DeleteProductResult.Deleted,
            DeleteProductUseCase(ports.transactions, ports.products)(requireNotNull(savedId)),
        )
    }

    private fun operationsOf(ports: DataPorts, clock: Clock): ProductEditUseCases {
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
            importPhotoUseCase = ImportProductPhotoUseCase { _, _ -> ImageImport.Unreadable },
        )
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
