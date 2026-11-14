package io.github.yesidrobayo.shopledger.data.repository

import androidx.room3.useReaderConnection
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.DataPorts
import io.github.yesidrobayo.shopledger.data.backup.InvariantQueries
import io.github.yesidrobayo.shopledger.data.local.catalog.CREATED_AT
import io.github.yesidrobayo.shopledger.data.local.catalog.CatalogDatabaseTest
import io.github.yesidrobayo.shopledger.data.local.texts
import io.github.yesidrobayo.shopledger.data.transaction.RestoreLock
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.VoidReason
import io.github.yesidrobayo.shopledger.domain.model.catalog.BulkPriceAdjustment
import io.github.yesidrobayo.shopledger.domain.model.catalog.BulkPriceContext
import io.github.yesidrobayo.shopledger.domain.model.catalog.BulkPriceRequest
import io.github.yesidrobayo.shopledger.domain.model.catalog.BulkPriceScope
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductForm
import io.github.yesidrobayo.shopledger.domain.model.catalog.SupplierForm
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerForm
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccountDraft
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccountKind
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodDraft
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceListDraft
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceListKind
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseLineAmount
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseLineRequest
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseRequest
import io.github.yesidrobayo.shopledger.domain.model.returns.PurchaseReturnResolution
import io.github.yesidrobayo.shopledger.domain.model.returns.ReturnLineRequest
import io.github.yesidrobayo.shopledger.domain.model.returns.ReturnSettlement
import io.github.yesidrobayo.shopledger.domain.model.returns.SaleReturnRequest
import io.github.yesidrobayo.shopledger.domain.model.returns.SupplierReturnLineRequest
import io.github.yesidrobayo.shopledger.domain.model.returns.SupplierReturnRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.ForeignTender
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SalePaymentRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleRequest
import io.github.yesidrobayo.shopledger.domain.model.settings.CsvSeparator
import io.github.yesidrobayo.shopledger.domain.model.settings.CurrencyCode
import io.github.yesidrobayo.shopledger.domain.model.settings.ReferenceCurrency
import io.github.yesidrobayo.shopledger.domain.model.settings.RegionalSettingsInput
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.PaymentMethodNames
import io.github.yesidrobayo.shopledger.domain.rule.ReportPeriods
import io.github.yesidrobayo.shopledger.domain.rule.catalog.ReferenceScale
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ApplyBulkPriceResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ApplyBulkPriceUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CreateProductUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CreateSupplierUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.EditProductUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveProductResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveSupplierResult
import io.github.yesidrobayo.shopledger.domain.usecase.customer.CreateCustomerUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.customer.SaveCustomerResult
import io.github.yesidrobayo.shopledger.domain.usecase.money.DocumentMoney
import io.github.yesidrobayo.shopledger.domain.usecase.money.MoneyLedgerWriter
import io.github.yesidrobayo.shopledger.domain.usecase.money.SaveMoneyAccountResult
import io.github.yesidrobayo.shopledger.domain.usecase.money.SaveMoneyAccountUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.payment.EditPaymentMethodUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.payment.SavePaymentMethodResult
import io.github.yesidrobayo.shopledger.domain.usecase.pricing.ObserveReferenceRateUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.pricing.RecordReferenceRateResult
import io.github.yesidrobayo.shopledger.domain.usecase.pricing.RecordReferenceRateUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.pricing.SavePriceListResult
import io.github.yesidrobayo.shopledger.domain.usecase.pricing.SavePriceListUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.pricing.SetListPriceResult
import io.github.yesidrobayo.shopledger.domain.usecase.pricing.SetListPriceUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.pricing.SetReferenceCurrencyResult
import io.github.yesidrobayo.shopledger.domain.usecase.pricing.SetReferenceCurrencyUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.RegisterPurchaseResult
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.RegisterPurchaseUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.domain.usecase.region.CurrencyLock
import io.github.yesidrobayo.shopledger.domain.usecase.region.SaveRegionalSettingsResult
import io.github.yesidrobayo.shopledger.domain.usecase.region.SaveRegionalSettingsUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.returns.ExchangeRequest
import io.github.yesidrobayo.shopledger.domain.usecase.returns.ExchangeResult
import io.github.yesidrobayo.shopledger.domain.usecase.returns.ExchangeSaleUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.returns.RegisterSaleReturnResult
import io.github.yesidrobayo.shopledger.domain.usecase.returns.RegisterSaleReturnUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.returns.RegisterSupplierReturnResult
import io.github.yesidrobayo.shopledger.domain.usecase.returns.RegisterSupplierReturnUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.returns.VoidSaleReturnResult
import io.github.yesidrobayo.shopledger.domain.usecase.returns.VoidSaleReturnUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.returns.VoidSupplierReturnResult
import io.github.yesidrobayo.shopledger.domain.usecase.returns.VoidSupplierReturnUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleResult
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleUseCase
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DayOfWeek
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Simulacro de devoluciones y precios sobre una base nueva, por el camino de la app: los puertos
 * de la base abierta y los casos de uso armados como los arman sus pantallas. Un día de un negocio
 * inventado: una compra, una venta con lista de precios y otra cobrada en otra moneda, una
 * devolución parcial con reembolso y otra con saldo a favor, un cambio de productos, dos
 * devoluciones al proveedor, una con reembolso y otra con saldo a favor, y la anulación de la
 * primera. Después, los precios: el costo de reposición corregido a mano, una masiva sobre una
 * lista, una cotización escrita a mano que no cambia nada sola y una masiva desde esa cotización.
 * Al final, una devolución de venta registrada por error se anula.
 *
 * La caja, el banco, los libros del cliente y del proveedor, cada fila del stock con su costo
 * promedio, los precios y la venta neta y el costo de lo vendido del día se comparan con valores
 * calculados a mano, y cada consulta de invariantes del modelo de datos devuelve cero filas,
 * también la de lo devuelto frente a lo vendido.
 *
 * El negocio, el cliente, el proveedor, los productos y los montos son inventados. La moneda es
 * `XTS`, el código de pruebas de ISO 4217, con dos decimales, y la otra moneda es `XXX`, el de
 * "sin moneda", también con dos: los montos van en su unidad menor.
 */
@RunWith(AndroidJUnit4::class)
internal class ReturnsAndPricesDrillTest : CatalogDatabaseTest() {
    // La base nace sin moneda, como en un teléfono nuevo, y la persona la confirma en su pantalla.
    override val confirmsCurrency: Boolean = false

    /** Un reloj que avanza un segundo en cada lectura, todo dentro del mismo día del negocio. */
    private val ticks = AtomicLong(CREATED_AT)
    private val clock = Clock { Instant.fromEpochMilliseconds(ticks.addAndGet(1_000L)) }

    @Test
    fun aDayOfReturnsAndPricesMatchesHandWrittenBooks() = runBlocking {
        val ports = DataPorts(database, file, Dispatchers.IO, clock, RestoreLock { false })
        val tx = ports.transactions
        val calendar = BusinessCalendarSource(ports.settings)
        val writer = MoneyLedgerWriter(ports.moneyAccounts, ports.moneyLedger)

        // Configuración, como en sus pantallas: la moneda, la moneda de referencia, un banco, el
        // efectivo enlazado a la caja inicial, dos productos, un proveedor, un cliente con solo su
        // nombre y una lista mayorista con precio propio para el jabón.
        assertEquals(
            SaveRegionalSettingsResult.Saved,
            SaveRegionalSettingsUseCase(tx, ports.settings, clock)(REGION),
        )
        assertEquals(
            SetReferenceCurrencyResult.Saved,
            SetReferenceCurrencyUseCase(tx, ports.settings, clock)(REFERENCE),
        )
        val saveAccount =
            SaveMoneyAccountUseCase(tx, ports.settings, ports.moneyAccounts, writer, clock)
        val bankDraft = MoneyAccountDraft(
            "Banco inventado",
            MoneyAccountKind.BANK,
            openingBalance = Money(50_000),
        )
        val bank = (saveAccount(null, bankDraft) as SaveMoneyAccountResult.Saved).accountId
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
        val createProduct = CreateProductUseCase(
            tx,
            ports.products,
            ports.categories,
            ports.suppliers,
            ports.units,
            ports.systemRows,
            ports.priceHistory,
            ports.stockLedger,
            ports.lowStockAlerts,
            calendar,
            CurrencyLock(ports.settings),
            clock, ports.mediaAssets,
        )
        val soap = savedProduct(createProduct(SOAP, QuantityMilli(10_000)))
        val candle = savedProduct(createProduct(CANDLE, QuantityMilli(10_000)))
        val supplier = (
            CreateSupplierUseCase(tx, ports.suppliers, clock)(SupplierForm("Proveedor inventado"))
                as SaveSupplierResult.Saved
            ).supplierId
        val customer = (
            CreateCustomerUseCase(tx, ports.customers, clock)(CustomerForm("Cliente inventado"))
                as SaveCustomerResult.Saved
            ).customerId
        val wholesale = (
            SavePriceListUseCase(tx, ports.priceLists, ports.productPrices, clock)(
                null,
                PriceListDraft("Mayorista inventada", PriceListKind.FIXED, null, null),
            ) as SavePriceListResult.Saved
            ).id
        val setListPrice = SetListPriceUseCase(
            tx,
            ports.priceLists,
            ports.productPrices,
            ports.products,
            ports.priceHistory,
            calendar,
            CurrencyLock(ports.settings),
            clock,
        )
        assertTrue(setListPrice(wholesale, soap, Money(3_000)) is SetListPriceResult.Changed)
        val defaultList = count("SELECT id FROM price_lists WHERE system_key IS NOT NULL")

        // Los casos de uso de cada pantalla, con los mismos puertos que les da la app.
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
        )
        val exchange = ExchangeSaleUseCase(tx, registerSale, registerReturn)
        val registerSupplierReturn = RegisterSupplierReturnUseCase(
            transactions = tx,
            settings = ports.settings,
            products = ports.products,
            units = ports.units,
            purchases = ports.purchases,
            purchaseReturns = ports.purchaseReturns,
            suppliers = ports.suppliers,
            supplierLedger = ports.supplierLedger,
            systemRows = ports.systemRows,
            ledger = ports.stockLedger,
            clock = clock,
            money = DocumentMoney(ports.moneyAccounts, ports.moneyLedger),
            alerts = ports.lowStockAlerts,
        )
        val voidSupplierReturn = VoidSupplierReturnUseCase(
            transactions = tx,
            settings = ports.settings,
            products = ports.products,
            purchaseReturns = ports.purchaseReturns,
            supplierLedger = ports.supplierLedger,
            ledger = ports.stockLedger,
            clock = clock,
            money = DocumentMoney(ports.moneyAccounts, ports.moneyLedger),
            alerts = ports.lowStockAlerts,
        )
        val voidSaleReturn = VoidSaleReturnUseCase(
            transactions = tx,
            settings = ports.settings,
            products = ports.products,
            sales = ports.sales,
            returns = ports.saleReturns,
            ledger = ports.stockLedger,
            clock = clock,
            money = DocumentMoney(ports.moneyAccounts, ports.moneyLedger),
            customerLedger = ports.customerLedger,
            alerts = ports.lowStockAlerts,
        )
        val editProduct = EditProductUseCase(
            ports.transactions, ports.products, ports.categories, ports.suppliers,
            ports.units, ports.systemRows, ports.priceHistory, ports.stockLedger,
            ports.lowStockAlerts, calendar, CurrencyLock(ports.settings), clock,
            ports.productComponents, ports.mediaAssets,
        )
        val applyBulk = ApplyBulkPriceUseCase(
            tx,
            ports.products,
            ports.systemRows,
            ports.priceHistory,
            calendar,
            CurrencyLock(ports.settings),
            clock,
            priceLists = ports.priceLists,
            productPrices = ports.productPrices,
        )
        val recordRate = RecordReferenceRateUseCase(tx, ports.settings, ports.fxRates, clock)
        val referenceRate = ObserveReferenceRateUseCase(ports.settings, ports.fxRates)

        // 1. Compra de diez jabones a 1.500, pagada desde el banco: 15.000.
        val purchase = (
            registerPurchase(
                PurchaseRequest(
                    lines = listOf(
                        PurchaseLineRequest(
                            soap,
                            QuantityMilli(10_000),
                            PurchaseLineAmount.LineTotal(Money(15_000)),
                        ),
                    ),
                    supplierId = supplier,
                    moneyAccountId = bank,
                ),
            ) as RegisterPurchaseResult.Registered
            ).purchaseId
        val purchaseLine = ports.purchases.findLines(purchase).single().id

        // 2. Venta con la lista mayorista: cuatro jabones a 3.000, del cliente, en efectivo.
        val listSale = registered(
            registerSale(
                SaleRequest(
                    lines = listOf(SaleLineRequest(soap, QuantityMilli(4_000))),
                    paymentMethodId = cashMethod,
                    customerId = customer,
                    priceListId = wholesale,
                ),
            ),
        )
        // 3. Venta cobrada en otra moneda: dos velas por 4.000, pagadas con 18,00 de la otra
        // moneda a 2,50, que son 4.500 en la del negocio. El cambio, 500, en la del negocio.
        val foreignSale = registered(
            registerSale(
                SaleRequest(
                    lines = listOf(SaleLineRequest(candle, QuantityMilli(2_000))),
                    paymentMethodId = cashMethod,
                    payments = listOf(
                        SalePaymentRequest(
                            cashMethod,
                            Money(4_000),
                            foreign = ForeignTender(REFERENCE.code, 2, 1_800, 2_500_000),
                        ),
                    ),
                ),
            ),
        )

        // 4. Devolución parcial con reembolso: un jabón de la venta mayorista, 3.000 de la caja.
        val listLine = ports.sales.findLines(listSale).single().id
        val refunded = returned(
            registerReturn(
                SaleReturnRequest(
                    listSale,
                    listOf(ReturnLineRequest(listLine, QuantityMilli(1_000))),
                    ReturnSettlement.Refund(drawer),
                ),
            ),
        )
        // 5. Otra con saldo a favor: un jabón más, que queda como 3.000 a favor del cliente.
        val credited = returned(
            registerReturn(
                SaleReturnRequest(
                    listSale,
                    listOf(ReturnLineRequest(listLine, QuantityMilli(1_000))),
                    ReturnSettlement.StoreCredit(null),
                ),
            ),
        )
        // 6. Cambio: una vela de la venta en otra moneda por un jabón a 3.500. La vela sale de la
        // caja como reembolso de 2.000 y el jabón entra cobrado en efectivo: la caja suma 1.500.
        val foreignLine = ports.sales.findLines(foreignSale).single().id
        val exchanged = exchange(
            ExchangeRequest(
                saleId = foreignSale,
                lines = listOf(ReturnLineRequest(foreignLine, QuantityMilli(1_000))),
                replacement = SaleRequest(
                    listOf(SaleLineRequest(soap, QuantityMilli(1_000))),
                    cashMethod,
                ),
                refundAccountId = drawer,
            ),
        ) as ExchangeResult.Exchanged

        // 7. Devolución al proveedor con reembolso: dos jabones de la compra, 3.000 al banco.
        val supplierRefund = supplierReturned(
            registerSupplierReturn(
                SupplierReturnRequest(
                    lines = listOf(
                        SupplierReturnLineRequest.FromPurchaseLine(
                            purchaseLine,
                            QuantityMilli(2_000),
                        ),
                    ),
                    resolution = PurchaseReturnResolution.CASH_REFUND,
                    purchaseId = purchase,
                    moneyAccountId = bank,
                ),
            ),
        )
        // 8. Otra con saldo a favor: un jabón, 1.500 que el proveedor queda debiendo.
        val supplierCredit = supplierReturned(
            registerSupplierReturn(
                SupplierReturnRequest(
                    lines = listOf(
                        SupplierReturnLineRequest.FromPurchaseLine(
                            purchaseLine,
                            QuantityMilli(1_000),
                        ),
                    ),
                    resolution = PurchaseReturnResolution.SUPPLIER_CREDIT,
                    purchaseId = purchase,
                ),
            ),
        )
        // 9. La del reembolso se registró por error y se anula desde su detalle.
        assertEquals(
            VoidSupplierReturnResult.Voided,
            voidSupplierReturn(supplierRefund, VoidReason.ENTRY_ERROR, null),
        )

        // 10. Costo de reposición de la vela corregido a mano en su formulario: 1.000. El costo
        // promedio no cambia.
        val candleBefore = requireNotNull(ports.products.findById(candle))
        assertEquals(
            SaveProductResult.Saved(candle),
            editProduct(
                candle,
                CANDLE.copy(
                    salePrice = candleBefore.salePrice,
                    replacementCost = Money(1_000),
                ),
            ),
        )

        // 11. Masiva sobre la lista mayorista, +10,00 %: el propio del jabón pasa de 3.000 a
        // 3.300, y la vela, que tomaba el de venta de 2.000, queda con 2.200 propio.
        val byList = applyBulk(
            listOf(soap, candle),
            BulkPriceRequest(
                BulkPriceScope.All,
                BulkPriceAdjustment.Percent(BasisPoints(1_000L)),
                Money.ZERO,
                priceListIds = setOf(wholesale),
            ),
        ) as ApplyBulkPriceResult.Applied
        assertEquals(2, byList.changed)

        // 12. Una cotización escrita a mano, 2,40, no cambia ningún precio ni ningún historial.
        val pricesBefore = prices(soap, candle, wholesale)
        val historyBefore = count("SELECT COUNT(*) FROM product_price_history")
        assertTrue(recordRate(2_400_000L, "Escrita a mano") is RecordReferenceRateResult.Recorded)
        assertEquals(pricesBefore, prices(soap, candle, wholesale))
        assertEquals(historyBefore, count("SELECT COUNT(*) FROM product_price_history"))

        // 13. Masiva por cotización, con la vigente y la escala de las dos monedas, como la arma
        // su pantalla: 15,00 de referencia a 2,40 dan 3.600 de venta para el jabón. La vela no
        // tiene precio de referencia y queda fuera.
        val view = referenceRate().first()
        val rate = requireNotNull(view.latest).content.rateMicro
        assertEquals(2_400_000L, rate)
        val byRate = applyBulk(
            listOf(soap, candle),
            BulkPriceRequest(
                BulkPriceScope.All,
                BulkPriceAdjustment.ReferenceRate(rate),
                Money.ZERO,
            ),
            BulkPriceContext(
                scale = ReferenceScale(REGION.currencyDecimals, REFERENCE.decimals),
            ),
        ) as ApplyBulkPriceResult.Applied
        assertEquals(1, byRate.changed)

        // 14. Otra cotización, 2,60: el jabón sigue en 3.600, porque nada se recalcula solo.
        val pricesAfterBulk = prices(soap, candle, wholesale)
        assertTrue(recordRate(2_600_000L, null) is RecordReferenceRateResult.Recorded)
        assertEquals(pricesAfterBulk, prices(soap, candle, wholesale))
        assertEquals(2, referenceRate().first().history.size)

        // 15. Una devolución de un jabón más, con reembolso de la caja, registrada por error y
        // anulada desde su hoja: el jabón vuelve a salir, el reembolso vuelve a la caja y el costo
        // se reproduce como si no hubiera existido.
        val wrongReturn = returned(
            registerReturn(
                SaleReturnRequest(
                    listSale,
                    listOf(ReturnLineRequest(listLine, QuantityMilli(1_000))),
                    ReturnSettlement.Refund(drawer),
                ),
            ),
        )
        assertEquals(
            VoidSaleReturnResult.Voided,
            voidSaleReturn(wrongReturn, VoidReason.ENTRY_ERROR, null),
        )

        // La caja, fila por fila: 12.000 y 4.000 de las ventas, 3.000 del reembolso, en el cambio
        // 3.500 que entran y 2.000 que salen, y el reembolso anulado, que suma 0. Quedan 14.500.
        assertEquals(
            listOf(
                "SALE_RECEIPT 12000 12000",
                "SALE_RECEIPT 4000 16000",
                "REFUND -3000 13000",
                "SALE_RECEIPT 3500 16500",
                "REFUND -2000 14500",
                "REFUND -3000 11500",
                "MOVEMENT_VOID 3000 14500",
            ),
            moneyRows(drawer),
        )
        assertEquals(Money(14_500), requireNotNull(ports.moneyAccounts.findById(drawer)).balance)
        // El banco: 50.000 iniciales, 15.000 de la compra, y el reembolso del proveedor de 3.000
        // con su anulación, que suman cero. Quedan 35.000.
        assertEquals(
            listOf(
                "OPENING_BALANCE 50000 50000",
                "PURCHASE_PAYMENT -15000 35000",
                "SUPPLIER_REFUND 3000 38000",
                "MOVEMENT_VOID -3000 35000",
            ),
            moneyRows(bank),
        )
        assertEquals(Money(35_000), requireNotNull(ports.moneyAccounts.findById(bank)).balance)

        // El pago en otra moneda: la venta y el pago siguen en la del negocio, y lo recibido va
        // aparte, con su cotización.
        assertEquals(
            listOf("4000 4500 500 XXX 2 1800 2500000"),
            rows(
                "SELECT amount || ' ' || tendered_amount || ' ' || change_amount || ' ' || " +
                    "tender_currency_code || ' ' || tender_currency_decimals || ' ' || " +
                    "tender_amount || ' ' || fx_rate_micro FROM sale_payments " +
                    "WHERE sale_id = $foreignSale",
            ),
        )
        assertEquals(
            Money(4_000),
            requireNotNull(ports.sales.findById(foreignSale)).content.total,
        )

        // Los libros del cliente y del proveedor: el saldo a favor de 3.000 baja lo que debe el
        // cliente, y el proveedor queda debiendo 1.500; el reembolso anulado no toca su libro.
        assertEquals(
            listOf("RETURN_CREDIT -3000 -3000"),
            rows(
                "SELECT type || ' ' || amount || ' ' || balance_after " +
                    "FROM customer_ledger_entries WHERE customer_id = $customer ORDER BY id",
            ),
        )
        assertEquals(Money(-3_000), requireNotNull(ports.customers.findById(customer)).balance)
        assertEquals(
            listOf("RETURN_CREDIT -1500 -1500"),
            rows(
                "SELECT type || ' ' || amount || ' ' || balance_after " +
                    "FROM supplier_ledger_entries WHERE supplier_id = $supplier ORDER BY id",
            ),
        )
        assertEquals(Money(-1_500), requireNotNull(ports.suppliers.findById(supplier)).balance)

        // El jabón, fila por fila: tipo, cantidad, costo, valor del stock y promedio después.
        // Entra a 1.200 y a 1.500; las ventas salen al promedio, cada devolución entra con su
        // costo congelado de 1.350, la del proveedor sale al costo de su línea y recalcula el
        // promedio, y cada anulación lo reproduce como si no hubiera existido.
        assertEquals(
            listOf(
                "OPENING_BALANCE 10000 12000 12000 1200",
                "PURCHASE 10000 15000 27000 1350",
                "SALE -4000 -5400 21600 1350",
                "SALE_RETURN 1000 1350 22950 1350",
                "SALE_RETURN 1000 1350 24300 1350",
                "SALE -1000 -1350 22950 1350",
                "PURCHASE_RETURN -2000 -3000 19950 1330",
                "PURCHASE_RETURN -1000 -1500 18450 1318",
                "PURCHASE_RETURN_VOID 2000 3000 21450 1341",
                "SALE_RETURN 1000 1350 22800 1341",
                "SALE_RETURN_VOID -1000 -1350 21450 1341",
            ),
            stockRows(soap),
        )
        assertEquals(
            listOf(
                "OPENING_BALANCE 10000 9000 9000 900",
                "SALE -2000 -1800 7200 900",
                "SALE_RETURN 1000 900 8100 900",
            ),
            stockRows(candle),
        )
        val soapNow = requireNotNull(ports.products.findById(soap))
        assertEquals(QuantityMilli(16_000), soapNow.onHand)
        assertEquals(Money(21_450), soapNow.stockValue)
        assertEquals(Money(1_341), soapNow.averageCost)
        val candleNow = requireNotNull(ports.products.findById(candle))
        assertEquals(QuantityMilli(9_000), candleNow.onHand)
        assertEquals(Money(900), candleNow.averageCost)

        // La reposición: la compra dejó 1.500 en el jabón, y la vela tiene el 1.000 escrito a
        // mano, fechado. Cada venta congeló la suya: 1.500 el jabón, y la vela, sin reposición
        // todavía, su promedio de 900.
        assertEquals(Money(1_500), soapNow.replacementCost)
        assertEquals(Money(1_000), candleNow.replacementCost)
        assertTrue(candleNow.replacementCostAt != null)

        // Los precios: el de venta del jabón salió de la cotización, el de la vela no cambió, y
        // la mayorista tiene los de la masiva. Ninguna venta pasada cambió su precio ni su lista.
        assertEquals(
            listOf(Money(3_600), Money(2_000), Money(3_300), Money(2_200)),
            prices(soap, candle, wholesale),
        )
        assertEquals(
            listOf(
                "$listSale 3000 $wholesale 1500",
                "$foreignSale 2000 $defaultList 900",
                "${exchanged.replacementSaleId} 3500 $defaultList 1500",
            ),
            rows(
                "SELECT sale_id || ' ' || unit_price || ' ' || price_list_id || ' ' || " +
                    "unit_replacement_cost FROM sale_lines ORDER BY id",
            ),
        )
        assertEquals(
            1L,
            count(
                "SELECT COUNT(*) FROM product_price_history WHERE source = 'BULK' " +
                    "AND bulk_mode = 'REFERENCE_RATE' AND fx_rate_micro = 2400000 " +
                    "AND product_id = $soap AND old_price = 3500 AND new_price = 3600",
            ),
        )

        // Los documentos: las devoluciones de venta, cada una con su resolución, la de cambio
        // nombra la venta nueva y la última quedó anulada; de las dos al proveedor, la primera.
        assertEquals(
            listOf(
                "$refunded REFUND 3000 1350 ACTIVE",
                "$credited STORE_CREDIT 3000 1350 ACTIVE",
                "${exchanged.saleReturnId} EXCHANGE 2000 900 ACTIVE",
                "$wrongReturn REFUND 3000 1350 VOIDED",
            ),
            rows(
                "SELECT id || ' ' || resolution || ' ' || net_refund || ' ' || cost_returned " +
                    "|| ' ' || status FROM sale_returns ORDER BY id",
            ),
        )
        assertEquals(
            exchanged.replacementSaleId,
            count(
                "SELECT replacement_sale_id FROM sale_returns " +
                    "WHERE id = ${exchanged.saleReturnId}",
            ),
        )
        assertEquals(
            listOf("$supplierRefund VOIDED", "$supplierCredit ACTIVE"),
            rows("SELECT id || ' ' || status FROM purchase_returns ORDER BY id"),
        )

        // El día según la sección de ganancias: ventas netas de 12.000, 4.000 y 3.500 menos lo
        // devuelto, 3.000, 3.000 y 2.000, dan 11.500; su costo, 5.400, 1.800 y 1.350, menos lo
        // que volvió, 1.350, 1.350 y 900, da 4.950. La devolución anulada no cuenta, y la del
        // proveedor no toca ninguna.
        val today = requireNotNull(ports.sales.findById(listSale)).content.businessDate
        val day = ReportPeriods.day(today)
        val profit = ports.periodReports.observeTotals(day).first().profit
        assertEquals(Money(19_500), profit.salesNetTotal)
        assertEquals(Money(8_000), profit.returnsNetRefund)
        assertEquals(Money(8_550), profit.salesTotalCost)
        assertEquals(Money(3_600), profit.returnsCostReturned)
        assertEquals(Money(11_500), profit.salesNetTotal - profit.returnsNetRefund)
        assertEquals(Money(4_950), profit.salesTotalCost - profit.returnsCostReturned)

        // Cada consulta de invariantes da cero filas, también la de lo devuelto frente a lo
        // vendido y las de los libros del cliente, del proveedor y del dinero.
        val labels = InvariantQueries.MODEL.map { it.label }
        for (invariant in listOf("I-14", "I-15", "I-16", "I-19", "I-20")) {
            assertTrue(invariant, labels.any { it == invariant || it.startsWith("$invariant,") })
        }
        for (query in InvariantQueries.MODEL) {
            val sql = query.sql.trimEnd().removeSuffix(";")
            assertEquals(query.label, 0L, count("SELECT COUNT(*) FROM ($sql)"))
        }
    }

    /** El precio de venta de [soap] y [candle], y su precio propio en [list], en ese orden. */
    private suspend fun prices(soap: Long, candle: Long, list: Long): List<Money> = listOf(
        Money(count("SELECT sale_price FROM products WHERE id = $soap")),
        Money(count("SELECT sale_price FROM products WHERE id = $candle")),
        Money(ownPrice(list, soap)),
        Money(ownPrice(list, candle)),
    )

    private suspend fun ownPrice(list: Long, product: Long): Long = count(
        "SELECT price FROM product_prices WHERE price_list_id = $list AND product_id = $product",
    )

    /** Las filas de la cuenta [account]: tipo, monto y saldo después, por orden del libro. */
    private suspend fun moneyRows(account: Long): List<String> = rows(
        "SELECT type || ' ' || amount || ' ' || balance_after FROM money_movements " +
            "WHERE money_account_id = $account ORDER BY id",
    )

    /** Las filas de [product]: tipo, cantidad, costo, valor del stock y costo promedio después. */
    private suspend fun stockRows(product: Long): List<String> = rows(
        "SELECT type || ' ' || quantity_milli || ' ' || total_cost || ' ' || " +
            "stock_value_after || ' ' || average_cost_after FROM stock_movements " +
            "WHERE product_id = $product ORDER BY id",
    )

    private suspend fun rows(sql: String): List<String> =
        database.useReaderConnection { it.texts(sql) }

    private fun savedProduct(result: SaveProductResult): Long =
        (result as SaveProductResult.Saved).productId

    private fun registered(result: RegisterSaleResult): Long =
        (result as RegisterSaleResult.Registered).saleId

    private fun returned(result: RegisterSaleReturnResult): Long =
        (result as RegisterSaleReturnResult.Registered).saleReturnId

    private fun supplierReturned(result: RegisterSupplierReturnResult): Long =
        (result as RegisterSupplierReturnResult.Registered).purchaseReturnId

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

        /** La moneda de referencia, con dos decimales: la de las cotizaciones escritas a mano. */
        val REFERENCE = ReferenceCurrency(requireNotNull(CurrencyCode.of("XXX")), 2)

        /** El jabón tiene precio de referencia: 15,00 en la otra moneda. */
        val SOAP = ProductForm(
            name = "Jabón inventado",
            salePrice = Money(3_500),
            cost = Money(1_200),
            referencePrice = Money(1_500),
        )
        val CANDLE =
            ProductForm(name = "Vela inventada", salePrice = Money(2_000), cost = Money(900))
    }
}
