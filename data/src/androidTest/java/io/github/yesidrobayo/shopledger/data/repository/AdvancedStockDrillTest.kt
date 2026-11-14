package io.github.yesidrobayo.shopledger.data.repository

import androidx.room3.useReaderConnection
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.DataPorts
import io.github.yesidrobayo.shopledger.data.backup.InvariantQueries
import io.github.yesidrobayo.shopledger.data.local.catalog.CREATED_AT
import io.github.yesidrobayo.shopledger.data.local.catalog.CatalogDatabaseTest
import io.github.yesidrobayo.shopledger.data.local.texts
import io.github.yesidrobayo.shopledger.data.transaction.RestoreLock
import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.VoidReason
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductForm
import io.github.yesidrobayo.shopledger.domain.model.catalog.RecipeLine
import io.github.yesidrobayo.shopledger.domain.model.catalog.SupplierForm
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodDraft
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseLineAmount
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseLineLot
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseLineRequest
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseRequest
import io.github.yesidrobayo.shopledger.domain.model.returns.ReturnLineRequest
import io.github.yesidrobayo.shopledger.domain.model.returns.ReturnSettlement
import io.github.yesidrobayo.shopledger.domain.model.returns.SaleReturnRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleRequest
import io.github.yesidrobayo.shopledger.domain.model.settings.CsvSeparator
import io.github.yesidrobayo.shopledger.domain.model.settings.CurrencyCode
import io.github.yesidrobayo.shopledger.domain.model.settings.RegionalSettingsInput
import io.github.yesidrobayo.shopledger.domain.model.stock.LocationDraft
import io.github.yesidrobayo.shopledger.domain.model.stock.LocationKind
import io.github.yesidrobayo.shopledger.domain.model.stock.OpenCountRequest
import io.github.yesidrobayo.shopledger.domain.model.stock.StockTransferLineRequest
import io.github.yesidrobayo.shopledger.domain.model.stock.StockTransferRequest
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.ExpiryNotice
import io.github.yesidrobayo.shopledger.domain.port.ExpiryNotifier
import io.github.yesidrobayo.shopledger.domain.port.PaymentMethodNames
import io.github.yesidrobayo.shopledger.domain.rule.ReportPeriods
import io.github.yesidrobayo.shopledger.domain.rule.production.ProductionInput
import io.github.yesidrobayo.shopledger.domain.rule.stock.LocationRules
import io.github.yesidrobayo.shopledger.domain.rule.stock.ReorderPlan
import io.github.yesidrobayo.shopledger.domain.rule.stock.ReorderQuery
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CreateProductUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CreateSupplierUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveProductResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveRecipeResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveRecipeUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveSupplierResult
import io.github.yesidrobayo.shopledger.domain.usecase.money.DocumentMoney
import io.github.yesidrobayo.shopledger.domain.usecase.payment.EditPaymentMethodUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.payment.SavePaymentMethodResult
import io.github.yesidrobayo.shopledger.domain.usecase.production.ProductionRequest
import io.github.yesidrobayo.shopledger.domain.usecase.production.RegisterProductionResult
import io.github.yesidrobayo.shopledger.domain.usecase.production.RegisterProductionUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.production.VoidProductionResult
import io.github.yesidrobayo.shopledger.domain.usecase.production.VoidProductionUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.RegisterPurchaseResult
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.RegisterPurchaseUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.domain.usecase.region.CurrencyLock
import io.github.yesidrobayo.shopledger.domain.usecase.region.SaveRegionalSettingsResult
import io.github.yesidrobayo.shopledger.domain.usecase.region.SaveRegionalSettingsUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.returns.RegisterSaleReturnResult
import io.github.yesidrobayo.shopledger.domain.usecase.returns.RegisterSaleReturnUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleResult
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.stock.ApplyCountSessionUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.stock.DeliverExpiryNoticesUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.stock.FinishCountResult
import io.github.yesidrobayo.shopledger.domain.usecase.stock.ObserveExpiringLotsUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.stock.ObserveReorderPlanUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.stock.OpenCountResult
import io.github.yesidrobayo.shopledger.domain.usecase.stock.OpenCountSessionUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.stock.RecordCountResult
import io.github.yesidrobayo.shopledger.domain.usecase.stock.RecordCountUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.stock.RegisterStockTransferResult
import io.github.yesidrobayo.shopledger.domain.usecase.stock.RegisterStockTransferUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.stock.SaveLocationResult
import io.github.yesidrobayo.shopledger.domain.usecase.stock.SaveLocationUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.stock.VoidStockTransferResult
import io.github.yesidrobayo.shopledger.domain.usecase.stock.VoidStockTransferUseCase
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DayOfWeek
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Simulacro del stock avanzado sobre una base nueva, por el camino de la app: los puertos de la
 * base abierta y los casos de uso armados como los arman sus pantallas. Un día de una tienda de
 * barrio inventada con un local y una bodega: una transferencia de café anulada por error, la
 * compra de un yogur con dos lotes y vencimientos en la bodega, su venta allí por vencimiento con
 * una fila por lote, la devolución de un yogur que vuelve a su lote, una producción de galletas
 * con su receta, una venta de galletas y la anulación de esa producción, un conteo a ciegas
 * aplicado en el local, el aviso de vencimiento del lote que vence antes y la reposición
 * sugerida, en todo el negocio y en la bodega.
 *
 * El stock por ubicación y por lote, cada fila del libro con su costo y su promedio, el costo
 * congelado de cada línea, la caja, la venta neta y el costo de lo vendido del día, y la
 * reposición sugerida se comparan con valores calculados a mano. El promedio de la galleta tras
 * anular la producción sale de reproducir su libro, no de restar. Cada consulta de invariantes
 * del modelo de datos devuelve cero filas.
 *
 * La tienda, los productos, los lotes y los montos son inventados. La moneda es `XTS`, el código
 * de pruebas de ISO 4217, con dos decimales: los montos van en su unidad menor.
 */
@RunWith(AndroidJUnit4::class)
internal class AdvancedStockDrillTest : CatalogDatabaseTest() {
    // La base nace sin moneda, como en un teléfono nuevo, y la persona la confirma en su pantalla.
    override val confirmsCurrency: Boolean = false

    /** Un reloj que avanza un segundo en cada lectura, todo dentro del mismo día del negocio. */
    private val ticks = AtomicLong(CREATED_AT)
    private val clock = Clock { Instant.fromEpochMilliseconds(ticks.addAndGet(1_000L)) }

    @Test
    fun aDayOfLocationsLotsProductionCountsAndReorderMatchesHandWrittenBooks() = runBlocking {
        val ports = DataPorts(database, file, Dispatchers.IO, clock, RestoreLock { false })
        val tx = ports.transactions
        val calendar = BusinessCalendarSource(ports.settings)

        // Configuración, como en sus pantallas: la moneda y el efectivo enlazado a la caja.
        assertEquals(
            SaveRegionalSettingsResult.Saved,
            SaveRegionalSettingsUseCase(tx, ports.settings, clock)(REGION),
        )
        val drawer = count("SELECT id FROM money_accounts WHERE system_key = 'CASH'")
        val cashMethod = count("SELECT id FROM payment_methods WHERE system_key = 'CASH'")
        val cash = requireNotNull(ports.paymentMethods.findById(cashMethod)).details
        assertEquals(
            SavePaymentMethodResult.Saved(cashMethod),
            EditPaymentMethodUseCase(tx, ports.paymentMethods, ports.mediaAssets, clock)(
                cashMethod,
                PaymentMethodDraft(
                    name = cash.name,
                    kind = cash.kind,
                    feeRate = cash.feeRate,
                    feeFixed = cash.feeFixed,
                    settlementDays = cash.settlementDays,
                    priceAdjustment = cash.priceAdjustment,
                    moneyAccountId = drawer,
                    instructionsText = cash.instructionsText,
                ),
            ),
        )

        // Los casos de uso de cada pantalla, con los mismos puertos que les da la app.
        val createProduct = CreateProductUseCase(
            tx, ports.products, ports.categories, ports.suppliers, ports.units, ports.systemRows,
            ports.priceHistory, ports.stockLedger, ports.lowStockAlerts, calendar,
            CurrencyLock(ports.settings), clock, ports.mediaAssets,
        )
        val createSupplier = CreateSupplierUseCase(tx, ports.suppliers, clock)
        val saveRecipe = SaveRecipeUseCase(tx, ports.products, ports.productComponents, clock)
        val saveLocation = SaveLocationUseCase(tx, ports.locations, clock)
        val registerTransfer = RegisterStockTransferUseCase(
            tx,
            ports.products,
            ports.units,
            ports.locations,
            ports.stockTransfers,
            ports.stockLedger,
            calendar,
            clock,
            ports.stockLots,
        )
        val voidTransfer = VoidStockTransferUseCase(
            tx,
            ports.products,
            ports.stockTransfers,
            ports.stockLedger,
            calendar,
            clock,
            ports.stockLots,
        )
        val registerSale = RegisterSaleUseCase(
            transactions = tx,
            settings = ports.settings,
            products = ports.products,
            sales = ports.sales,
            payments = ports.salePayments,
            units = ports.units,
            systemRows = ports.systemRows,
            ledger = ports.stockLedger,
            methodNames = PaymentMethodNames { key -> "Initial ${key.lowercase()}" },
            taxRates = ports.taxRates,
            clock = clock,
            heldSales = ports.heldSales,
            productUnits = ports.productUnits,
            alerts = ports.lowStockAlerts,
            customers = ports.customers,
            customerLedger = ports.customerLedger,
            loyaltyLedger = ports.loyaltyLedger,
            moneyAccounts = ports.moneyAccounts,
            moneyLedger = ports.moneyLedger,
            priceLists = ports.priceLists,
            productPrices = ports.productPrices,
            recipes = ports.productComponents,
            lineComponents = ports.saleLineComponents,
            locations = ports.locations,
            lots = ports.stockLots,
        )
        val registerPurchase = RegisterPurchaseUseCase(
            transactions = tx,
            settings = ports.settings,
            products = ports.products,
            purchases = ports.purchases,
            suppliers = ports.suppliers,
            units = ports.units,
            systemRows = ports.systemRows,
            ledger = ports.stockLedger,
            taxRates = ports.taxRates,
            clock = clock,
            productUnits = ports.productUnits,
            alerts = ports.lowStockAlerts,
            categories = ports.categories,
            supplierLedger = ports.supplierLedger,
            money = DocumentMoney(ports.moneyAccounts, ports.moneyLedger),
            locations = ports.locations,
            lots = ports.stockLots,
        )
        val registerReturn = RegisterSaleReturnUseCase(
            transactions = tx,
            settings = ports.settings,
            products = ports.products,
            sales = ports.sales,
            returns = ports.saleReturns,
            units = ports.units,
            ledger = ports.stockLedger,
            clock = clock,
            alerts = ports.lowStockAlerts,
            moneyAccounts = ports.moneyAccounts,
            moneyLedger = ports.moneyLedger,
            customers = ports.customers,
            customerLedger = ports.customerLedger,
            lineComponents = ports.saleLineComponents,
        )
        val produce = RegisterProductionUseCase(
            tx, ports.products, ports.units, ports.productComponents, ports.productionBatches,
            ports.systemRows, ports.stockLedger, ports.lowStockAlerts, calendar,
            CurrencyLock(ports.settings), clock,
        )
        val voidProduction = VoidProductionUseCase(
            tx,
            ports.products,
            ports.productionBatches,
            ports.stockLedger,
            ports.lowStockAlerts,
            calendar,
            clock,
        )
        val openCount = OpenCountSessionUseCase(
            tx,
            ports.countSessions,
            ports.products,
            ports.categories,
            ports.locations,
            ports.locationStock,
            calendar,
            clock,
        )
        val recordCount = RecordCountUseCase(tx, ports.countSessions, ports.products, ports.units)
        val applyCount = ApplyCountSessionUseCase(
            tx,
            ports.countSessions,
            ports.products,
            ports.stockLedger,
            ports.lowStockAlerts,
            calendar,
            CurrencyLock(ports.settings),
            clock,
        )
        val expiring = ObserveExpiringLotsUseCase(ports.stockLots, calendar, ports.settings, clock)
        val shown = mutableListOf<ExpiryNotice>()
        val notifier = ExpiryNotifier { notices ->
            shown += notices
            true
        }
        val deliverNotices =
            DeliverExpiryNoticesUseCase(expiring, ports.stockLots, ports.units, notifier, clock)
        val reorder = ObserveReorderPlanUseCase(
            products = ports.products,
            suppliers = ports.suppliers,
            categories = ports.categories,
            units = ports.units,
            productUnits = ports.productUnits,
            outflow = ports.saleOutflow,
            locationStock = ports.locationStock,
            calendar = calendar,
            clock = clock,
        )

        // 1. Con la ubicación inicial sola, nada de ubicaciones se muestra. La bodega es la
        // segunda, y desde ella cada pantalla de stock ofrece su selector.
        val store = count("SELECT id FROM locations WHERE system_key = 'MAIN'")
        assertFalse(LocationRules.showsLocations(activeLocations(ports)))
        val warehouse = (
            saveLocation(null, LocationDraft("Bodega inventada", LocationKind.WAREHOUSE))
                as SaveLocationResult.Saved
            ).locationId
        assertTrue(LocationRules.showsLocations(activeLocations(ports)))

        // 2. El proveedor del yogur y los productos: el café con 10 a 2.000 y un mínimo de 4, el
        // yogur por lotes, sin stock y con un mínimo de 2, la harina con 10 a 3.000, el azúcar
        // con 10 a 1.000 y la galleta con 5 a 400 y su receta de 0,2 de harina y 0,1 de azúcar.
        // Lo que se crea con stock entra en la ubicación inicial.
        val dairy = (
            createSupplier(SupplierForm("Lácteos inventados")) as SaveSupplierResult.Saved
            ).supplierId
        val coffee = saved(createProduct(COFFEE, QuantityMilli(10_000)))
        val yogurt = saved(createProduct(YOGURT.copy(defaultSupplierId = dairy)))
        val flour = saved(createProduct(FLOUR, QuantityMilli(10_000)))
        val sugar = saved(createProduct(SUGAR, QuantityMilli(10_000)))
        val cookie = saved(createProduct(COOKIE, QuantityMilli(5_000)))
        assertEquals(
            SaveRecipeResult.Saved,
            saveRecipe(
                cookie,
                listOf(
                    RecipeLine(flour, QuantityMilli(200)),
                    RecipeLine(sugar, QuantityMilli(100)),
                ),
            ),
        )

        // 3. Cuatro cafés pasan del local a la bodega, y la transferencia se anula por error: el
        // café nunca cambia de cantidad, valor ni promedio, solo de lugar.
        val transfer = (
            registerTransfer(
                StockTransferRequest(
                    store,
                    warehouse,
                    listOf(StockTransferLineRequest(coffee, QuantityMilli(4_000))),
                ),
            ) as RegisterStockTransferResult.Registered
            ).transferId
        assertEquals(
            listOf("$coffee $store 0 6000", "$coffee $warehouse 0 4000"),
            balances(coffee),
        )
        assertEquals("10000 2000 20000", cached(ports, coffee))
        assertEquals(
            VoidStockTransferResult.Voided,
            voidTransfer(transfer, VoidReason.ENTRY_ERROR),
        )
        assertEquals(listOf("$coffee $store 0 10000", "$coffee $warehouse 0 0"), balances(coffee))

        // 4. Venta de seis cafés en el local, sin elegir ubicación: sale de la inicial. 24.000.
        val coffeeSale = registered(
            registerSale(SaleRequest(listOf(line(coffee, 6_000)), cashMethod)),
        )

        // 5. Compra del yogur en la bodega, pagada de la caja: el lote L-0110 vence el 10 de
        // enero, con 6 a 1.000, y el L-0315 el 15 de marzo, con 9 a 1.500. 19.500, a 1.300.
        val purchase = registerPurchase(
            PurchaseRequest(
                lines = listOf(
                    lotLine(yogurt, 6_000, 1_000, PurchaseLineLot.New("L-0110", JAN_10)),
                    lotLine(yogurt, 9_000, 1_500, PurchaseLineLot.New("L-0315", MAR_15)),
                ),
                supplierId = dairy,
                moneyAccountId = drawer,
                locationId = warehouse,
            ),
        )
        assertTrue("$purchase", purchase is RegisterPurchaseResult.Registered)
        val soon = requireNotNull(ports.stockLots.findByCode(yogurt, "L-0110")).id
        val later = requireNotNull(ports.stockLots.findByCode(yogurt, "L-0315")).id

        // 6. Venta de nueve yogures en la bodega, por vencimiento: los seis del lote que vence
        // antes y tres del otro, una fila por lote, cada una a 1.300. 22.500.
        val yogurtSale = registered(
            registerSale(
                SaleRequest(listOf(line(yogurt, 9_000)), cashMethod, locationId = warehouse),
            ),
        )

        // 7. Vuelve un yogur de esa venta, con reembolso de la caja: entra al lote del que salió
        // primero, en la bodega, al costo congelado.
        val yogurtLine = ports.sales.findLines(yogurtSale).single().id
        val yogurtReturn = (
            registerReturn(
                SaleReturnRequest(
                    yogurtSale,
                    listOf(ReturnLineRequest(yogurtLine, QuantityMilli(1_000))),
                    ReturnSettlement.Refund(drawer),
                ),
            ) as RegisterSaleReturnResult.Registered
            ).saleReturnId

        // 8. Diez galletas con su receta: salen 2 de harina a 3.000 y 1 de azúcar a 1.000, y la
        // galleta entra a la suma, 7.000. Con las 5 a 400 queda en 15 por 9.000: 600 cada una.
        val batch = produce(
            ProductionRequest(
                cookie,
                QuantityMilli(10_000),
                listOf(
                    ProductionInput(flour, QuantityMilli(2_000)),
                    ProductionInput(sugar, QuantityMilli(1_000)),
                ),
                note = null,
            ),
        )
        assertEquals(Money(7_000), (batch as RegisterProductionResult.Registered).totalCost)

        // 9. Venta de tres galletas en el local a 1.500: salen a 600. 4.500.
        val cookieSale = registered(
            registerSale(SaleRequest(listOf(line(cookie, 3_000)), cashMethod)),
        )

        // 10. La producción se anula por error. Los insumos vuelven y las diez galletas salen; el
        // promedio se reproduce sin la producción: 5 a 400, menos las 3 vendidas a su costo
        // congelado de 1.800, deja 2 con 200 de valor y el promedio en 400, no en 100.
        assertEquals(
            VoidProductionResult.Voided,
            voidProduction(batch.batchId, VoidReason.ENTRY_ERROR, null),
        )

        // 11. Conteo a ciegas del local: esperaba 4 cafés y se cuentan 3; el azúcar se cuenta
        // igual a lo esperado y no escribe nada, y lo que no se cuenta no cambia.
        val session = (openCount(OpenCountRequest(store)) as OpenCountResult.Opened).sessionId
        val countLines = ports.countSessions.findLines(session)
        assertEquals(
            listOf("$sugar 10000", "$coffee 4000", "$cookie 2000", "$flour 10000"),
            countLines.map { "${it.productId} ${it.expected.milli}" },
        )
        val coffeeCount = countLines.single { it.productId == coffee }.id
        val sugarCount = countLines.single { it.productId == sugar }.id
        assertEquals(RecordCountResult.Recorded, recordCount(session, coffeeCount, units(3)))
        assertEquals(RecordCountResult.Recorded, recordCount(session, sugarCount, units(10)))
        assertEquals(FinishCountResult.Applied(1), applyCount(session))
        assertEquals(
            listOf("1 APPLIED $store"),
            rows("SELECT is_blind || ' ' || status || ' ' || location_id FROM count_sessions"),
        )

        // El costo congelado de cada línea: el yogur a 1.300, la suma de sus dos filas.
        assertEquals(
            listOf(
                "$coffeeSale $store $coffee 6000 4000 2000 12000",
                "$yogurtSale $warehouse $yogurt 9000 2500 1300 11700",
                "$cookieSale $store $cookie 3000 1500 600 1800",
            ),
            rows(
                "SELECT l.sale_id || ' ' || s.location_id || ' ' || l.product_id || ' ' || " +
                    "l.quantity_milli || ' ' || l.unit_price || ' ' || l.unit_cost || ' ' || " +
                    "l.line_cost FROM sale_lines l JOIN sales s ON s.id = l.sale_id ORDER BY l.id",
            ),
        )

        // El libro, fila por fila: tipo, ubicación, lote, cantidad, costo, valor y promedio. La
        // anulación de la transferencia saca primero de la bodega y después devuelve al local.
        assertEquals(
            listOf(
                "OPENING_BALANCE $store - 10000 20000 20000 2000",
                "TRANSFER_OUT $store - -4000 0 20000 2000",
                "TRANSFER_IN $warehouse - 4000 0 20000 2000",
                "TRANSFER_VOID $warehouse - -4000 0 20000 2000",
                "TRANSFER_VOID $store - 4000 0 20000 2000",
                "SALE $store - -6000 -12000 8000 2000",
                "ADJUSTMENT $store - -1000 -2000 6000 2000",
            ),
            stockRows(coffee),
        )
        assertEquals(
            listOf(
                "PURCHASE $warehouse $soon 6000 6000 6000 1000",
                "PURCHASE $warehouse $later 9000 13500 19500 1300",
                "SALE $warehouse $soon -6000 -7800 11700 1300",
                "SALE $warehouse $later -3000 -3900 7800 1300",
                "SALE_RETURN $warehouse $soon 1000 1300 9100 1300",
            ),
            stockRows(yogurt),
        )
        assertEquals(
            listOf(
                "OPENING_BALANCE $store - 10000 30000 30000 3000",
                "PRODUCTION_OUT $store - -2000 -6000 24000 3000",
                "PRODUCTION_VOID $store - 2000 6000 30000 3000",
            ),
            stockRows(flour),
        )
        assertEquals(
            listOf(
                "OPENING_BALANCE $store - 10000 10000 10000 1000",
                "PRODUCTION_OUT $store - -1000 -1000 9000 1000",
                "PRODUCTION_VOID $store - 1000 1000 10000 1000",
            ),
            stockRows(sugar),
        )
        assertEquals(
            listOf(
                "OPENING_BALANCE $store - 5000 2000 2000 400",
                "PRODUCTION_IN $store - 10000 7000 9000 600",
                "SALE $store - -3000 -1800 7200 600",
                "PRODUCTION_VOID $store - -10000 -7000 200 400",
            ),
            stockRows(cookie),
        )
        assertEquals(
            listOf("$cookie 10000 7000 VOIDED"),
            rows(
                "SELECT product_id || ' ' || quantity_milli || ' ' || total_cost || ' ' || " +
                    "status FROM production_batches",
            ),
        )
        assertEquals(
            listOf("$transfer VOIDED"),
            rows("SELECT id || ' ' || status FROM stock_transfers"),
        )

        // Lo que hay, por producto, por ubicación y por lote.
        assertEquals(
            listOf(
                "$coffee 3000 2000 6000",
                "$yogurt 7000 1300 9100",
                "$flour 10000 3000 30000",
                "$sugar 10000 1000 10000",
                "$cookie 2000 400 200",
            ),
            listOf(coffee, yogurt, flour, sugar, cookie).map { "$it ${cached(ports, it)}" },
        )
        assertEquals(listOf("$coffee $store 0 3000", "$coffee $warehouse 0 0"), balances(coffee))
        assertEquals(
            listOf("$yogurt $warehouse $soon 1000", "$yogurt $warehouse $later 6000"),
            balances(yogurt),
        )
        for (product in listOf(flour, sugar)) {
            assertEquals(listOf("$product $store 0 10000"), balances(product))
        }
        assertEquals(listOf("$cookie $store 0 2000"), balances(cookie))
        assertEquals(
            listOf("L-0110 1000", "L-0315 6000"),
            ports.stockLots.findStock(yogurt, warehouse).map { "${it.code} ${it.onHand.milli}" },
        )
        // En el local los dos lotes existen, sin nada: todo entró y salió por la bodega.
        assertEquals(
            listOf("L-0110 0", "L-0315 0"),
            ports.stockLots.findStock(yogurt, store).map { "${it.code} ${it.onHand.milli}" },
        )

        // La devolución: 2.500 de un yogur, con 1.300 de costo de vuelta.
        assertEquals(
            listOf("$yogurtReturn REFUND 2500 1300 ACTIVE"),
            rows(
                "SELECT id || ' ' || resolution || ' ' || net_refund || ' ' || cost_returned " +
                    "|| ' ' || status FROM sale_returns ORDER BY id",
            ),
        )
        // La caja: el café, la compra del yogur, su venta, el reembolso y las galletas.
        assertEquals(
            listOf(
                "SALE_RECEIPT 24000 24000",
                "PURCHASE_PAYMENT -19500 4500",
                "SALE_RECEIPT 22500 27000",
                "REFUND -2500 24500",
                "SALE_RECEIPT 4500 29000",
            ),
            rows(
                "SELECT type || ' ' || amount || ' ' || balance_after FROM money_movements " +
                    "WHERE money_account_id = $drawer ORDER BY id",
            ),
        )

        // El día según la sección de ganancias: 24.000, 22.500 y 4.500 menos el yogur devuelto,
        // 2.500, dan 48.500; su costo, 12.000, 11.700 y 1.800, menos 1.300, da 24.200. La
        // producción, la transferencia y el conteo no son ventas.
        val day = ReportPeriods.day(
            requireNotNull(ports.sales.findById(coffeeSale)).content.businessDate,
        )
        val profit = ports.periodReports.observeTotals(day).first().profit
        assertEquals(Money(51_000), profit.salesNetTotal)
        assertEquals(Money(2_500), profit.returnsNetRefund)
        assertEquals(Money(25_500), profit.salesTotalCost)
        assertEquals(Money(1_300), profit.returnsCostReturned)
        assertEquals(Money(48_500), profit.salesNetTotal - profit.returnsNetRefund)
        assertEquals(Money(24_200), profit.salesTotalCost - profit.returnsCostReturned)

        // El aviso de vencimiento: con 30 días de aviso, el L-0110 vence en 9 y tiene 1; el
        // L-0315, en 73, todavía no. Se avisa una vez, y la segunda pasada no repite.
        val dueSoon = expiring().first()
        assertEquals(
            listOf("$soon L-0110 9 1000"),
            dueSoon.map { "${it.lot.lotId} ${it.lot.code} ${it.daysLeft} ${it.lot.onHand.milli}" },
        )
        assertEquals(JAN_10, dueSoon.single().lot.expiryDate)
        assertEquals(1, deliverNotices())
        assertEquals(0, deliverNotices())
        assertEquals(listOf(soon), shown.map { it.lot.lot.lotId })
        assertEquals(
            listOf("$soon 1", "$later 0"),
            rows(
                "SELECT id || ' ' || (expiry_notified_at IS NOT NULL) FROM stock_lots " +
                    "WHERE product_id = $yogurt AND is_unassigned = 0 ORDER BY id",
            ),
        )

        // La reposición sugerida, a 30 días de ventas y 30 de cobertura. El yogur vendió 9:
        // 300 por día, 7 alcanzan 23 días, y 300 × 30 + 2 de mínimo − 7 piden 4. El café vendió
        // 6: 200 por día, 3 alcanzan 15, y 200 × 30 + 4 − 3 piden 7. La galleta vendió 3: 100 por
        // día, 2 alcanzan 20, y 100 × 30 − 2 piden 1. La harina y el azúcar no se vendieron.
        assertEquals(
            listOf(
                "$dairy: $yogurt 7000 300 23 4000",
                "-: $coffee 3000 200 15 7000, $cookie 2000 100 20 1000",
                "true",
            ),
            planRows(reorder(ReorderQuery()).first().plan),
        )
        // En la bodega se mira lo de la bodega: el yogur igual, y el café, sin nada ahí y bajo
        // su mínimo, entra en cero para llenarlo a mano.
        assertEquals(
            listOf("$dairy: $yogurt 7000 300 23 4000", "-: $coffee 0 0 null 0", "true"),
            planRows(reorder(ReorderQuery(), warehouse).first().plan),
        )

        // Cada consulta de invariantes da cero filas, también las de ubicaciones, lotes y
        // transferencias.
        val labels = InvariantQueries.MODEL.map { it.label }
        for (invariant in listOf("I-10", "I-11", "I-12", "I-21")) {
            assertTrue(invariant, labels.any { it == invariant || it.startsWith("$invariant,") })
        }
        for (query in InvariantQueries.MODEL) {
            val sql = query.sql.trimEnd().removeSuffix(";")
            assertEquals(query.label, 0L, count("SELECT COUNT(*) FROM ($sql)"))
        }
    }

    private suspend fun activeLocations(ports: DataPorts): Int =
        ports.locations.observe(ArchiveFilter.ACTIVE).first().size

    private fun units(count: Long) = QuantityMilli(count * 1_000L)

    private fun line(productId: Long, milli: Long) =
        SaleLineRequest(productId, QuantityMilli(milli))

    private fun lotLine(productId: Long, milli: Long, unitCost: Long, lot: PurchaseLineLot) =
        PurchaseLineRequest(
            productId,
            QuantityMilli(milli),
            PurchaseLineAmount.UnitCost(Money(unitCost)),
            lot = lot,
        )

    /** Cantidad, costo promedio y valor del stock guardados en el producto. */
    private suspend fun cached(ports: DataPorts, productId: Long): String {
        val product = requireNotNull(ports.products.findById(productId))
        return "${product.onHand.milli} ${product.averageCost.minor} ${product.stockValue.minor}"
    }

    /** Los grupos de la reposición: proveedor y, por línea, hay, por día, días y sugerencia. */
    private fun planRows(plan: ReorderPlan): List<String> = plan.groups.map { group ->
        "${group.supplier?.id ?: "-"}: " + group.lines.joinToString { line ->
            val suggestion = line.suggestion
            "${line.product.id} ${line.onHand.milli} ${suggestion.velocity.milli} " +
                "${suggestion.daysLeft} ${suggestion.suggested.milli}"
        }
    } + "${plan.hasRecentSales}"

    /** Los saldos de [product]: producto, ubicación, lote (0 sin lote) y cantidad. */
    private suspend fun balances(product: Long): List<String> = rows(
        "SELECT product_id || ' ' || location_id || ' ' || lot_key || ' ' || on_hand_milli " +
            "FROM stock_balances WHERE product_id = $product ORDER BY location_id, lot_key",
    )

    /** Las filas de [product]: tipo, ubicación, lote, cantidad, costo, valor y promedio. */
    private suspend fun stockRows(product: Long): List<String> = rows(
        "SELECT type || ' ' || location_id || ' ' || COALESCE(lot_id, '-') || ' ' || " +
            "quantity_milli || ' ' || total_cost || ' ' || stock_value_after || ' ' || " +
            "average_cost_after FROM stock_movements WHERE product_id = $product ORDER BY id",
    )

    private suspend fun rows(sql: String): List<String> =
        database.useReaderConnection { it.texts(sql) }

    private fun saved(result: SaveProductResult): Long =
        (result as? SaveProductResult.Saved)?.productId
            ?: throw AssertionError("No se guardó: $result")

    private fun registered(result: RegisterSaleResult): Long =
        (result as? RegisterSaleResult.Registered)?.saleId
            ?: throw AssertionError("No se registró: $result")

    private companion object {
        /** La región que confirma la persona: moneda de pruebas de dos decimales, sin redondeo. */
        val REGION = RegionalSettingsInput(
            currencyCode = requireNotNull(CurrencyCode.of("XTS")),
            currencyDecimals = 2,
            currencySymbol = "¤",
            cashRounding = Money.ZERO,
            formatLocaleTag = "pt-CV",
            businessTimeZoneId = "Etc/GMT-9",
            weekStartDay = DayOfWeek.MONDAY,
            csvSeparator = CsvSeparator.SEMICOLON,
        )

        /** El día del negocio es el 1 de enero: un lote vence en 9 días y el otro en 73. */
        val JAN_10 = requireNotNull(BusinessDate.parse("2026-01-10"))
        val MAR_15 = requireNotNull(BusinessDate.parse("2026-03-15"))

        val COFFEE = ProductForm(
            name = "Café inventado",
            salePrice = Money(4_000),
            cost = Money(2_000),
            minStock = QuantityMilli(4_000),
        )

        /** Por lotes: no entra con stock inicial, sino con la compra de cada lote. */
        val YOGURT = ProductForm(
            name = "Yogur inventado",
            salePrice = Money(2_500),
            minStock = QuantityMilli(2_000),
            tracksLots = true,
        )
        val FLOUR =
            ProductForm(name = "Harina inventada", salePrice = Money(5_000), cost = Money(3_000))
        val SUGAR =
            ProductForm(name = "Azúcar inventado", salePrice = Money(2_000), cost = Money(1_000))
        val COOKIE =
            ProductForm(name = "Galleta inventada", salePrice = Money(1_500), cost = Money(400))
    }
}
