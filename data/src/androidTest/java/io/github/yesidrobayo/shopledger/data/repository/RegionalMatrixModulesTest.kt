package io.github.yesidrobayo.shopledger.data.repository

import android.net.Uri
import androidx.room3.useReaderConnection
import io.github.yesidrobayo.shopledger.data.DataPorts
import io.github.yesidrobayo.shopledger.data.backup.BackupPackager
import io.github.yesidrobayo.shopledger.data.backup.CandidateUnpacker
import io.github.yesidrobayo.shopledger.data.backup.CandidateValidation
import io.github.yesidrobayo.shopledger.data.backup.InvariantQueries
import io.github.yesidrobayo.shopledger.data.backup.MemoryLog
import io.github.yesidrobayo.shopledger.data.backup.PackageResult
import io.github.yesidrobayo.shopledger.data.backup.SnapshotCreator
import io.github.yesidrobayo.shopledger.data.backup.SnapshotResult
import io.github.yesidrobayo.shopledger.data.backup.UnpackResult
import io.github.yesidrobayo.shopledger.data.backup.findings
import io.github.yesidrobayo.shopledger.data.backup.validate
import io.github.yesidrobayo.shopledger.data.export.CsvExporter
import io.github.yesidrobayo.shopledger.data.export.FileDocument
import io.github.yesidrobayo.shopledger.data.export.SummaryPackageWriter
import io.github.yesidrobayo.shopledger.data.export.csvCells
import io.github.yesidrobayo.shopledger.data.export.csvLines
import io.github.yesidrobayo.shopledger.data.export.recordCsvExported
import io.github.yesidrobayo.shopledger.data.export.unzipCsv
import io.github.yesidrobayo.shopledger.data.imports.DocumentImportReader
import io.github.yesidrobayo.shopledger.data.local.catalog.CatalogDatabaseTest
import io.github.yesidrobayo.shopledger.data.local.catalog.KILOGRAM_ID
import io.github.yesidrobayo.shopledger.data.local.inspectWithoutRoom
import io.github.yesidrobayo.shopledger.data.local.texts
import io.github.yesidrobayo.shopledger.data.transaction.RestoreLock
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Margin
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.ProfitSummary
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.SeedTable
import io.github.yesidrobayo.shopledger.domain.model.backup.CandidateCheck
import io.github.yesidrobayo.shopledger.domain.model.backup.CandidateVerdict
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductForm
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.document.ReceiptAmount
import io.github.yesidrobayo.shopledger.domain.model.expense.ExpenseRequest
import io.github.yesidrobayo.shopledger.domain.model.export.CsvExport
import io.github.yesidrobayo.shopledger.domain.model.export.CsvExportTable
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportApplyResult
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportExistingPolicy
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportFileRead
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportRowIssue
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportSheet
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportTarget
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodDraft
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodKind
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseLineAmount
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseLineRequest
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseRequest
import io.github.yesidrobayo.shopledger.domain.model.report.ManagementSummary
import io.github.yesidrobayo.shopledger.domain.model.report.PaymentMethodTotal
import io.github.yesidrobayo.shopledger.domain.model.report.PeriodMetrics
import io.github.yesidrobayo.shopledger.domain.model.report.SummaryFile
import io.github.yesidrobayo.shopledger.domain.model.sale.LineDiscount
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SalePaymentRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleRequest
import io.github.yesidrobayo.shopledger.domain.model.settings.RegionalSettingsInput
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxMode
import io.github.yesidrobayo.shopledger.domain.model.tax.TaxEstimate
import io.github.yesidrobayo.shopledger.domain.model.tax.TaxRateDraft
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.CsvNaming
import io.github.yesidrobayo.shopledger.domain.port.PaymentMethodNames
import io.github.yesidrobayo.shopledger.domain.port.SummaryNaming
import io.github.yesidrobayo.shopledger.domain.rule.document.SaleReceiptDocument
import io.github.yesidrobayo.shopledger.domain.rule.export.CsvRowWriter
import io.github.yesidrobayo.shopledger.domain.rule.imports.ColumnMatch
import io.github.yesidrobayo.shopledger.domain.rule.imports.ImportColumnMatcher
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendar
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import io.github.yesidrobayo.shopledger.domain.rule.region.RegionalSettingsProblem
import io.github.yesidrobayo.shopledger.domain.testing.MatrixRegion
import io.github.yesidrobayo.shopledger.domain.testing.MatrixSale
import io.github.yesidrobayo.shopledger.domain.testing.RegionalMatrix
import io.github.yesidrobayo.shopledger.domain.testing.RegionalMatrixRow
import io.github.yesidrobayo.shopledger.domain.testing.RegionalMatrixSale
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CreateProductUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveProductResult
import io.github.yesidrobayo.shopledger.domain.usecase.expense.RegisterExpenseResult
import io.github.yesidrobayo.shopledger.domain.usecase.expense.RegisterExpenseUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.export.ExportCsvUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.imports.ApplyImportUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.imports.ImportWrites
import io.github.yesidrobayo.shopledger.domain.usecase.imports.PrepareImportUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.money.DocumentMoney
import io.github.yesidrobayo.shopledger.domain.usecase.payment.EditPaymentMethodUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.payment.SavePaymentMethodResult
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.RegisterPurchaseResult
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.RegisterPurchaseUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.domain.usecase.region.CurrencyLock
import io.github.yesidrobayo.shopledger.domain.usecase.region.SaveRegionalSettingsResult
import io.github.yesidrobayo.shopledger.domain.usecase.region.SaveRegionalSettingsUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.report.CreateManagementSummaryUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.report.ObserveManagementSummaryUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.report.ObservePeriodReportUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.report.PeriodReport
import io.github.yesidrobayo.shopledger.domain.usecase.report.SummaryTarget
import io.github.yesidrobayo.shopledger.domain.usecase.sale.ObserveSaleDetailUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleResult
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.tax.ChangeTaxSettingsResult
import io.github.yesidrobayo.shopledger.domain.usecase.tax.ChangeTaxSettingsUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.tax.CreateTaxRateUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.tax.ObserveTaxEstimateUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.tax.SaveTaxRateResult
import io.github.yesidrobayo.shopledger.domain.usecase.tax.TaxSettingsChange
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipInputStream
import kotlin.random.Random
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.YearMonth
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Cada fila de la matriz de configuraciones, recorrida por todos los módulos sobre una base nueva
 * y por el camino de la app: los puertos de la base abierta y los casos de uso armados como los
 * arman sus pantallas. Es el único lugar de `:data` donde se nombran monedas, zonas y formatos
 * reales, y son datos de prueba: ninguna regla los supone.
 *
 * La persona confirma la moneda, el redondeo del efectivo, el formato, la zona y la semana de la
 * fila, y el modo de impuesto con una tasa del 19 %. A las 04:30 UTC del 2 de noviembre de 2026
 * vende dos productos, cada uno con su descuento, con un descuento de toda la venta y dos pagos,
 * compra, gasta e importa un producto por CSV. Después se leen las estadísticas del día y de la
 * semana, el resumen de gestión con su paquete, la exportación en CSV y en hoja de cálculo y el
 * comprobante, y cada cifra se compara con la de [RegionalMatrixSale], escrita a mano: los mismos
 * enteros en unidad menor en todas las filas, con los decimales y el separador de la fila en los
 * archivos. La fecha de negocio y el comienzo de la semana de cada fila están escritos a mano en
 * [DAYS]. Al final, la copia de esa base pasa la validación con el teléfono en otro idioma.
 *
 * La fila sin moneda confirmada no puede registrar dinero: la venta, el gasto y un producto con
 * precio se rechazan, y su copia también se valida.
 *
 * El negocio, los productos y los montos son inventados.
 */
@RunWith(Parameterized::class)
internal class RegionalMatrixModulesTest(
    private val position: Int,
    private val row: RegionalMatrixRow,
) : CatalogDatabaseTest() {
    // La base nace sin moneda, como en un teléfono nuevo, y la persona la confirma en su pantalla.
    // Así la caja inicial toma la moneda de la fila en esa misma transacción.
    override val confirmsCurrency: Boolean = false

    /** Un reloj que avanza un segundo en cada lectura, desde el instante de la venta. */
    private val ticks = AtomicLong(SALE_AT)
    private val clock = Clock { Instant.fromEpochMilliseconds(ticks.addAndGet(1_000L)) }

    private val region: MatrixRegion = row.regionOr(RegionalMatrix.INVENTED_DEVICE)
    private val separator: Char = region.csvSeparator.char
    private val day: MatrixDay? = DAYS[position]

    private lateinit var outputs: File

    @Before
    fun createFolder() {
        outputs = File(context.noBackupFilesDir, "regional-matrix-" + Random.nextLong().toULong())
        outputs.mkdirs()
    }

    @After
    fun deleteFolder() {
        outputs.deleteRecursively()
    }

    // Los casos de uso de cada pantalla, con los mismos puertos que les da la app.
    private val ports by lazy {
        DataPorts(database, file, Dispatchers.IO, clock, RestoreLock { false })
    }

    private val tx by lazy {
        ports.transactions
    }

    private val calendar by lazy {
        BusinessCalendarSource(ports.settings)
    }

    private val createProduct by lazy {
        CreateProductUseCase(
            tx, ports.products, ports.categories, ports.suppliers, ports.units,
            ports.systemRows, ports.priceHistory, ports.stockLedger, ports.lowStockAlerts,
            calendar, CurrencyLock(ports.settings), clock, ports.mediaAssets,
        )
    }

    private val registerSale by lazy {
        RegisterSaleUseCase(
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
            quotes = ports.quotes,
            orders = ports.orders,
        )
    }

    private val registerPurchase by lazy {
        RegisterPurchaseUseCase(
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
    }

    private val registerExpense by lazy {
        RegisterExpenseUseCase(
            transactions = tx,
            settings = ports.settings,
            expenses = ports.expenses,
            categories = ports.expenseCategories,
            clock = clock,
            money = DocumentMoney(ports.moneyAccounts, ports.moneyLedger),
        )
    }

    private val saleDetail by lazy {
        ObserveSaleDetailUseCase(
            ports.sales,
            ports.salePayments,
            ports.products,
            ports.saleReturns,
        )
    }

    private val reports by lazy {
        ObservePeriodReportUseCase(ports.periodReports)
    }

    private val createSummary by lazy {
        CreateManagementSummaryUseCase(
            ports.settings,
            SummaryPackageWriter(
                rows = { ports.csvRows },
                recordExport = { recordCsvExported(ports, clock, MemoryLog()) },
                log = MemoryLog(),
                ioContext = Dispatchers.IO,
            ),
        ) { region.decimalSeparator }
    }

    private val exportFiles = HashMap<String, File>()

    private val exportData by lazy {
        val exporter = CsvExporter(
            rows = { ports.csvRows },
            documentFor = { uri -> FileDocument(exportFiles.getValue(uri)) },
            recordExport = { recordCsvExported(ports, clock, MemoryLog()) },
            log = MemoryLog(),
            ioContext = Dispatchers.IO,
        )
        ExportCsvUseCase(ports.settings, exporter) { region.decimalSeparator }
    }

    private val prepareImport by lazy {
        PrepareImportUseCase(
            products = ports.products,
            customers = ports.customers,
            suppliers = ports.suppliers,
            categories = ports.categories,
            units = ports.units,
            priceLists = ports.priceLists,
            priceIndex = ports.priceIndex,
            systemRows = ports.systemRows,
            settings = ports.settings,
            seedLabels = { _, key -> listOf("seed:$key") },
        )
    }

    private val applyImport by lazy {
        ApplyImportUseCase(
            transactions = tx,
            writes = ImportWrites(
                products = ports.products,
                categories = ports.categories,
                suppliers = ports.suppliers,
                customers = ports.customers,
                units = ports.units,
                priceIndex = ports.priceIndex,
                systemRows = ports.systemRows,
                priceHistory = ports.priceHistory,
                ledger = ports.stockLedger,
                alerts = ports.lowStockAlerts,
            ),
            calendar = calendar,
            currencyLock = CurrencyLock(ports.settings),
            clock = clock,
        )
    }

    // Lo que comparten los pasos: la caja, los dos medios de pago y los productos.
    private var drawer = 0L
    private var cashMethod = 0L
    private var cardMethod = 0L
    private var oil = 0L
    private var cheese = 0L
    private var saleId = 0L

    /** La venta, la compra y los reportes de la fila, con su modo de impuesto. */
    private val sale: MatrixSale = RegionalMatrixSale.saleFor(row.taxMode)

    /** El redondeo del efectivo de la fila: solo toca la parte en efectivo. */
    private val rounding: Long =
        RegionalMatrixSale.cashRoundingOf(sale.cashPart, row.cashRounding.minor)

    /** Lo que cobra el efectivo, con su redondeo. */
    private val cashPaid: Long = sale.cashPart + rounding

    @Test
    fun everyModuleFollowsTheRowWithTheSameMinorUnits() = runBlocking {
        val today = day
        if (today == null) {
            withoutConfirmedCurrency()
        } else {
            configure()
            registerTheDay()
            booksOfTheDay(today)
            statistics(today)
            managementSummary(today)
            exports(today)
            receipt(today)
            importProducts()
            invariants()
        }
        restoredInAnotherLanguage()
    }

    /** La moneda, el impuesto, la caja del efectivo y el catálogo, como en sus pantallas. */
    private suspend fun configure() {
        assertEquals(SaveRegionalSettingsResult.Saved, saveRegion())
        drawer = count("SELECT id FROM money_accounts WHERE system_key = 'CASH'")
        // La caja que nació sin moneda toma la de la fila al confirmarla.
        assertEquals(
            listOf("${row.currencyCode}"),
            rows("SELECT currency_code FROM money_accounts WHERE id = $drawer"),
        )

        // Una sola tasa, de 19 %, la que usa el negocio por defecto, y el modo de la fila. El
        // impuesto de las compras se recupera: su costo es el neto.
        val rate = CreateTaxRateUseCase(tx, ports.taxRates, ports.taxSettings, clock)(
            TaxRateDraft(TAX_NAME, BasisPoints(RegionalMatrixSale.TAX_RATE_BP), true),
        )
        assertTrue("$rate", rate is SaveTaxRateResult.Saved)
        val changeTaxes = ChangeTaxSettingsUseCase(tx, ports.taxSettings, clock)
        val changes = listOf(
            TaxSettingsChange.Mode(row.taxMode),
            TaxSettingsChange.PurchaseTaxRecoverable(true),
        )
        for (change in changes) {
            val result = changeTaxes(change)
            assertTrue(
                "$change: $result",
                result == ChangeTaxSettingsResult.Changed ||
                    result == ChangeTaxSettingsResult.Unchanged,
            )
        }

        cashMethod = count("SELECT id FROM payment_methods WHERE system_key = 'CASH'")
        cardMethod = count("SELECT id FROM payment_methods WHERE system_key = 'CARD'")
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

        oil = saved(createProduct(OIL, QuantityMilli(RegionalMatrixSale.OIL_STOCK_MILLI)))
        cheese =
            saved(createProduct(CHEESE, QuantityMilli(RegionalMatrixSale.CHEESE_STOCK_MILLI)))
    }

    /** La venta con sus dos pagos, la compra y el gasto, en el mismo instante del día. */
    private suspend fun registerTheDay() {
        // Los pagos escritos suman el total: la tarjeta, lo suyo, y el efectivo, el resto. El
        // redondeo se suma después, solo al efectivo.
        val result = registerSale(
            SaleRequest(
                lines = listOf(
                    SaleLineRequest(
                        oil,
                        QuantityMilli(RegionalMatrixSale.OIL_SOLD_MILLI),
                        discount = LineDiscount.Percent(
                            BasisPoints(RegionalMatrixSale.OIL_DISCOUNT_BP),
                        ),
                    ),
                    SaleLineRequest(
                        cheese,
                        QuantityMilli(RegionalMatrixSale.CHEESE_SOLD_MILLI),
                        discount = LineDiscount.Amount(Money(RegionalMatrixSale.CHEESE_DISCOUNT)),
                    ),
                ),
                paymentMethodId = cashMethod,
                payments = listOf(
                    SalePaymentRequest(cashMethod, Money(sale.cashPart)),
                    SalePaymentRequest(cardMethod, Money(RegionalMatrixSale.CARD_PAYMENT)),
                ),
                orderAdjustment = Money(RegionalMatrixSale.ORDER_ADJUSTMENT),
            ),
        )
        saleId = (result as? RegisterSaleResult.Registered)?.saleId
            ?: throw AssertionError("No se registró: $result")

        val purchase = registerPurchase(
            PurchaseRequest(
                listOf(
                    PurchaseLineRequest(
                        oil,
                        QuantityMilli(RegionalMatrixSale.PURCHASE_MILLI),
                        PurchaseLineAmount.UnitCost(Money(RegionalMatrixSale.PURCHASE_UNIT_COST)),
                    ),
                ),
                invoiceReference = "C-0001",
            ),
        )
        assertTrue("$purchase", purchase is RegisterPurchaseResult.Registered)

        val expense = registerExpense(
            ExpenseRequest(
                Money(RegionalMatrixSale.EXPENSE),
                utilities(),
                "Luz inventada",
                moneyAccountId = drawer,
            ),
        )
        assertTrue("$expense", expense is RegisterExpenseResult.Registered)
    }

    /** Lo que quedó guardado: la venta, sus líneas y pagos, la compra, el gasto, caja y stock. */
    private suspend fun booksOfTheDay(day: MatrixDay) {
        // La fecha de negocio es la del instante en la zona de la fila.
        val (first, second) = sale.lines
        assertEquals(
            listOf(
                "${day.date} ${sale.net} ${sale.tax} ${sale.total} $rounding " +
                    "${sale.total + rounding} ${RegionalMatrixSale.SALE_COST}",
            ),
            rows(
                "SELECT business_date || ' ' || net_total || ' ' || tax_total || ' ' || total " +
                    "|| ' ' || cash_rounding_amount || ' ' || amount_due || ' ' || total_cost " +
                    "FROM sales",
            ),
        )
        assertEquals(
            listOf(
                "2420 ${RegionalMatrixSale.OIL_SHARE} ${first.net} ${first.tax} ${first.total} " +
                    "14000",
                "${RegionalMatrixSale.CHEESE_DISCOUNT} ${RegionalMatrixSale.CHEESE_SHARE} " +
                    "${second.net} ${second.tax} ${second.total} 1800",
            ),
            rows(
                "SELECT line_discount || ' ' || order_adjustment_share || ' ' || net_amount " +
                    "|| ' ' || tax_amount || ' ' || line_total || ' ' || line_cost " +
                    "FROM sale_lines ORDER BY id",
            ),
        )
        assertEquals(
            listOf("$cashPaid", "${RegionalMatrixSale.CARD_PAYMENT}"),
            rows("SELECT amount FROM sale_payments ORDER BY id"),
        )
        val purchase = RegionalMatrixSale.purchaseFor(row.taxMode)
        assertEquals(
            listOf(
                "${day.date} ${purchase.net} ${purchase.tax} ${purchase.total} ${purchase.cost}",
            ),
            rows(
                "SELECT business_date || ' ' || net_total || ' ' || tax_total || ' ' || total " +
                    "|| ' ' || cost_total FROM purchases",
            ),
        )
        assertEquals(
            listOf("${day.date} ${RegionalMatrixSale.EXPENSE}"),
            rows("SELECT business_date || ' ' || amount FROM expenses"),
        )
        // La caja recibe el efectivo con su redondeo y paga el gasto. La tarjeta no tiene cuenta.
        assertEquals(
            listOf(
                "SALE_RECEIPT $cashPaid $cashPaid",
                "EXPENSE_PAYMENT -${RegionalMatrixSale.EXPENSE} " +
                    "${cashPaid - RegionalMatrixSale.EXPENSE}",
            ),
            rows(
                "SELECT type || ' ' || amount || ' ' || balance_after FROM money_movements " +
                    "WHERE money_account_id = $drawer ORDER BY id",
            ),
        )
        // Aceite: 10 − 2 + 5 = 13, que valen 70.000 − 14.000 más el costo de la compra. Queso:
        // 5 − 1,5 = 3,5 kg, que valen 6.000 − 1.800 = 4.200.
        val oilAfter = when (row.taxMode) {
            // 56.000 + 29.412 = 85.412, y 85.412 / 13 = 6.570,2.
            TaxMode.INCLUDED -> "13000 6570 85412"
            // 56.000 + 35.000 = 91.000, y 91.000 / 13 = 7.000.
            TaxMode.NONE, TaxMode.ADDED -> "13000 7000 91000"
        }
        assertEquals(listOf(oilAfter, "3500 1200 4200"), listOf(cached(oil), cached(cheese)))
    }

    /** Las estadísticas del día y de la semana de la fila, y el otro día que no tuvo nada. */
    private suspend fun statistics(day: MatrixDay) {
        val ready = calendar.current() as BusinessCalendarResult.Ready
        assertEquals(ReportPeriod.Week(day.weekStart), ready.calendar.week(day.date))
        val metrics = PeriodMetrics(
            profit = profit(),
            salesCount = 1L,
            averageTicket = Money(sale.net),
            hasMovements = true,
        )
        for (period in listOf(ReportPeriod.Day(day.date), ReportPeriod.Week(day.weekStart))) {
            assertEquals(PeriodReport(period, metrics), reports(period).first())
        }
        // El mismo instante es otro día en otra zona: ese día no vendió ni gastó nada.
        val other = if (day.date == NOV_2) NOV_1 else NOV_2
        val empty = requireNotNull(reports(ReportPeriod.Day(other)).first().metrics)
        assertEquals(0L, empty.salesCount)
        assertFalse(empty.hasMovements)
    }

    /** El resumen de gestión de noviembre y su paquete, con las cifras escritas en la fila. */
    private suspend fun managementSummary(day: MatrixDay) {
        val ready = calendar.current() as BusinessCalendarResult.Ready
        val summary = observeSummary(ready.calendar)
        val purchase = RegionalMatrixSale.purchaseFor(row.taxMode)
        assertEquals(NOVEMBER, summary.period)
        assertEquals(profit(), summary.profit)
        assertEquals(
            when (row.taxMode) {
                TaxMode.NONE -> null
                TaxMode.INCLUDED, TaxMode.ADDED ->
                    TaxEstimate(collected = Money(sale.tax), paid = Money(purchase.tax))
            },
            summary.tax,
        )
        // El efectivo cobra más que la tarjeta, y va primero.
        assertEquals(
            listOf(
                PaymentMethodTotal(
                    "Initial cash",
                    PaymentMethodKind.CASH,
                    1L,
                    Money(cashPaid),
                    Money.ZERO,
                ),
                PaymentMethodTotal(
                    "Initial card",
                    PaymentMethodKind.CARD,
                    1L,
                    Money(RegionalMatrixSale.CARD_PAYMENT),
                    Money.ZERO,
                ),
            ),
            summary.methods,
        )
        assertEquals(0L, summary.salesWithoutCost)
        assertTrue(summary.hasMovements)

        val pdf = File(outputs, "summary.pdf").apply { writeBytes(PDF_BYTES) }
        val zip = File(outputs, "summary.zip")
        val result = createSummary(
            period = NOVEMBER,
            chosen = summary.contents.toSet(),
            offered = summary.contents,
            target = SummaryTarget(zip.absolutePath, "summary.pdf", pdf.absolutePath),
            naming = SUMMARY_NAMING,
            progress = {},
        )
        assertTrue("$result", result is CsvExport.Saved)
        val files = unzipCsv(zip)
        assertEquals(listOf(saleCells(day)), cellsOf(files, "sales.csv", *SALE_COLUMNS))
        assertEquals(
            listOf(cell(cashPaid), cell(RegionalMatrixSale.CARD_PAYMENT)),
            cellsOf(files, "sale_payments.csv", "amount"),
        )
        assertEquals(
            listOf(cell(RegionalMatrixSale.EXPENSE)),
            cellsOf(files, "expenses.csv", "amount"),
        )
    }

    /** La exportación completa en CSV, con el formato de la fila, y en hoja de cálculo. */
    private suspend fun exports(day: MatrixDay) {
        val zipFile = File(outputs, "export.zip")
        exportFiles[ZIP_URI] = zipFile
        assertTrue(exportData(ZIP_URI, CSV_NAMING) {} is CsvExport.Saved)
        val files = unzipCsv(zipFile)
        assertEquals(listOf(saleCells(day)), cellsOf(files, "sales.csv", *SALE_COLUMNS))
        val (first, second) = sale.lines
        assertEquals(
            listOf(
                "${cell(2_420L)} ${cell(RegionalMatrixSale.OIL_SHARE)} ${cell(first.net)}",
                "${cell(RegionalMatrixSale.CHEESE_DISCOUNT)} " +
                    "${cell(RegionalMatrixSale.CHEESE_SHARE)} ${cell(second.net)}",
            ),
            cellsOf(
                files,
                "sale_lines.csv",
                "line_discount",
                "order_adjustment_share",
                "net_amount",
            ),
        )
        val purchase = RegionalMatrixSale.purchaseFor(row.taxMode)
        assertEquals(
            listOf(
                "${day.date} ${cell(purchase.net)} ${cell(purchase.tax)} " +
                    "${cell(purchase.cost)} ${cell(purchase.total)}",
            ),
            cellsOf(
                files,
                "purchases.csv",
                "date",
                "net_total",
                "tax_total",
                "cost_total",
                "total",
            ),
        )

        // La hoja escribe los números con punto decimal, sea cual sea la región: la hoja de
        // cálculo los muestra con la de quien la abre. La fecha va como texto ISO.
        val xlsxFile = File(outputs, "export.xlsx")
        exportFiles[XLSX_URI] = xlsxFile
        assertTrue(exportData(XLSX_URI, CSV_NAMING, spreadsheet = true) {} is CsvExport.Saved)
        val sheet = unzipText(xlsxFile)
            .getValue("xl/worksheets/sheet${CsvExportTable.SALES.ordinal + 1}.xml")
        val numbers = listOf(
            sale.net,
            sale.tax,
            RegionalMatrixSale.ORDER_ADJUSTMENT,
            sale.total + rounding,
        )
        for (minor in numbers) {
            val number = sheetNumber(minor)
            assertTrue("$row: $number", sheet.contains("<v>$number</v>"))
        }
        assertTrue("$row: ${day.date}", sheet.contains(">${day.date}<"))
    }

    /** El comprobante: lo que cobró cada línea, el impuesto, el redondeo y los pagos. */
    private suspend fun receipt(day: MatrixDay) {
        val settings = ports.settings.get()
        val document = SaleReceiptDocument.of(
            requireNotNull(saleDetail(saleId).first()),
            settings.receipt,
            settings.profile.businessName,
            customerName = null,
            creditBefore = Money.ZERO,
        )
        assertEquals(day.date, document.businessDate)
        assertEquals(
            listOf(
                "Aceite inventado ${RegionalMatrixSale.OIL_SOLD_MILLI} " +
                    "${RegionalMatrixSale.OIL_BASE}",
                "Queso inventado ${RegionalMatrixSale.CHEESE_SOLD_MILLI} " +
                    "${RegionalMatrixSale.CHEESE_BASE}",
            ),
            document.lines.map { "${it.productName} ${it.quantity.milli} ${it.amount.minor}" },
        )
        val expected = buildList {
            add("Subtotal ${RegionalMatrixSale.SUBTOTAL}")
            add("Adjustment ${RegionalMatrixSale.ORDER_ADJUSTMENT}")
            if (sale.tax != 0L) add("Tax ${sale.tax}")
            if (rounding != 0L) add("Rounding $rounding")
            add("Total ${sale.total + rounding}")
            add("Payment $cashPaid")
            add("Payment ${RegionalMatrixSale.CARD_PAYMENT}")
        }
        assertEquals(
            expected,
            document.amounts.map { "${it.javaClass.simpleName} ${it.amount.minor}" },
        )
        // Con el impuesto dentro, subtotal y descuento ya dan el total: se informa sin sumarlo.
        val tax = document.amounts.filterIsInstance<ReceiptAmount.Tax>()
        assertEquals(
            when (row.taxMode) {
                TaxMode.NONE -> emptyList()
                TaxMode.INCLUDED -> listOf(true)
                TaxMode.ADDED -> listOf(false)
            },
            tax.map { it.included },
        )
    }

    /** Un CSV de productos escrito con los separadores y los decimales de la fila. */
    private suspend fun importProducts() {
        val csv = File(outputs, "products.csv").apply {
            writeText(
                listOf("Nombre", "Precio de venta", "Costo inicial", "Existencia")
                    .joinToString(separator.toString()) + "\r\n" +
                    listOf(
                        "Arroz inventado",
                        cell(RegionalMatrixSale.IMPORTED_PRICE),
                        cell(RegionalMatrixSale.IMPORTED_COST),
                        "12",
                    ).joinToString(separator.toString()) + "\r\n",
            )
        }
        val read = DocumentImportReader(context.contentResolver, Dispatchers.IO)
            .read(Uri.fromFile(csv).toString(), region.csvSeparator, region.csvSeparator) {}
        val sheet = (read as? ImportFileRead.Read)?.sheet
            ?: throw AssertionError("No se leyó: $read")
        val preview = prepareImport(
            sheet,
            ImportTarget.PRODUCTS,
            columnsOf(sheet),
            region.decimalSeparator,
        )
        assertEquals(emptyList<ImportRowIssue>(), preview.issues)
        assertEquals(listOf(2), preview.ready.map { it.row })
        assertEquals(
            ImportApplyResult.Applied(created = 1, updated = 0, skipped = 0),
            applyImport(preview, ImportExistingPolicy.SKIP),
        )
        val rice = count("SELECT id FROM products WHERE name = 'Arroz inventado'")
        assertEquals(
            listOf("${RegionalMatrixSale.IMPORTED_PRICE}"),
            rows("SELECT sale_price FROM products WHERE id = $rice"),
        )
        assertEquals("12000 2000 24000", cached(rice))
    }

    /** Cada consulta de invariantes da cero filas. */
    private suspend fun invariants() {
        for (query in InvariantQueries.MODEL) {
            val sql = query.sql.trimEnd().removeSuffix(";")
            assertEquals("$row: ${query.label}", 0L, count("SELECT COUNT(*) FROM ($sql)"))
        }
    }

    /** La fila sin moneda confirmada: nada con dinero se registra. */
    private suspend fun withoutConfirmedCurrency() {
        val unconfirmed = setOf(RegionalSettingsProblem.CURRENCY_UNCONFIRMED)
        assertEquals(SaveRegionalSettingsResult.Rejected(unconfirmed), saveRegion())
        assertEquals(SaveProductResult.CurrencyUnconfirmed, createProduct(OIL))
        val service = ProductForm("Servicio inventado", stockMode = ProductStockMode.UNTRACKED)
        val free = saved(createProduct(service))
        cashMethod = count("SELECT id FROM payment_methods WHERE system_key = 'CASH'")
        val line = SaleLineRequest(
            free,
            QuantityMilli(1_000L),
            unitPrice = Money(RegionalMatrixSale.OIL_PRICE),
        )
        assertEquals(
            RegisterSaleResult.CurrencyNotConfirmed,
            registerSale(SaleRequest(listOf(line), cashMethod)),
        )
        assertEquals(
            RegisterExpenseResult.CurrencyNotConfirmed,
            registerExpense(ExpenseRequest(Money(RegionalMatrixSale.EXPENSE), utilities())),
        )
        assertEquals(
            listOf("0 0 0"),
            rows(
                "SELECT (SELECT COUNT(*) FROM sales) || ' ' || (SELECT COUNT(*) FROM expenses) " +
                    "|| ' ' || (SELECT COUNT(*) FROM money_movements)",
            ),
        )
    }

    /**
     * La copia de la base, como la arma el respaldo, comprimida y vuelta a abrir, pasa la
     * validación de la restauración con el teléfono en otro idioma, y trae la moneda y la venta de
     * la fila.
     */
    private suspend fun restoredInAnotherLanguage() {
        val snapshot = File(outputs, "snapshot/snapshot.db")
        val created = SnapshotCreator(file, Dispatchers.IO).create(snapshot)
        assertTrue("$created", created is SnapshotResult.Created)
        val packed = File(outputs, "incoming/incoming.db.gz")
        requireNotNull(packed.parentFile).mkdirs()
        val pack = BackupPackager(Dispatchers.IO).compress(snapshot, packed)
        val sha256 = (pack as? PackageResult.Packed)?.fileSha256
            ?: throw AssertionError("No se comprimió: $pack")
        val candidate = File(outputs, "restore/candidate/candidate.db")
        val unpacked = CandidateUnpacker(Dispatchers.IO).unpack(packed, candidate, sha256)
        assertTrue("$unpacked", unpacked is UnpackResult.Unpacked)

        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag(otherLanguage()))
            val validation = validate(candidate)
            assertEquals("$row", emptyMap<CandidateCheck, Long>(), validation.findings())
            val verdict = (validation as CandidateValidation.Checked).verdict
            assertTrue("$row: $verdict", verdict is CandidateVerdict.Restorable)
        } finally {
            Locale.setDefault(previous)
        }

        val restored = inspectWithoutRoom(candidate) { connection ->
            val currency = "SELECT currency_code || ' ' || currency_decimals FROM app_settings"
            connection.texts(currency) + connection.texts("SELECT amount_due FROM sales")
        }
        val expected = if (day == null) {
            listOf("${row.currencyCode} 0")
        } else {
            listOf("${row.currencyCode} ${row.currencyDecimals}", "${sale.total + rounding}")
        }
        assertEquals(expected, restored)
    }

    // Apoyo

    private suspend fun saveRegion(): SaveRegionalSettingsResult =
        SaveRegionalSettingsUseCase(tx, ports.settings, clock)(
            RegionalSettingsInput(
                currencyCode = row.currencyCode,
                currencyDecimals = row.currencyDecimals,
                currencySymbol = null,
                cashRounding = row.cashRounding,
                formatLocaleTag = region.formatLocaleTag,
                businessTimeZoneId = region.businessTimeZoneId,
                weekStartDay = region.weekStartDay,
                csvSeparator = region.csvSeparator,
            ),
        )

    /**
     * La ganancia del día, de la semana y del mes: la venta es lo único que vende. El redondeo del
     * efectivo es un costo de cobrar con su signo contrario: uno que sube el cobro lo reduce.
     */
    private fun profit(): ProfitSummary {
        val paymentCosts = -rounding
        val totalExpenses = RegionalMatrixSale.EXPENSE + paymentCosts
        return ProfitSummary(
            netSales = Money(sale.net),
            cogs = Money(RegionalMatrixSale.SALE_COST),
            grossProfit = Money(sale.grossProfit),
            grossMargin = Margin.Defined(BasisPoints(sale.marginBp)),
            recordedExpenses = Money(RegionalMatrixSale.EXPENSE),
            netShrinkage = Money.ZERO,
            paymentCosts = Money(paymentCosts),
            totalExpenses = Money(totalExpenses),
            netProfit = Money(sale.grossProfit - totalExpenses),
        )
    }

    private suspend fun observeSummary(calendar: BusinessCalendar): ManagementSummary {
        val observe = ObserveManagementSummaryUseCase(
            ports.periodReports,
            ObserveTaxEstimateUseCase(ports.taxSettings, ports.taxReports),
            ports.periodBreakdowns,
            ports.managementSummaries,
        )
        return requireNotNull(observe(NOVEMBER, calendar).first())
    }

    /** Las celdas de la venta en `sales.csv`, en el orden de [SALE_COLUMNS]. */
    private fun saleCells(day: MatrixDay): String = listOf(
        day.date.toString(),
        cell(sale.net),
        cell(sale.tax),
        cell(RegionalMatrixSale.ORDER_ADJUSTMENT),
        cell(rounding),
        cell(sale.total),
        cell(sale.total + rounding),
        cell(RegionalMatrixSale.SALE_COST),
        cell(sale.grossProfit),
    ).joinToString(" ")

    /**
     * [minor] como lo escribe un CSV de la fila, armado a mano: los decimales de la moneda tras el
     * separador decimal de la región, sin miles ni símbolo, y el guion ASCII si es negativo.
     */
    private fun cell(minor: Long): String = decimal(minor, region.decimalSeparator.char)

    /** [minor] como lo escribe una hoja de cálculo: siempre con punto decimal. */
    private fun sheetNumber(minor: Long): String = decimal(minor, '.')

    private fun decimal(minor: Long, point: Char): String {
        val decimals = row.currencyDecimals
        val digits = (if (minor < 0L) -minor else minor).toString().padStart(decimals + 1, '0')
        val text = if (decimals == 0) {
            digits
        } else {
            digits.dropLast(decimals) + point + digits.takeLast(decimals)
        }
        return if (minor < 0L) "-$text" else text
    }

    /**
     * Las celdas de [columns] de cada fila del CSV [name] de [files], separadas por un espacio,
     * tal como están escritas.
     */
    private fun cellsOf(
        files: Map<String, String>,
        name: String,
        vararg columns: String,
    ): List<String> {
        val lines = csvLines(files.getValue(name))
        val header =
            csvCells(lines.first().removePrefix(CsvRowWriter.BYTE_ORDER_MARK), separator)
        val indexes = columns.map { column ->
            header.indexOf("\"$column\"").also { check(it >= 0) { "Falta la columna $column" } }
        }
        return lines.drop(1).map { line ->
            val cells = csvCells(line, separator)
            indexes.joinToString(" ") { cells[it] }
        }
    }

    /** La columna que reconoce la importación en cada encabezado de [sheet], o `null`. */
    private fun columnsOf(sheet: ImportSheet): List<String?> =
        ImportColumnMatcher { key -> HEADERS[key].orEmpty() }
            .match(sheet.header, ImportTarget.PRODUCTS)
            .map { (it as? ColumnMatch.Field)?.key }

    /** Los archivos de un `.zip`, como texto UTF-8. */
    private fun unzipText(file: File): Map<String, String> {
        val entries = linkedMapOf<String, String>()
        ZipInputStream(file.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        return entries
    }

    /** Otro idioma de la app que el de la interfaz de la fila. */
    private fun otherLanguage(): String = when (row.uiLanguageTag) {
        "es" -> "pt-BR"
        "pt" -> "en-US"
        else -> "es-ES"
    }

    private suspend fun utilities(): Long =
        count("SELECT id FROM expense_categories WHERE system_key = 'UTILITIES'")

    /** Cantidad, costo promedio y valor del stock guardados en el producto. */
    private suspend fun cached(productId: Long): String {
        val product = requireNotNull(ports.products.findById(productId))
        return "${product.onHand.milli} ${product.averageCost.minor} ${product.stockValue.minor}"
    }

    private suspend fun rows(sql: String): List<String> =
        database.useReaderConnection { it.texts(sql) }

    private fun saved(result: SaveProductResult): Long =
        (result as? SaveProductResult.Saved)?.productId
            ?: throw AssertionError("No se guardó: $result")

    /** La fecha de negocio del instante de la venta en la zona de una fila, y su semana. */
    data class MatrixDay(val date: BusinessDate, val weekStart: BusinessDate)

    companion object {
        /** 2026-11-02 04:30 UTC: un lunes en casi todas las zonas, y domingo en tres de ellas. */
        private const val SALE_AT = 1_793_593_800_000L

        private fun date(text: String) = requireNotNull(BusinessDate.parse(text))

        private val OCT_26 = date("2026-10-26")
        private val OCT_31 = date("2026-10-31")
        private val NOV_1 = date("2026-11-01")
        private val NOV_2 = date("2026-11-02")

        private fun on(date: BusinessDate, weekStart: BusinessDate) = MatrixDay(date, weekStart)

        /**
         * La fecha de negocio de [SALE_AT] y el primer día de su semana en cada fila, en el orden
         * de la matriz, escritos a mano con el desfase de cada zona ese día. El 2 de noviembre es
         * lunes, el 1 domingo y el 31 de octubre sábado.
         */
        private val DAYS: List<MatrixDay?> = listOf(
            // Tokio, +09:00: 13:30 del lunes; la semana empieza el domingo.
            on(NOV_2, NOV_1),
            // Ho Chi Minh, +07:00: 11:30 del lunes; la semana empieza el lunes.
            on(NOV_2, NOV_2),
            // Los Ángeles, −08:00 desde el 1 de noviembre: 20:30 del domingo.
            on(NOV_1, NOV_1),
            // Madrid, +01:00: 05:30 del lunes.
            on(NOV_2, NOV_2),
            // São Paulo, −03:00: 01:30 del lunes; la semana empieza el domingo.
            on(NOV_2, NOV_1),
            // Calcuta, +05:30: 10:00 del lunes.
            on(NOV_2, NOV_2),
            // San Juan de Terranova, −03:30 desde el 1 de noviembre: 01:00 del lunes.
            on(NOV_2, NOV_1),
            // Zúrich, +01:00: 05:30 del lunes.
            on(NOV_2, NOV_2),
            // Kuwait, +03:00: 07:30 del lunes; la semana empieza el sábado 31.
            on(NOV_2, OCT_31),
            // Santiago, −03:00: 01:30 del lunes.
            on(NOV_2, NOV_2),
            // Moneda sin confirmar: no registra nada.
            null,
            // Buenos Aires, −03:00: 01:30 del lunes.
            on(NOV_2, NOV_2),
            // Ciudad de México, −06:00: 22:30 del domingo.
            on(NOV_1, NOV_1),
            // Tokio, con formato en inglés.
            on(NOV_2, NOV_1),
            // Santiago, sin decimales.
            on(NOV_2, NOV_2),
            // Buenos Aires, sin decimales.
            on(NOV_2, NOV_2),
            // UTC: 04:30 del lunes; la semana empieza el domingo.
            on(NOV_2, NOV_1),
            // Bogotá, −05:00: 23:30 del domingo; la semana empezó el lunes 26 de octubre.
            on(NOV_1, OCT_26),
        )

        private val NOVEMBER = ReportPeriod.Month(YearMonth(2026, 11))

        private const val TAX_NAME = "Tasa inventada"
        private const val ZIP_URI = "content://matrix/export.zip"
        private const val XLSX_URI = "content://matrix/export.xlsx"

        /** Las columnas de la venta que se comparan en `sales.csv`. */
        private val SALE_COLUMNS = arrayOf(
            "date",
            "net_total",
            "tax_total",
            "order_adjustment",
            "cash_rounding_amount",
            "total",
            "amount_due",
            "total_cost",
            "sales_profit",
        )

        /** Un PDF inventado: el paquete lo copia tal cual, sin leerlo. */
        private val PDF_BYTES = "%PDF-1.4 inventado".toByteArray(Charsets.US_ASCII)

        private val OIL = ProductForm(
            name = "Aceite inventado",
            salePrice = Money(RegionalMatrixSale.OIL_PRICE),
            cost = Money(RegionalMatrixSale.OIL_COST),
        )

        private val CHEESE = ProductForm(
            name = "Queso inventado",
            baseUnitId = KILOGRAM_ID,
            salePrice = Money(RegionalMatrixSale.CHEESE_PRICE),
            cost = Money(RegionalMatrixSale.CHEESE_COST),
        )

        /** Los encabezados del CSV que se importa, como los nombra la interfaz en español. */
        private val HEADERS = mapOf(
            "name" to listOf("Nombre"),
            "sale_price" to listOf("Precio de venta"),
            "initial_cost" to listOf("Costo inicial"),
            "on_hand" to listOf("Existencia"),
        )

        /** Nombres de archivo y encabezados por su clave, y las filas iniciales por su clave. */
        private val CSV_NAMING = object : CsvNaming {
            override fun fileName(table: CsvExportTable): String = "${table.key}.csv"

            override fun header(column: String): String = column

            override fun seedName(table: SeedTable, systemKey: String): String = "seed:$systemKey"
        }

        private val SUMMARY_NAMING = object : SummaryNaming {
            override fun fileName(table: CsvExportTable): String = "${table.key}.csv"

            override fun header(column: String): String = column

            override fun seedName(table: SeedTable, systemKey: String): String = "seed:$systemKey"

            override fun closingInventoryFileName(): String =
                "${SummaryFile.CLOSING_INVENTORY_KEY}.csv"
        }

        @JvmStatic
        @Parameterized.Parameters(name = "{1}")
        fun parameters(): List<Array<Any>> {
            check(DAYS.size == RegionalMatrix.ROWS.size) { "Falta el día de una fila" }
            return RegionalMatrix.ROWS.mapIndexed { index, row -> arrayOf(index, row) }
        }
    }
}
