package io.github.yesidrobayo.shopledger.ui.stock

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.ShopLedgerApp
import io.github.yesidrobayo.shopledger.data.DataAccess
import io.github.yesidrobayo.shopledger.data.DataPorts
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductForm
import io.github.yesidrobayo.shopledger.domain.model.region.NumberSymbols
import io.github.yesidrobayo.shopledger.domain.model.stock.StockAdjustmentReason
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ArchiveProductUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CreateProductUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.RegisterOpeningBalanceUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveProductResult
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.domain.usecase.region.CurrencyLock
import io.github.yesidrobayo.shopledger.domain.usecase.stock.AdjustStockUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.stock.ObserveInventoryUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.stock.ObserveStockAdjustmentSubjectUseCase
import io.github.yesidrobayo.shopledger.ui.product.ProductNumberFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * El conteo físico en el teléfono, con la base de la app y el formulario del ajuste: la persona
 * escribe lo que contó, ve la diferencia antes de guardar y guarda. La diferencia queda como una
 * corrección por conteo, nunca como una edición del saldo; contar lo mismo que dice el libro no
 * escribe nada; y el inventario muestra el producto por agotarse sin mirar las alertas.
 *
 * El producto va sin costo, para no fijar ninguna moneda, y se archiva al final: con historia ya
 * no se borra.
 */
@RunWith(AndroidJUnit4::class)
class StockCountFlowTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val app = context.applicationContext as ShopLedgerApp
    private val symbols = NumberSymbols(decimalSeparator = ',', minusSign = '-', grouping = null)

    @Test
    fun aCountShowsTheDifferenceSavesACorrectionAndRepeatsAsNoDifference(): Unit = runBlocking {
        val container = app.container
        val ports = (container.data.open() as DataAccess.Ready).ports
        val clock = container.clock
        val productId = createWithStock(ports, clock, units = 12L, minStock = 10L)

        val first = count(ports, clock, productId, "10")
        // Antes de guardar, la persona ve lo que queda y la diferencia.
        // Sin costo, el ajuste vale cero.
        assertEquals(
            AdjustmentPreview(QuantityMilli(10_000L), QuantityMilli(-2_000L), Money.ZERO),
            first.preview,
        )
        assertEquals(AdjustmentOutcome.Saved(QuantityMilli(10_000L)), first.outcome)
        val rows = ports.stockLedger.findByProduct(productId)
        assertEquals(
            listOf(StockMovementType.OPENING_BALANCE, StockMovementType.ADJUSTMENT),
            rows.map { it.movement.type },
        )
        assertEquals(StockAdjustmentReason.COUNT_CORRECTION, rows.last().reason)
        assertEquals(QuantityMilli(-2_000L), rows.last().movement.quantity)

        val again = count(ports, clock, productId, "10")
        assertEquals(AdjustmentOutcome.NoDifference, again.outcome)
        assertEquals(2, ports.stockLedger.findByProduct(productId).size)

        val inventory = ObserveInventoryUseCase(ports.products, ports.units)().first()
        assertTrue(inventory.lowStock.any { it.id == productId })

        ArchiveProductUseCase(
            ports.transactions,
            ports.products,
            ports.lowStockAlerts,
            clock,
            ports.productVariants,
        )(productId)
    }

    /** Abre el ajuste del producto, escribe [counted] en el conteo y guarda. */
    private suspend fun count(
        ports: DataPorts,
        clock: Clock,
        productId: Long,
        counted: String,
    ): StockAdjustmentUiState = withContext(Dispatchers.Main) {
        val form = StockAdjustmentViewModel(
            operationsOf(ports, clock),
            ProductNumberFormat(symbols, currencyDecimals = 0, quantitySymbols = symbols),
            productId,
        )
        withTimeout(TIMEOUT_MS) {
            form.uiState.first { it.content is StockAdjustmentContent.Ready }
        }
        form.onAction(StockAdjustmentAction.ModeChanged(AdjustmentMode.COUNT))
        form.onAction(StockAdjustmentAction.QuantityChanged(counted))
        val written = form.uiState.value
        form.onAction(StockAdjustmentAction.Save)
        val saved = withTimeout(TIMEOUT_MS) { form.uiState.first { it.outcome != null } }
        saved.copy(preview = written.preview)
    }

    private suspend fun createWithStock(
        ports: DataPorts,
        clock: Clock,
        units: Long,
        minStock: Long,
    ): Long {
        val calendar = BusinessCalendarSource(ports.settings)
        val create = CreateProductUseCase(
            ports.transactions, ports.products, ports.categories, ports.suppliers, ports.units,
            ports.systemRows, ports.priceHistory, ports.stockLedger, ports.lowStockAlerts,
            calendar, CurrencyLock(ports.settings), clock, ports.mediaAssets,
        )
        val form = ProductForm(
            name = "Producto contado ${System.nanoTime()}",
            minStock = QuantityMilli.ofUnits(minStock),
        )
        val saved = create(form, QuantityMilli.ofUnits(units))
        return (saved as SaveProductResult.Saved).productId
    }

    private fun operationsOf(ports: DataPorts, clock: Clock): StockAdjustmentOperations {
        val calendar = BusinessCalendarSource(ports.settings)
        val currencyLock = CurrencyLock(ports.settings)
        return StockAdjustmentUseCases(
            ObserveStockAdjustmentSubjectUseCase(
                ports.transactions,
                ports.products,
                ports.units,
                ports.stockLedger,
            ),
            AdjustStockUseCase(
                ports.transactions, ports.products, ports.units, ports.systemRows,
                ports.stockLedger, ports.lowStockAlerts, calendar, currencyLock, clock,
                ports.stockLots,
            ),
            RegisterOpeningBalanceUseCase(
                ports.transactions, ports.products, ports.units, ports.systemRows,
                ports.stockLedger, ports.lowStockAlerts, calendar, currencyLock, clock,
            ),
        )
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
