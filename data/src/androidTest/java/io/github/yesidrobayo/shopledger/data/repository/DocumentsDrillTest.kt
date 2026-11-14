package io.github.yesidrobayo.shopledger.data.repository

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.room3.useReaderConnection
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.DataPorts
import io.github.yesidrobayo.shopledger.data.backup.InvariantQueries
import io.github.yesidrobayo.shopledger.data.backup.MemoryLog
import io.github.yesidrobayo.shopledger.data.export.CsvExporter
import io.github.yesidrobayo.shopledger.data.export.FileDocument
import io.github.yesidrobayo.shopledger.data.export.csvCells
import io.github.yesidrobayo.shopledger.data.export.csvLines
import io.github.yesidrobayo.shopledger.data.export.recordCsvExported
import io.github.yesidrobayo.shopledger.data.export.unzipCsv
import io.github.yesidrobayo.shopledger.data.imports.DocumentImportReader
import io.github.yesidrobayo.shopledger.data.local.catalog.CREATED_AT
import io.github.yesidrobayo.shopledger.data.local.catalog.CatalogDatabaseTest
import io.github.yesidrobayo.shopledger.data.local.texts
import io.github.yesidrobayo.shopledger.data.media.MediaImporter
import io.github.yesidrobayo.shopledger.data.print.ThermalImagePrinter
import io.github.yesidrobayo.shopledger.data.transaction.RestoreLock
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.SeedTable
import io.github.yesidrobayo.shopledger.domain.model.catalog.ImageImport
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductForm
import io.github.yesidrobayo.shopledger.domain.model.catalog.StoredImage
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerForm
import io.github.yesidrobayo.shopledger.domain.model.document.AttachmentTarget
import io.github.yesidrobayo.shopledger.domain.model.document.FiscalMarkStatus
import io.github.yesidrobayo.shopledger.domain.model.document.FiscalSale
import io.github.yesidrobayo.shopledger.domain.model.document.OrderStatus
import io.github.yesidrobayo.shopledger.domain.model.document.QuoteStatus
import io.github.yesidrobayo.shopledger.domain.model.document.ReceiptAmount
import io.github.yesidrobayo.shopledger.domain.model.document.ReceiptDocument
import io.github.yesidrobayo.shopledger.domain.model.document.ReceiptHeader
import io.github.yesidrobayo.shopledger.domain.model.document.ReceiptKind
import io.github.yesidrobayo.shopledger.domain.model.document.ReceiptLine
import io.github.yesidrobayo.shopledger.domain.model.expense.ExpenseRequest
import io.github.yesidrobayo.shopledger.domain.model.export.CsvExport
import io.github.yesidrobayo.shopledger.domain.model.export.CsvExportTable
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportApplyResult
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportDraft
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportExistingPolicy
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportFileRead
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportIssue
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportRowIssue
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportSheet
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportTarget
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodDraft
import io.github.yesidrobayo.shopledger.domain.model.print.PrintFailure
import io.github.yesidrobayo.shopledger.domain.model.print.PrintResult
import io.github.yesidrobayo.shopledger.domain.model.region.DecimalSeparator
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SalePaymentRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleRequest
import io.github.yesidrobayo.shopledger.domain.model.settings.CsvSeparator
import io.github.yesidrobayo.shopledger.domain.model.settings.CurrencyCode
import io.github.yesidrobayo.shopledger.domain.model.settings.ReceiptLogoChange
import io.github.yesidrobayo.shopledger.domain.model.settings.ReceiptTemplateInput
import io.github.yesidrobayo.shopledger.domain.model.settings.RegionalSettingsInput
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.CsvNaming
import io.github.yesidrobayo.shopledger.domain.port.PaymentMethodNames
import io.github.yesidrobayo.shopledger.domain.rule.document.DocumentReceipt
import io.github.yesidrobayo.shopledger.domain.rule.document.SaleReceiptDocument
import io.github.yesidrobayo.shopledger.domain.rule.imports.ColumnMatch
import io.github.yesidrobayo.shopledger.domain.rule.imports.ImportColumnMatcher
import io.github.yesidrobayo.shopledger.domain.testing.FakeThermalPrinter
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ChangePriceResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ChangePriceUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CreateProductUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveProductResult
import io.github.yesidrobayo.shopledger.domain.usecase.customer.CreateCustomerUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.customer.RecordCustomerPaymentUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.customer.SaveCustomerResult
import io.github.yesidrobayo.shopledger.domain.usecase.document.AttachReceiptPhotoUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.document.AttachReceiptResult
import io.github.yesidrobayo.shopledger.domain.usecase.document.ImportReceiptPhotoUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.document.MarkFiscalResult
import io.github.yesidrobayo.shopledger.domain.usecase.document.MarkSalesFiscalUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.document.ObserveFiscalMonthUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.document.ObserveFiscalPortalLinkUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.document.ObserveOrdersUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.document.ObserveReceiptPhotosUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.document.OrderChangeResult
import io.github.yesidrobayo.shopledger.domain.usecase.document.OrderDeliveryResult
import io.github.yesidrobayo.shopledger.domain.usecase.document.OrderDepositResult
import io.github.yesidrobayo.shopledger.domain.usecase.document.OrderRequest
import io.github.yesidrobayo.shopledger.domain.usecase.document.PrepareOrderDeliveryUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.document.PrepareQuoteConversionUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.document.QuoteConversionResult
import io.github.yesidrobayo.shopledger.domain.usecase.document.QuoteRequest
import io.github.yesidrobayo.shopledger.domain.usecase.document.RecordOrderDepositUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.document.ReferenceChange
import io.github.yesidrobayo.shopledger.domain.usecase.document.SaveFiscalPortalLinkUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.document.SaveOrderResult
import io.github.yesidrobayo.shopledger.domain.usecase.document.SaveOrderUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.document.SavePortalLinkResult
import io.github.yesidrobayo.shopledger.domain.usecase.document.SaveQuoteResult
import io.github.yesidrobayo.shopledger.domain.usecase.document.SaveQuoteUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.document.SetOrderPreparationUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.expense.RegisterExpenseResult
import io.github.yesidrobayo.shopledger.domain.usecase.expense.RegisterExpenseUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.export.ExportCsvUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.imports.ApplyImportUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.imports.ImportWrites
import io.github.yesidrobayo.shopledger.domain.usecase.imports.PrepareImportUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.money.DocumentMoney
import io.github.yesidrobayo.shopledger.domain.usecase.money.MoneyLedgerWriter
import io.github.yesidrobayo.shopledger.domain.usecase.payment.EditPaymentMethodUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.payment.SavePaymentMethodResult
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.domain.usecase.region.CurrencyLock
import io.github.yesidrobayo.shopledger.domain.usecase.region.SaveRegionalSettingsResult
import io.github.yesidrobayo.shopledger.domain.usecase.region.SaveRegionalSettingsUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.sale.ObserveSaleDetailUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleResult
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.settings.BusinessProfileChange
import io.github.yesidrobayo.shopledger.domain.usecase.settings.SaveBusinessProfileResult
import io.github.yesidrobayo.shopledger.domain.usecase.settings.SaveBusinessProfileUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.settings.SaveReceiptTemplateResult
import io.github.yesidrobayo.shopledger.domain.usecase.settings.SaveReceiptTemplateUseCase
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipInputStream
import kotlin.random.Random
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.YearMonth
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Simulacro de los documentos sobre una base nueva, por el camino de la app: los puertos de la
 * base abierta y los casos de uso armados como los arman sus pantallas. Un día de una tienda de
 * regalos inventada: la plantilla del comprobante, una venta con su comprobante, impreso en una
 * térmica falsa, una cotización con el precio congelado que se convierte en venta una sola vez,
 * un pedido con envío y anticipo que se entrega con la venta que consume ese anticipo, las marcas
 * de pendientes de facturar con su enlace externo, un gasto con la foto de su comprobante, la
 * importación de un CSV pequeño con una fila con error y otra que ya existe, y la exportación de
 * todas las tablas a CSV y a hoja de cálculo, que vuelve a entrar sin crear nada.
 *
 * Ningún documento lleva costo, margen ni ganancia: el comprobante, la cotización, el pedido y la
 * lista de pendientes no tienen dónde ponerlos, y ninguna de sus cifras coincide con un costo o
 * un margen de la venta. El stock, la caja, el saldo del cliente, lo reservado por el pedido y
 * cada archivo exportado se comparan con valores calculados a mano. Cada consulta de invariantes
 * del modelo de datos devuelve cero filas.
 *
 * La tienda, los productos, el cliente, la foto, el archivo y los montos son inventados. La moneda
 * es `XTS`, el código de pruebas de ISO 4217, con dos decimales: los montos van en su unidad
 * menor.
 */
@RunWith(AndroidJUnit4::class)
internal class DocumentsDrillTest : CatalogDatabaseTest() {
    // La base nace sin moneda, como en un teléfono nuevo, y la persona la confirma en su pantalla.
    override val confirmsCurrency: Boolean = false

    /** Un reloj que avanza un segundo en cada lectura, todo dentro del mismo día del negocio. */
    private val ticks = AtomicLong(CREATED_AT)
    private val clock = Clock { Instant.fromEpochMilliseconds(ticks.addAndGet(1_000L)) }

    private lateinit var media: File
    private lateinit var inputs: File

    @Before
    fun createFolders() {
        val run = "documents-drill-" + Random.nextLong().toULong()
        media = File(context.filesDir, run)
        inputs = File(context.cacheDir, run).apply { mkdirs() }
    }

    @After
    fun deleteFolders() {
        media.deleteRecursively()
        inputs.deleteRecursively()
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

    private val changePrice by lazy {
        ChangePriceUseCase(
            tx,
            ports.products,
            ports.systemRows,
            ports.priceHistory,
            calendar,
            CurrencyLock(ports.settings),
            clock,
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

    private val saleDetail by lazy {
        ObserveSaleDetailUseCase(
            ports.sales,
            ports.salePayments,
            ports.products,
            ports.saleReturns,
        )
    }

    private val saveQuote by lazy {
        SaveQuoteUseCase(
            transactions = tx,
            settings = ports.settings,
            quotes = ports.quotes,
            customers = ports.customers,
            clock = clock,
            products = ports.products,
            units = ports.units,
            taxRates = ports.taxRates,
            systemRows = ports.systemRows,
            productUnits = ports.productUnits,
            priceLists = ports.priceLists,
            productPrices = ports.productPrices,
        )
    }

    private val prepareConversion by lazy {
        PrepareQuoteConversionUseCase(
            transactions = tx,
            settings = ports.settings,
            quotes = ports.quotes,
            products = ports.products,
            customers = ports.customers,
            clock = clock,
            productUnits = ports.productUnits,
            priceLists = ports.priceLists,
            productPrices = ports.productPrices,
        )
    }

    private val saveOrder by lazy {
        SaveOrderUseCase(
            transactions = tx,
            settings = ports.settings,
            orders = ports.orders,
            customers = ports.customers,
            clock = clock,
            products = ports.products,
            units = ports.units,
            taxRates = ports.taxRates,
            systemRows = ports.systemRows,
            productUnits = ports.productUnits,
            priceLists = ports.priceLists,
            productPrices = ports.productPrices,
        )
    }

    private val observeOrders by lazy {
        ObserveOrdersUseCase(
            ports.orders,
            ports.customers,
            ports.products,
            ports.productComponents,
        )
    }

    private val setPreparation by lazy {
        SetOrderPreparationUseCase(tx, ports.orders, clock)
    }

    private val recordDeposit by lazy {
        RecordOrderDepositUseCase(
            transactions = tx,
            orders = ports.orders,
            payments = RecordCustomerPaymentUseCase(
                transactions = tx,
                settings = ports.settings,
                customers = ports.customers,
                ledger = ports.customerLedger,
                accounts = ports.moneyAccounts,
                money = MoneyLedgerWriter(ports.moneyAccounts, ports.moneyLedger),
                clock = clock,
            ),
        )
    }

    private val prepareDelivery by lazy {
        PrepareOrderDeliveryUseCase(tx, ports.orders, ports.products, ports.customers)
    }

    private val fiscalMonth by lazy {
        ObserveFiscalMonthUseCase(ports.saleFiscalMarks)
    }

    private val markFiscal by lazy {
        MarkSalesFiscalUseCase(tx, ports.saleFiscalMarks, clock)
    }

    private val savePortal by lazy {
        SaveFiscalPortalLinkUseCase(tx, ports.settings, clock)
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

    private val importPhoto by lazy {
        ImportReceiptPhotoUseCase(
            MediaImporter(context.contentResolver, { media }, Dispatchers.IO),
        )
    }

    private val attachPhoto by lazy {
        AttachReceiptPhotoUseCase(tx, ports.documentAttachments, ports.mediaAssets, clock)
    }

    private val photos by lazy {
        ObserveReceiptPhotosUseCase(ports.documentAttachments, ports.mediaAssets)
    }

    private val fileReader by lazy {
        DocumentImportReader(context.contentResolver, Dispatchers.IO)
    }

    private val matcher by lazy {
        ImportColumnMatcher { key -> HEADERS[key].orEmpty() }
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
            seedLabels = { _, key -> listOf(seedLabel(key)) },
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

    private val exportFiles = HashMap<String, File>()

    private val exporter by lazy {
        CsvExporter(
            rows = { ports.csvRows },
            documentFor = { uri -> FileDocument(exportFiles.getValue(uri)) },
            recordExport = { recordCsvExported(ports, clock, MemoryLog()) },
            log = MemoryLog(),
            ioContext = Dispatchers.IO,
        )
    }

    private val exportData by lazy {
        ExportCsvUseCase(ports.settings, exporter) { DecimalSeparator.COMMA }
    }

    // Lo que comparten los pasos del día: cuentas, medios de pago, productos, clienta y ventas.
    private var drawer = 0L
    private var cashMethod = 0L
    private var onAccount = 0L
    private var candle = 0L
    private var mug = 0L
    private var box = 0L
    private var customer = 0L
    private var counterSale = 0L
    private var quoteSale = 0L
    private var orderSale = 0L
    private var today = JAN_1

    @Test
    fun aDayOfDocumentsMatchesHandWrittenBooks() = runBlocking {
        configure()
        catalog()
        counterSaleWithReceipt()
        quoteToSale()
        orderWithDeposit()
        fiscalMarks()
        expenseWithPhoto()
        importCsv()
        booksOfTheDay()
        exportAndReimport()
        invariants()
    }

    /**
     * Un producto nuevo que llega en un CSV entra con su stock inicial, su costo y su primer
     * precio en el historial, anotado como importado y sin precio anterior, como pide la regla
     * de importar datos. La guarda del historial de precios debe aceptar esa fila.
     */
    @Test
    fun aNewProductFromAFileEntersWithItsFirstPrice() = runBlocking {
        configure()
        val csv = File(inputs, "products.csv").apply {
            writeText(
                "Nombre;Categoría;Precio de venta;Costo inicial;Existencia\r\n" +
                    "Arroz inventado;Granos;35,00;20,00;12\r\n",
            )
        }
        val sheet = sheetOf(fileReader.read(Uri.fromFile(csv).toString(), null, SEMICOLON) {})
        val preview = prepareImport(
            sheet,
            ImportTarget.PRODUCTS,
            columnsOf(matcher, sheet),
            DecimalSeparator.COMMA,
        )
        assertEquals(listOf(2), preview.ready.map { it.row })

        val result = applyImport(preview, ImportExistingPolicy.SKIP)

        assertEquals(ImportApplyResult.Applied(created = 1, updated = 0, skipped = 0), result)
        val rice = count("SELECT id FROM products WHERE name = 'Arroz inventado'")
        assertEquals("12000 2000 24000", cached(ports, rice))
        assertEquals(
            listOf("IMPORT - 3500"),
            rows(
                "SELECT source || ' ' || IFNULL(old_price, '-') || ' ' || new_price " +
                    "FROM product_price_history WHERE product_id = $rice",
            ),
        )
        for (query in InvariantQueries.MODEL) {
            val sql = query.sql.trimEnd().removeSuffix(";")
            assertEquals(query.label, 0L, count("SELECT COUNT(*) FROM ($sql)"))
        }
    }

    /** La moneda, la caja, el nombre del negocio y la plantilla, como en sus pantallas. */
    private suspend fun configure() {
        // Configuración, como en sus pantallas: la moneda, el efectivo enlazado a la caja, el
        // nombre del negocio y la plantilla del comprobante.
        assertEquals(
            SaveRegionalSettingsResult.Saved,
            SaveRegionalSettingsUseCase(tx, ports.settings, clock)(REGION),
        )
        drawer = count("SELECT id FROM money_accounts WHERE system_key = 'CASH'")
        cashMethod = count("SELECT id FROM payment_methods WHERE system_key = 'CASH'")
        onAccount = count("SELECT id FROM payment_methods WHERE system_key = 'ON_ACCOUNT'")
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
        assertEquals(
            SaveBusinessProfileResult.Saved,
            SaveBusinessProfileUseCase(tx, ports.settings, clock)(
                BusinessProfileChange.Name("Regalos inventados"),
            ),
        )
        assertEquals(
            SaveReceiptTemplateResult.Saved,
            SaveReceiptTemplateUseCase(tx, ports.settings, ports.mediaAssets, clock)(
                ReceiptTemplateInput(ReceiptLogoChange.Keep, DETAILS, FOOTER),
            ),
        )
    }

    /** El catálogo y la clienta. */
    private suspend fun catalog() {
        // 1. El catálogo, con stock en la ubicación inicial y costos que ningún documento
        // muestra: velas con 20 a 12,37, tazas con 10 a 24,11 y cajas con 8 a 6,13. Y la
        // clienta de la cotización y del pedido.
        candle = saved(createProduct(CANDLE, QuantityMilli(20_000)))
        mug = saved(createProduct(MUG, QuantityMilli(10_000)))
        box = saved(createProduct(BOX, QuantityMilli(8_000)))
        customer = (
            CreateCustomerUseCase(tx, ports.customers, clock)(
                CustomerForm("Clienta inventada"),
            ) as SaveCustomerResult.Saved
            ).customerId
    }

    /** La venta de contado, su comprobante y su impresión. */
    private suspend fun counterSaleWithReceipt() {
        // 2. Una venta de contado, 2 velas y 1 taza: 11.000. Su comprobante sale de la venta
        // guardada y de la plantilla, con lo que cobró cada línea, el pago y nada más.
        counterSale = registered(
            registerSale(SaleRequest(listOf(line(candle, 2_000), line(mug, 1_000)), cashMethod)),
        )
        val settings = ports.settings.get()
        val receipt = SaleReceiptDocument.of(
            requireNotNull(saleDetail(counterSale).first()),
            settings.receipt,
            settings.profile.businessName,
            customerName = null,
        )
        today = requireNotNull(ports.sales.findById(counterSale)).content.businessDate
        assertEquals(JAN_1, today)
        assertEquals(ReceiptKind.SALE, receipt.kind)
        assertEquals(counterSale, receipt.number)
        assertFalse(receipt.voided)
        assertEquals(
            ReceiptHeader(null, "Regalos inventados", DETAILS),
            receipt.header,
        )
        assertEquals(FOOTER, receipt.footerText)
        assertEquals(
            listOf("Vela inventada 2000 6000", "Taza inventada 1000 5000"),
            lineRows(receipt),
        )
        assertEquals(
            listOf("Subtotal 11000", "Total 11000", "Payment 11000"),
            amountRows(receipt),
        )
        // El costo congelado de la venta existe, 2 × 1.237 + 2.411, y no sale en el papel.
        assertEquals(
            listOf("2474 3526", "2411 2589"),
            rows(
                "SELECT line_cost || ' ' || (line_total - line_cost) FROM sale_lines " +
                    "WHERE sale_id = $counterSale ORDER BY id",
            ),
        )
        assertNoCostIn(receipt, counterSale)

        // 3. El comprobante va a una térmica de 58 mm: un solo trabajo ESC/POS, una imagen
        // del ancho del papel y su corte. Una impresora que falla no toca la venta.
        val printer = FakeThermalPrinter()
        val thermal = ThermalImagePrinter(Dispatchers.Default)
        val printed = thermal.printImages(printer, listOf(receipt), { 1 }, ::paperOf)
        assertTrue("$printed", printed is PrintResult.Sent)
        assertEquals(1, printer.jobs.size)
        val image = printer.printedImages().single()
        val receiptRows = receipt.lines.size + receipt.amounts.size
        assertEquals(PAPER_DOTS, image.width)
        assertEquals(receiptRows * ROW_DOTS, image.height)
        assertEquals(receiptRows * BAR_DOTS, image.rows().count { it.all { dot -> dot == '#' } })
        val salesBefore = count("SELECT COUNT(*) FROM sales")
        val broken = FakeThermalPrinter(PrintFailure.WRITE_FAILED)
        assertEquals(
            PrintResult.Failed(PrintFailure.WRITE_FAILED),
            thermal.printImages(broken, listOf(receipt), { 1 }, ::paperOf),
        )
        assertTrue(broken.jobs.isEmpty())
        assertEquals(salesBefore, count("SELECT COUNT(*) FROM sales"))
    }

    /** La cotización y su venta. */
    private suspend fun quoteToSale() {
        val settings = ports.settings.get()
        // 4. Una cotización de 2 tazas para la clienta, vigente 15 días: 10.000. No toca el
        // stock ni la caja. Después la taza sube a 55,00, y la cotización conserva su precio.
        val stockRowsBefore = count("SELECT COUNT(*) FROM stock_movements")
        val moneyRowsBefore = count("SELECT COUNT(*) FROM money_movements")
        val quote = (
            saveQuote(
                QuoteRequest(
                    listOf(line(mug, 2_000)),
                    customerId = customer,
                    validUntil = today.plusDays(15),
                ),
            ) as SaveQuoteResult.Saved
            ).quoteId
        assertEquals(stockRowsBefore, count("SELECT COUNT(*) FROM stock_movements"))
        assertEquals(moneyRowsBefore, count("SELECT COUNT(*) FROM money_movements"))
        assertTrue(changePrice(mug, Money(5_500)) is ChangePriceResult.Changed)
        val quoteDocument = DocumentReceipt.ofQuote(
            requireNotNull(ports.quotes.findById(quote)),
            ports.quotes.findLines(quote),
            settings.receipt,
            settings.profile.businessName,
            "Clienta inventada",
        )
        assertEquals(ReceiptKind.QUOTE, quoteDocument.kind)
        assertEquals(today.plusDays(15), quoteDocument.validUntil)
        assertEquals(listOf("Taza inventada 2000 10000"), lineRows(quoteDocument))
        assertEquals(listOf("Subtotal 10000", "Total 10000"), amountRows(quoteDocument))
        assertNoCostIn(quoteDocument, saleId = null)

        // 5. La cotización se convierte en venta con su precio congelado: la venta la deja
        // convertida en su misma transacción, y una segunda vez no hay venta.
        val conversion =
            (prepareConversion(quote) as QuoteConversionResult.Ready).conversion
        assertEquals(customer, conversion.customerId)
        assertEquals(1, conversion.changedPrices)
        assertEquals(Money(5_500), conversion.lines.single().currentPrice)
        val quoteRequest = SaleRequest(
            conversion.requests(useCurrentPrices = false),
            cashMethod,
            customerId = conversion.customerId,
            quoteId = quote,
        )
        quoteSale = registered(registerSale(quoteRequest))
        assertEquals(RegisterSaleResult.QuoteUnavailable, registerSale(quoteRequest))
        assertEquals(QuoteStatus.CONVERTED, requireNotNull(ports.quotes.findById(quote)).status)
        assertEquals(quoteSale, count("SELECT converted_sale_id FROM quotes WHERE id = $quote"))
        assertEquals(
            "10000 4822",
            rows("SELECT total || ' ' || total_cost FROM sales WHERE id = $quoteSale").single(),
        )
    }

    /** El pedido, su anticipo y su entrega. */
    private suspend fun orderWithDeposit() {
        val settings = ports.settings.get()
        // 6. Un pedido de 5 velas y 5 cajas con 20,00 de envío: 15.000 + 7.500 + 2.000 =
        // 24.500. Reserva sin tocar el libro, y la clienta deja 100,00 de anticipo en la caja,
        // que es saldo a su favor.
        val stockRowsBeforeOrder = count("SELECT COUNT(*) FROM stock_movements")
        val order = (
            saveOrder(
                OrderRequest(
                    listOf(line(candle, 5_000), line(box, 5_000)),
                    customerId = customer,
                    dueDate = today.plusDays(2),
                    deliveryAddress = "Calle inventada 45",
                    shippingAmount = Money(2_000),
                ),
            ) as SaveOrderResult.Saved
            ).orderId
        assertEquals(stockRowsBeforeOrder, count("SELECT COUNT(*) FROM stock_movements"))
        assertEquals(Money(24_500), requireNotNull(ports.orders.findById(order)).total)
        assertEquals(
            mapOf(candle to QuantityMilli(5_000), box to QuantityMilli(5_000)),
            observeOrders.reserved().first(),
        )
        assertEquals(
            listOf("5000 0", "5000 0"),
            requireNotNull(observeOrders.detail(order).first()).lines.map {
                "${it.reserved.milli} ${it.short.milli}"
            },
        )
        assertTrue(
            recordDeposit(order, Money(10_000), drawer) is OrderDepositResult.Recorded,
        )
        assertEquals(Money(-10_000), requireNotNull(ports.customers.findById(customer)).balance)
        assertEquals(OrderChangeResult.Changed, setPreparation(order, true))
        assertEquals(OrderStatus.READY, requireNotNull(ports.orders.findById(order)).status)
        val orderDocument = DocumentReceipt.ofOrder(
            requireNotNull(ports.orders.findById(order)),
            ports.orders.findLines(order),
            settings.receipt,
            settings.profile.businessName,
            "Clienta inventada",
        )
        assertEquals(ReceiptKind.ORDER, orderDocument.kind)
        assertEquals(today.plusDays(2), orderDocument.dueDate)
        assertEquals(
            listOf("Vela inventada 5000 15000", "Caja de regalo inventada 5000 7500"),
            lineRows(orderDocument),
        )
        assertEquals(
            listOf("Subtotal 22500", "Shipping 2000", "Total 24500"),
            amountRows(orderDocument),
        )
        assertNoCostIn(orderDocument, saleId = null)

        // 7. La entrega: la venta consume el anticipo a crédito y cobra el resto, 145,00, en
        // efectivo, con el envío en su ajuste. Saca el stock y deja el pedido entregado.
        val delivery = (prepareDelivery(order) as OrderDeliveryResult.Ready).delivery
        assertEquals(Money(10_000), delivery.credit)
        assertEquals(Money(2_000), delivery.shippingAmount)
        orderSale = registered(
            registerSale(
                SaleRequest(
                    lines = delivery.lines,
                    paymentMethodId = cashMethod,
                    payments = listOf(
                        SalePaymentRequest(onAccount, delivery.credit),
                        SalePaymentRequest(cashMethod, Money(14_500)),
                    ),
                    orderAdjustment = delivery.shippingAmount,
                    customerId = customer,
                    orderId = order,
                ),
            ),
        )
        assertEquals(OrderStatus.DELIVERED, requireNotNull(ports.orders.findById(order)).status)
        assertEquals(orderSale, count("SELECT delivered_sale_id FROM orders WHERE id = $order"))
        assertEquals(emptyMap<Long, QuantityMilli>(), observeOrders.reserved().first())
        assertEquals(Money.ZERO, requireNotNull(ports.customers.findById(customer)).balance)
        assertEquals(
            "24500 9250",
            rows("SELECT total || ' ' || total_cost FROM sales WHERE id = $orderSale").single(),
        )
        // Su comprobante: las líneas, el envío como ajuste, el anticipo como saldo a crédito
        // y el efectivo. Tampoco lleva costo.
        val orderReceipt = SaleReceiptDocument.of(
            requireNotNull(saleDetail(orderSale).first()),
            settings.receipt,
            settings.profile.businessName,
            "Clienta inventada",
        )
        assertEquals(
            listOf(
                "Subtotal 22500",
                "Adjustment 2000",
                "Total 24500",
                "Payment 10000",
                "Payment 14500",
                "BalanceDue 10000",
            ),
            amountRows(orderReceipt),
        )
        assertNoCostIn(orderReceipt, orderSale)
    }

    /** Las marcas de pendientes de facturar y el enlace externo. */
    private suspend fun fiscalMarks() {
        // 8. Pendientes de facturar del mes: las tres ventas, de la más nueva a la más
        // antigua, 45.500 en total. Marcar escribe solo la marca: ni la venta, ni el stock,
        // ni la caja cambian. El enlace externo se guarda solo con https.
        val january = YearMonth(2026, 1)
        val pending = fiscalMonth(january, FiscalMarkStatus.PENDING).first()
        assertEquals(
            listOf(
                "$orderSale 24500 Clienta inventada PENDING -",
                "$quoteSale 10000 Clienta inventada PENDING -",
                "$counterSale 11000 - PENDING -",
            ),
            pending.sales.map(::fiscalRow),
        )
        assertEquals(Money(45_500), pending.total)
        assertFalse(pending.hasMarks)
        val stockRowsBeforeMarks = count("SELECT COUNT(*) FROM stock_movements")
        val moneyRowsBeforeMarks = count("SELECT COUNT(*) FROM money_movements")
        assertEquals(
            MarkFiscalResult.Marked(1),
            markFiscal(listOf(counterSale), FiscalMarkStatus.NOT_NEEDED, ReferenceChange.Keep),
        )
        assertEquals(
            MarkFiscalResult.Marked(1),
            markFiscal(
                listOf(orderSale),
                FiscalMarkStatus.RECORDED,
                ReferenceChange.Write("REF-0001"),
            ),
        )
        assertEquals(
            listOf("$quoteSale 10000 Clienta inventada PENDING -"),
            fiscalMonth(january, FiscalMarkStatus.PENDING).first().sales.map(::fiscalRow),
        )
        val recorded = fiscalMonth(january, FiscalMarkStatus.RECORDED).first()
        assertEquals(
            listOf("$orderSale 24500 Clienta inventada RECORDED REF-0001"),
            recorded.sales.map(::fiscalRow),
        )
        assertTrue(recorded.hasMarks)
        assertEquals(
            listOf("$counterSale 11000 - NOT_NEEDED -"),
            fiscalMonth(january, FiscalMarkStatus.NOT_NEEDED).first().sales.map(::fiscalRow),
        )
        assertEquals(stockRowsBeforeMarks, count("SELECT COUNT(*) FROM stock_movements"))
        assertEquals(moneyRowsBeforeMarks, count("SELECT COUNT(*) FROM money_movements"))
        assertEquals(SavePortalLinkResult.NotHttps, savePortal("http://portal.example.com"))
        assertEquals(
            SavePortalLinkResult.Saved(PORTAL),
            savePortal(PORTAL),
        )
        assertEquals(PORTAL, ObserveFiscalPortalLinkUseCase(ports.settings)().first())
    }

    /** El gasto con la foto de su comprobante. */
    private suspend fun expenseWithPhoto() {
        // 9. Un gasto de 40,00 en papel de regalo, pagado de la caja, con la foto de su
        // comprobante: se recodifica en su carpeta, y unirla no mueve dinero.
        val otherExpenses = count("SELECT id FROM expense_categories WHERE system_key = 'OTHER'")
        val expense = (
            registerExpense(
                ExpenseRequest(
                    Money(4_000),
                    otherExpenses,
                    "Papel de regalo",
                    moneyAccountId = drawer,
                ),
            ) as RegisterExpenseResult.Registered
            ).expenseId
        val moneyRowsWithExpense = count("SELECT COUNT(*) FROM money_movements")
        val stored = imported(importPhoto(photo("receipt.jpg")))
        val target = AttachmentTarget.Expense(expense)
        val attached = attachPhoto(target, stored)
        assertTrue("$attached", attached is AttachReceiptResult.Attached)
        assertEquals(listOf(stored), photos(target).first().map { it.image })
        assertEquals(
            listOf("DOCUMENT_ATTACHMENT"),
            rows("SELECT kind FROM media_assets WHERE sha256 = '${stored.sha256}'"),
        )
        assertEquals(1, media.walkTopDown().count { it.isFile })
        assertEquals(moneyRowsWithExpense, count("SELECT COUNT(*) FROM money_movements"))
    }

    /** La importación del CSV de clientes. */
    private suspend fun importCsv() {
        // 10. Un CSV pequeño de clientes, con punto y coma, en español: la clienta ya existe y
        // se omite; un cliente nuevo entra con su correo; un nombre que empieza como fórmula
        // entra como texto; y uno con un correo mal escrito no entra.
        val csv = File(inputs, "customers.csv").apply {
            writeText(
                "Nombre;Teléfono;Correo\r\n" +
                    "Clienta inventada;;\r\n" +
                    "Cliente de feria inventado;;feria@example.com\r\n" +
                    "=2+2 inventado;;\r\n" +
                    "Cliente sin correo inventado;;correo-malo\r\n",
            )
        }
        val csvSheet = sheetOf(fileReader.read(Uri.fromFile(csv).toString(), null, SEMICOLON) {})
        assertEquals(CsvSeparator.SEMICOLON, csvSheet.separator)
        val csvPreview = prepareImport(
            csvSheet,
            ImportTarget.CUSTOMERS,
            columnsOf(matcher, csvSheet, ImportTarget.CUSTOMERS),
            DecimalSeparator.COMMA,
        )
        assertEquals(
            listOf("3 Cliente de feria inventado", "4 =2+2 inventado"),
            csvPreview.ready.map { "${it.row} ${(it as ImportDraft.Contact).name}" },
        )
        assertEquals(listOf(2), csvPreview.existing.map { it.row })
        assertEquals(listOf(ImportRowIssue(5, "email", ImportIssue.EMAIL)), csvPreview.issues)
        assertEquals(
            ImportApplyResult.Applied(created = 2, updated = 0, skipped = 1),
            applyImport(csvPreview, ImportExistingPolicy.SKIP),
        )
        assertEquals(
            listOf(
                "Clienta inventada -",
                "Cliente de feria inventado feria@example.com",
                "=2+2 inventado -",
            ),
            rows("SELECT name || ' ' || IFNULL(email, '-') FROM customers ORDER BY id"),
        )
    }

    /** El stock y la caja del día. */
    private suspend fun booksOfTheDay() {
        // 11. El stock y los libros del día, a mano. Velas: 20 − 2 − 5 = 13; tazas: 10 − 1 −
        // 2 = 7; cajas: 8 − 5 = 3, cada una a su costo. La caja: dos ventas de contado, el
        // anticipo, el efectivo de la entrega y el gasto.
        assertEquals(
            listOf(
                "$candle 13000 1237 16081",
                "$mug 7000 2411 16877",
                "$box 3000 613 1839",
            ),
            listOf(candle, mug, box).map { "$it ${cached(ports, it)}" },
        )
        assertEquals(
            listOf(
                "SALE_RECEIPT 11000 11000",
                "SALE_RECEIPT 10000 21000",
                "CUSTOMER_PAYMENT 10000 31000",
                "SALE_RECEIPT 14500 45500",
                "EXPENSE_PAYMENT -4000 41500",
            ),
            rows(
                "SELECT type || ' ' || amount || ' ' || balance_after FROM money_movements " +
                    "WHERE money_account_id = $drawer ORDER BY id",
            ),
        )
    }

    /** La exportación a CSV y a hoja de cálculo, y la hoja que vuelve a entrar. */
    private suspend fun exportAndReimport() {
        // 12. La exportación a CSV: un archivo por tabla, con las filas del día, y el nombre
        // que empezaba como fórmula neutralizado con su apóstrofo.
        val zipFile = File(inputs, "export.zip")
        exportFiles[ZIP_URI] = zipFile
        val zipResult = exportData(ZIP_URI, NAMING) {}
        val files = unzipCsv(zipFile)
        assertEquals(CsvExportTable.entries.map { "${it.key}.csv" }, files.keys.toList())
        val dataRows = files.mapValues { (_, content) -> csvLines(content).size - 1 }
        assertEquals(
            CsvExport.Saved(files = files.size, rows = dataRows.values.sum().toLong()),
            zipResult,
        )
        assertEquals(
            mapOf(
                "products.csv" to 3,
                "customers.csv" to 3,
                "sales.csv" to 3,
                "sale_lines.csv" to 5,
                "sale_payments.csv" to 4,
                "stock_movements.csv" to 8,
                "money_movements.csv" to 5,
                "customer_ledger.csv" to 2,
                "expenses.csv" to 1,
                "quotes.csv" to 1,
                "quote_lines.csv" to 1,
                "orders.csv" to 1,
                "order_lines.csv" to 2,
                "sale_fiscal_marks.csv" to 2,
                "document_attachments.csv" to 1,
            ),
            DRILL_FILES.associateWith { dataRows.getValue(it) },
        )
        val exportedNames =
            csvLines(files.getValue("customers.csv")).drop(1).map { csvCells(it)[1] }
        assertTrue(exportedNames.toString(), "\"'=2+2 inventado\"" in exportedNames)

        // 13. La misma exportación como hoja de cálculo: una hoja por tabla, con las mismas
        // filas que cada CSV, y el texto también neutralizado.
        val xlsxFile = File(inputs, "export.xlsx")
        exportFiles[XLSX_URI] = xlsxFile
        assertTrue(exportData(XLSX_URI, NAMING, spreadsheet = true) {} is CsvExport.Saved)
        val sheets = unzipText(xlsxFile)
        val workbook = sheets.getValue("xl/workbook.xml")
        assertEquals(CsvExportTable.entries.size, Regex("<sheet ").findAll(workbook).count())
        CsvExportTable.entries.forEachIndexed { index, table ->
            val sheet = sheets.getValue("xl/worksheets/sheet${index + 1}.xml")
            assertEquals(
                table.key,
                dataRows.getValue("${table.key}.csv"),
                Regex("<row ").findAll(sheet).count() - 1,
            )
        }
        val customerSheet = "xl/worksheets/sheet${CsvExportTable.CUSTOMERS.ordinal + 1}.xml"
        assertTrue(sheets.getValue(customerSheet).contains(">'=2+2 inventado<"))

        // 14. La hoja exportada vuelve a entrar como productos: la primera hoja se reconoce
        // por sus columnas, los tres productos ya existen, y omitirlos no escribe nada.
        val seqBeforeReimport = changeSeq()
        val xlsxSheet =
            sheetOf(fileReader.read(Uri.fromFile(xlsxFile).toString(), null, SEMICOLON) {})
        assertEquals(null, xlsxSheet.separator)
        val xlsxPreview = prepareImport(
            xlsxSheet,
            ImportTarget.PRODUCTS,
            columnsOf(matcher, xlsxSheet),
            DecimalSeparator.COMMA,
        )
        assertEquals(emptyList<ImportRowIssue>(), xlsxPreview.issues)
        assertTrue(xlsxPreview.ready.isEmpty())
        assertEquals(3, xlsxPreview.existingRows)
        assertEquals(
            ImportApplyResult.Applied(created = 0, updated = 0, skipped = 3),
            applyImport(xlsxPreview, ImportExistingPolicy.SKIP),
        )
        assertEquals(seqBeforeReimport, changeSeq())
        assertEquals(3L, count("SELECT COUNT(*) FROM products"))
    }

    /** Cada consulta de invariantes da cero filas. */
    private suspend fun invariants() {
        // Cada consulta de invariantes da cero filas.
        for (query in InvariantQueries.MODEL) {
            val sql = query.sql.trimEnd().removeSuffix(";")
            assertEquals(query.label, 0L, count("SELECT COUNT(*) FROM ($sql)"))
        }
    }

    /**
     * Ningún campo de [document] se llama costo, margen, ganancia ni factura, y ninguna de sus
     * cifras coincide con el costo, el margen o la ganancia de las líneas de [saleId].
     */
    private suspend fun assertNoCostIn(document: ReceiptDocument, saleId: Long?) {
        val forbidden = listOf("cost", "margin", "profit", "invoice")
        for (type in DOCUMENT_TYPES) {
            for (field in type.declaredFields) {
                val name = field.name.lowercase()
                assertFalse("${type.simpleName}.${field.name}", forbidden.any { it in name })
            }
        }
        assertEquals(listOf("SALE", "QUOTE", "ORDER"), ReceiptKind.entries.map { it.name })
        if (saleId == null) return
        val hidden = rows(
            "SELECT line_cost FROM sale_lines WHERE sale_id = $saleId UNION " +
                "SELECT line_total - line_cost FROM sale_lines WHERE sale_id = $saleId UNION " +
                "SELECT total_cost FROM sales WHERE id = $saleId UNION " +
                "SELECT net_total - total_cost FROM sales WHERE id = $saleId",
        ).map(String::toLong).toSet()
        val shown =
            document.lines.map { it.amount.minor } + document.amounts.map { it.amount.minor }
        assertEquals(emptySet<Long>(), shown.toSet() intersect hidden)
    }

    /** Las líneas de [document]: nombre, cantidad y lo que cobra. */
    private fun lineRows(document: ReceiptDocument): List<String> =
        document.lines.map { "${it.productName} ${it.quantity.milli} ${it.amount.minor}" }

    /** Las cifras de [document], en el orden del papel: tipo y monto. */
    private fun amountRows(document: ReceiptDocument): List<String> =
        document.amounts.map { "${it.javaClass.simpleName} ${it.amount.minor}" }

    /** Una venta de la lista de pendientes: número, total, cliente, marca y referencia. */
    private fun fiscalRow(sale: FiscalSale): String =
        "${sale.saleId} ${sale.total.minor} ${sale.customerName ?: "-"} ${sale.status} " +
            (sale.externalReference ?: "-")

    /**
     * El papel de [document] en una térmica de 58 mm: una barra negra por renglón, con su
     * espacio. El dibujo real del comprobante es de la interfaz y tiene sus propias pruebas; aquí
     * importa que el documento llegue entero a la impresora.
     */
    private fun paperOf(document: ReceiptDocument): Bitmap {
        val rows = document.lines.size + document.amounts.size
        val bitmap = Bitmap.createBitmap(PAPER_DOTS, rows * ROW_DOTS, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap).apply { drawColor(Color.WHITE) }
        val ink = Paint().apply { color = Color.BLACK }
        repeat(rows) { row ->
            val top = (row * ROW_DOTS).toFloat()
            canvas.drawRect(0f, top, PAPER_DOTS.toFloat(), top + BAR_DOTS, ink)
        }
        return bitmap
    }

    /** Una foto inventada de un comprobante en papel: fondo claro y un bloque de texto. */
    private fun photo(name: String): String {
        val bitmap = Bitmap.createBitmap(900, 1_200, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.rgb(245, 240, 230))
            drawRect(150f, 200f, 750f, 900f, Paint().apply { color = Color.DKGRAY })
        }
        val source = File(inputs, name)
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
        return Uri.fromFile(source).toString()
    }

    private fun imported(result: ImageImport): StoredImage =
        (result as? ImageImport.Imported)?.image ?: throw AssertionError("No se importó: $result")

    private fun sheetOf(read: ImportFileRead): ImportSheet =
        (read as? ImportFileRead.Read)?.sheet ?: throw AssertionError("No se leyó: $read")

    /** La columna que reconoce [matcher] en cada encabezado de [sheet], o `null`. */
    private fun columnsOf(
        matcher: ImportColumnMatcher,
        sheet: ImportSheet,
        target: ImportTarget = ImportTarget.PRODUCTS,
    ): List<String?> = matcher.match(sheet.header, target).map { (it as? ColumnMatch.Field)?.key }

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

    /** Cantidad, costo promedio y valor del stock guardados en el producto. */
    private suspend fun cached(ports: DataPorts, productId: Long): String {
        val product = requireNotNull(ports.products.findById(productId))
        return "${product.onHand.milli} ${product.averageCost.minor} ${product.stockValue.minor}"
    }

    private suspend fun changeSeq(): Long = count("SELECT change_seq FROM backup_state")

    private suspend fun rows(sql: String): List<String> =
        database.useReaderConnection { it.texts(sql) }

    private fun line(productId: Long, milli: Long) =
        SaleLineRequest(productId, QuantityMilli(milli))

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

        val SEMICOLON = CsvSeparator.SEMICOLON
        val JAN_1 = requireNotNull(BusinessDate.parse("2026-01-01"))
        const val DETAILS = "Calle inventada 123"
        const val FOOTER = "Gracias por su compra"
        const val PORTAL = "https://portal.example.com/registro"
        const val ZIP_URI = "content://drill/export.zip"
        const val XLSX_URI = "content://drill/export.xlsx"

        /** Una térmica de 58 mm: 384 puntos de ancho, y cada renglón, 8 negros y 8 blancos. */
        const val PAPER_DOTS = 384
        const val ROW_DOTS = 16
        const val BAR_DOTS = 8

        /** Las clases de los documentos y de la lista de pendientes, con sus cifras. */
        val DOCUMENT_TYPES: List<Class<*>> = listOf(
            ReceiptDocument::class.java,
            ReceiptHeader::class.java,
            ReceiptLine::class.java,
            FiscalSale::class.java,
        ) + ReceiptAmount::class.java.declaredClasses

        /** Los encabezados en español del CSV, como los nombra la interfaz. */
        val HEADERS = mapOf(
            "name" to listOf("Nombre"),
            "category" to listOf("Categoría"),
            "sale_price" to listOf("Precio de venta"),
            "initial_cost" to listOf("Costo inicial"),
            "on_hand" to listOf("Existencia"),
            "phone" to listOf("Teléfono"),
            "email" to listOf("Correo"),
        )

        fun seedLabel(systemKey: String) = "seed:$systemKey"

        /** Nombres de archivo y encabezados por su clave, y las filas iniciales por su clave. */
        val NAMING = object : CsvNaming {
            override fun fileName(table: CsvExportTable): String = "${table.key}.csv"

            override fun header(column: String): String = column

            override fun seedName(table: SeedTable, systemKey: String): String =
                seedLabel(systemKey)
        }

        /** Los archivos donde el día deja filas, en el orden de la exportación. */
        val DRILL_FILES = listOf(
            "products.csv",
            "customers.csv",
            "sales.csv",
            "sale_lines.csv",
            "sale_payments.csv",
            "stock_movements.csv",
            "money_movements.csv",
            "customer_ledger.csv",
            "expenses.csv",
            "quotes.csv",
            "quote_lines.csv",
            "orders.csv",
            "order_lines.csv",
            "sale_fiscal_marks.csv",
            "document_attachments.csv",
        )

        val CANDLE = ProductForm(
            name = "Vela inventada",
            salePrice = Money(3_000),
            cost = Money(1_237),
        )
        val MUG =
            ProductForm(name = "Taza inventada", salePrice = Money(5_000), cost = Money(2_411))
        val BOX =
            ProductForm(
                name = "Caja de regalo inventada",
                salePrice = Money(1_500),
                cost = Money(613),
            )
    }
}
