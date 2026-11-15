package io.github.yesidrobayo.shopledger.data.repository

import androidx.room3.useReaderConnection
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.DataPorts
import io.github.yesidrobayo.shopledger.data.backup.InvariantQueries
import io.github.yesidrobayo.shopledger.data.backup.MemoryLog
import io.github.yesidrobayo.shopledger.data.export.SummaryPackageWriter
import io.github.yesidrobayo.shopledger.data.export.csvCells
import io.github.yesidrobayo.shopledger.data.export.csvLines
import io.github.yesidrobayo.shopledger.data.export.recordCsvExported
import io.github.yesidrobayo.shopledger.data.export.unzipCsv
import io.github.yesidrobayo.shopledger.data.local.catalog.CREATED_AT
import io.github.yesidrobayo.shopledger.data.local.catalog.CatalogDatabaseTest
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
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductForm
import io.github.yesidrobayo.shopledger.domain.model.catalog.SupplierForm
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerForm
import io.github.yesidrobayo.shopledger.domain.model.expense.ExpenseCategoryDraft
import io.github.yesidrobayo.shopledger.domain.model.expense.ExpenseRequest
import io.github.yesidrobayo.shopledger.domain.model.expense.Recurrence
import io.github.yesidrobayo.shopledger.domain.model.expense.RecurringExpenseDraft
import io.github.yesidrobayo.shopledger.domain.model.export.CsvExport
import io.github.yesidrobayo.shopledger.domain.model.export.CsvExportTable
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccountDraft
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccountKind
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodDraft
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodKind
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseLineAmount
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseLineRequest
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseRequest
import io.github.yesidrobayo.shopledger.domain.model.region.DecimalSeparator
import io.github.yesidrobayo.shopledger.domain.model.report.BreakEven
import io.github.yesidrobayo.shopledger.domain.model.report.CashFlowHorizon
import io.github.yesidrobayo.shopledger.domain.model.report.CashFlowProjection
import io.github.yesidrobayo.shopledger.domain.model.report.CustomerSales
import io.github.yesidrobayo.shopledger.domain.model.report.GoalAdvance
import io.github.yesidrobayo.shopledger.domain.model.report.IdleProduct
import io.github.yesidrobayo.shopledger.domain.model.report.LimitLevel
import io.github.yesidrobayo.shopledger.domain.model.report.ManagementSummary
import io.github.yesidrobayo.shopledger.domain.model.report.MonthGoals
import io.github.yesidrobayo.shopledger.domain.model.report.PaymentMethodTotal
import io.github.yesidrobayo.shopledger.domain.model.report.PriceIndexEntry
import io.github.yesidrobayo.shopledger.domain.model.report.PriceIndexView
import io.github.yesidrobayo.shopledger.domain.model.report.ProfitConcentration
import io.github.yesidrobayo.shopledger.domain.model.report.ProfitLeader
import io.github.yesidrobayo.shopledger.domain.model.report.RealComparison
import io.github.yesidrobayo.shopledger.domain.model.report.RealValue
import io.github.yesidrobayo.shopledger.domain.model.report.RevenueLimitView
import io.github.yesidrobayo.shopledger.domain.model.report.SummaryContent
import io.github.yesidrobayo.shopledger.domain.model.report.SummaryFile
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SalePaymentRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleRequest
import io.github.yesidrobayo.shopledger.domain.model.settings.CsvSeparator
import io.github.yesidrobayo.shopledger.domain.model.settings.CurrencyCode
import io.github.yesidrobayo.shopledger.domain.model.settings.RegionalSettingsInput
import io.github.yesidrobayo.shopledger.domain.model.settings.RevenueLimit
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.PaymentMethodNames
import io.github.yesidrobayo.shopledger.domain.port.SummaryNaming
import io.github.yesidrobayo.shopledger.domain.rule.export.CsvRowWriter
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CreateProductUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CreateSupplierUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveProductResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveSupplierResult
import io.github.yesidrobayo.shopledger.domain.usecase.customer.CreateCustomerUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.customer.SaveCustomerResult
import io.github.yesidrobayo.shopledger.domain.usecase.expense.ObserveRecurringExpensesUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.expense.PrepareRecurringRegistrationUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.expense.RegisterExpenseResult
import io.github.yesidrobayo.shopledger.domain.usecase.expense.RegisterExpenseUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.expense.SaveExpenseCategoryResult
import io.github.yesidrobayo.shopledger.domain.usecase.expense.SaveExpenseCategoryUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.expense.SaveRecurringExpenseResult
import io.github.yesidrobayo.shopledger.domain.usecase.expense.SaveRecurringExpenseUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.money.DocumentMoney
import io.github.yesidrobayo.shopledger.domain.usecase.money.MoneyLedgerWriter
import io.github.yesidrobayo.shopledger.domain.usecase.money.SaveMoneyAccountResult
import io.github.yesidrobayo.shopledger.domain.usecase.money.SaveMoneyAccountUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.payment.EditPaymentMethodUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.payment.SavePaymentMethodResult
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.RegisterPurchaseResult
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.RegisterPurchaseUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.domain.usecase.region.CurrencyLock
import io.github.yesidrobayo.shopledger.domain.usecase.region.SaveRegionalSettingsResult
import io.github.yesidrobayo.shopledger.domain.usecase.region.SaveRegionalSettingsUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.report.CreateManagementSummaryUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.report.MonthGoalView
import io.github.yesidrobayo.shopledger.domain.usecase.report.ObserveBreakEvenUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.report.ObserveCashFlowUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.report.ObserveCurrentMonthGoalUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.report.ObserveManagementSummaryUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.report.ObserveMonthGoalsUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.report.ObservePeriodAnalysisUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.report.ObservePeriodReportUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.report.ObservePriceIndexUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.report.ObserveRealComparisonUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.report.ObserveRevenueLimitUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.report.SaveGoalsResult
import io.github.yesidrobayo.shopledger.domain.usecase.report.SaveLimitResult
import io.github.yesidrobayo.shopledger.domain.usecase.report.SaveMonthGoalsUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.report.SavePriceIndexMonthUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.report.SavePriceIndexResult
import io.github.yesidrobayo.shopledger.domain.usecase.report.SaveRevenueLimitUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.report.SummaryTarget
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleResult
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.tax.ObserveTaxEstimateUseCase
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.YearMonth
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Simulacro de los reportes sobre una base nueva, por el camino de la app: los puertos de la base
 * abierta y los casos de uso armados como los arman sus pantallas. Dos meses de una panadería
 * inventada: un día de diciembre con dos ventas, y un enero con compras al contado y a plazo,
 * ventas en efectivo, con tarjeta que se acredita dos días después y a crédito de una clienta,
 * gastos con el alquiler en una categoría marcada como fija, un alquiler mensual del día 31 que se
 * confirma el 31 de enero y un servicio que se propone y nadie confirma, las metas del mes, un
 * límite de ingresos escrito por la persona y dos meses de índice de precios.
 *
 * El 31 de enero se leen los reportes como los leen sus pantallas, y cada cifra se compara con un
 * valor calculado a mano en los comentarios: el resumen de gestión y su paquete `.zip` con sus
 * CSV, el próximo vencimiento del alquiler, el flujo de caja a 7 y 30 días, el punto de
 * equilibrio, el avance de las metas, el nivel del límite, el análisis del 80 % y de lo que no se
 * movió, y la comparación en términos reales. Cada consulta de invariantes del modelo de datos da
 * cero filas.
 *
 * El PDF del resumen lo dibuja la interfaz y tiene sus propias pruebas: aquí entra un PDF falso
 * pequeño, que el paquete copia tal cual.
 *
 * El negocio, los productos, la clienta, el proveedor, el índice y los montos son inventados. La
 * moneda es `XTS`, el código de pruebas de ISO 4217, con dos decimales: los montos van en su
 * unidad menor.
 */
@RunWith(AndroidJUnit4::class)
internal class ReportsDrillTest : CatalogDatabaseTest() {
    // La base nace sin moneda, como en un teléfono nuevo, y la persona la confirma en su pantalla.
    // Así la cuenta de efectivo inicial toma la moneda del negocio en esa misma transacción, como
    // en la app, y entra en el flujo de caja.
    override val confirmsCurrency: Boolean = false

    /** Un reloj que avanza un segundo en cada lectura, desde el mediodía del día que fija [on]. */
    private val ticks = AtomicLong(CREATED_AT)
    private val clock = Clock { Instant.fromEpochMilliseconds(ticks.addAndGet(1_000L)) }

    private lateinit var outputs: File

    @Before
    fun createFolder() {
        outputs = File(context.cacheDir, "reports-drill-" + Random.nextLong().toULong())
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

    private val reports by lazy {
        ObservePeriodReportUseCase(ports.periodReports)
    }

    private val createProduct by lazy {
        CreateProductUseCase(
            tx, ports.products, ports.categories, ports.suppliers, ports.units,
            ports.systemRows, ports.priceHistory, ports.stockLedger, ports.lowStockAlerts,
            calendar, CurrencyLock(ports.settings), clock, ports.mediaAssets,
        )
    }

    private val editMethod by lazy {
        EditPaymentMethodUseCase(tx, ports.paymentMethods, ports.mediaAssets, clock)
    }

    private val saveAccount by lazy {
        SaveMoneyAccountUseCase(
            tx,
            ports.settings,
            ports.moneyAccounts,
            MoneyLedgerWriter(ports.moneyAccounts, ports.moneyLedger),
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
            recurring = ports.recurringExpenses,
        )
    }

    private val saveRecurring by lazy {
        SaveRecurringExpenseUseCase(tx, ports.recurringExpenses, ports.expenseCategories, clock)
    }

    private val recurringOverview by lazy {
        ObserveRecurringExpensesUseCase(ports.recurringExpenses, calendar, clock)
    }

    private val prepareRecurring by lazy {
        PrepareRecurringRegistrationUseCase(ports.recurringExpenses, ports.expenseCategories)
    }

    private val monthGoals by lazy {
        ObserveMonthGoalsUseCase(ports.goals, reports)
    }

    private val summaryPackage by lazy {
        SummaryPackageWriter(
            rows = { ports.csvRows },
            recordExport = { recordCsvExported(ports, clock, MemoryLog()) },
            log = MemoryLog(),
            ioContext = Dispatchers.IO,
        )
    }

    private val createSummary by lazy {
        CreateManagementSummaryUseCase(ports.settings, summaryPackage) { DecimalSeparator.COMMA }
    }

    // Lo que comparten los pasos: cuentas, medios de pago, productos, clienta, proveedor, ventas
    // y gastos recurrentes.
    private var drawer = 0L
    private var bank = 0L
    private var cashMethod = 0L
    private var cardMethod = 0L
    private var onAccount = 0L
    private var bread = 0L
    private var coffee = 0L
    private var jam = 0L
    private var candle = 0L
    private var customer = 0L
    private var supplier = 0L
    private var rent = 0L
    private var internet = 0L
    private val januarySales = mutableListOf<Long>()

    @Test
    fun twoMonthsOfReportsMatchHandWrittenNumbers() = runBlocking {
        configure()
        december()
        januaryBooks()
        lastDayOfJanuary()
        recurringExpenses()
        managementSummary()
        summaryPackage()
        cashFlow()
        breakEven()
        goalsAndLimit()
        analysis()
        realTerms()
        booksOfTheMonth()
        invariants()
    }

    /** La moneda, las cuentas, los medios de pago, el catálogo, la clienta y el proveedor. */
    private suspend fun configure() {
        // 1. El 1 de diciembre, como en sus pantallas: la moneda, un banco con 6.000,00 de saldo
        // inicial, el efectivo a la caja inicial y la tarjeta al banco, que acredita en 2 días y
        // sin comisión.
        on(DEC_1)
        assertEquals(
            SaveRegionalSettingsResult.Saved,
            SaveRegionalSettingsUseCase(tx, ports.settings, clock)(REGION),
        )
        drawer = count("SELECT id FROM money_accounts WHERE system_key = 'CASH'")
        bank = (
            saveAccount(
                null,
                MoneyAccountDraft(
                    "Banco inventado",
                    MoneyAccountKind.BANK,
                    openingBalance = Money(600_000),
                ),
            ) as SaveMoneyAccountResult.Saved
            ).accountId
        cashMethod = count("SELECT id FROM payment_methods WHERE system_key = 'CASH'")
        cardMethod = count("SELECT id FROM payment_methods WHERE system_key = 'CARD'")
        onAccount = count("SELECT id FROM payment_methods WHERE system_key = 'ON_ACCOUNT'")
        link(cashMethod, drawer, settlementDays = 0)
        link(cardMethod, bank, settlementDays = 2)

        // 2. El catálogo, con stock en la ubicación inicial y costos conocidos: pan a 20,00, café
        // a 120,00, mermelada a 80,00 y velas a 90,00, que nunca se venden. La clienta y el
        // proveedor llevan solo su nombre.
        bread = saved(createProduct(BREAD, QuantityMilli(200_000)))
        coffee = saved(createProduct(COFFEE, QuantityMilli(60_000)))
        jam = saved(createProduct(JAM, QuantityMilli(30_000)))
        candle = saved(createProduct(CANDLE, QuantityMilli(4_000)))
        customer = (
            CreateCustomerUseCase(tx, ports.customers, clock)(
                CustomerForm("Clienta inventada"),
            ) as SaveCustomerResult.Saved
            ).customerId
        supplier = (
            CreateSupplierUseCase(tx, ports.suppliers, clock)(
                SupplierForm("Distribuidora inventada"),
            ) as SaveSupplierResult.Saved
            ).supplierId
    }

    /** Las ventas de diciembre, la base de la comparación en términos reales. */
    private suspend fun december() {
        // 3. El 15 de diciembre, dos ventas en efectivo: 100 panes a 50,00 y 25 cafés a 200,00.
        // Diciembre vende 1.000.000 con un costo de 200.000 + 300.000 = 500.000.
        on(DEC_15)
        registered(registerSale(SaleRequest(listOf(line(bread, 100)), cashMethod)))
        registered(registerSale(SaleRequest(listOf(line(coffee, 25)), cashMethod)))
    }

    /** Las compras, el gasto variable, la categoría fija y los recurrentes de enero. */
    private suspend fun januaryBooks() {
        // 4. El 5 de enero: la luz, 318,00, sale de la caja. El alquiler se marca como gasto
        // fijo, y la luz no. Se guardan dos gastos recurrentes, que no registran nada: el
        // alquiler, 4.182,00 cada día 31 desde el banco, y el internet, 150,00 cada día 25.
        on(JAN_5)
        val utilities = categoryOf("UTILITIES")
        expense(ExpenseRequest(Money(31_800), utilities, "Luz inventada", moneyAccountId = drawer))
        val rentCategory = categoryOf("RENT")
        assertEquals(
            SaveExpenseCategoryResult.Saved(rentCategory),
            SaveExpenseCategoryUseCase(tx, ports.expenseCategories, clock)(
                rentCategory,
                ExpenseCategoryDraft("", isFixed = true),
            ),
        )
        rent = recurring(
            RecurringExpenseDraft(
                amount = Money(418_200),
                categoryId = rentCategory,
                description = "Alquiler inventado",
                recurrence = Recurrence.MONTHLY,
                dayOfPeriod = 31,
                nextDueDate = JAN_31,
                moneyAccountId = bank,
                reminderEnabled = true,
            ),
        )
        internet = recurring(
            RecurringExpenseDraft(
                amount = Money(15_000),
                categoryId = categoryOf("INTERNET_PHONE"),
                description = "Internet inventado",
                recurrence = Recurrence.MONTHLY,
                dayOfPeriod = 25,
                nextDueDate = JAN_25,
            ),
        )
        assertEquals(1L, count("SELECT COUNT(*) FROM expenses"))

        // 5. El 8 de enero, 10 cafés a 120,00 pagados desde el banco: 120.000, y el costo
        // promedio no cambia.
        on(JAN_8)
        purchase(
            PurchaseRequest(
                listOf(PurchaseLineRequest(coffee, milli(10), unitCost(12_000))),
                supplierId = supplier,
                invoiceReference = "C-0001",
                moneyAccountId = bank,
            ),
        )

        // 6. El 10 de enero, 60 panes en efectivo: 300.000, con un costo de 120.000.
        on(JAN_10)
        januarySales += registered(
            registerSale(SaleRequest(listOf(line(bread, 60)), cashMethod)),
        )

        // 7. El 20 de enero, la clienta compra 15 cafés y 10 panes en efectivo: 300.000 + 50.000
        // = 350.000, con un costo de 180.000 + 20.000 = 200.000.
        on(JAN_20)
        januarySales += registered(
            registerSale(
                SaleRequest(
                    listOf(line(coffee, 15), line(bread, 10)),
                    cashMethod,
                    customerId = customer,
                ),
            ),
        )

        // 8. El 25 de enero, 20 mermeladas a 80,00 a plazo, sin pagar nada: 160.000 que vencen
        // el 4 de febrero. Hoy vence el internet, y nadie lo confirma.
        on(JAN_25)
        purchase(
            PurchaseRequest(
                listOf(PurchaseLineRequest(jam, milli(20), unitCost(8_000))),
                supplierId = supplier,
                invoiceReference = "C-0002",
                amountPaid = Money.ZERO,
                dueDate = FEB_4,
            ),
        )
    }

    /** Las ventas, el alquiler, las metas, el límite y el índice del 31 de enero. */
    private suspend fun lastDayOfJanuary() {
        // 9. El 31 de enero, 10 cafés con tarjeta: 200.000, con un costo de 120.000, que el banco
        // acredita el 2 de febrero. Y la clienta lleva 25 mermeladas a crédito, que vencen el 20
        // de febrero: 250.000, con un costo de 200.000.
        on(JAN_31)
        januarySales += registered(
            registerSale(SaleRequest(listOf(line(coffee, 10)), cardMethod)),
        )
        januarySales += registered(
            registerSale(
                SaleRequest(
                    lines = listOf(line(jam, 25)),
                    paymentMethodId = onAccount,
                    payments = listOf(SalePaymentRequest(onAccount, Money(250_000))),
                    customerId = customer,
                    creditDueDate = FEB_20,
                ),
            ),
        )

        // 10. Las metas de enero: vender 1.375.000 y que queden 40.000 de ganancia final. El
        // límite de ingresos lo escribe la persona: 2.500.000 en 12 meses. La app no conoce
        // ningún tope.
        assertEquals(
            SaveGoalsResult.Saved,
            SaveMonthGoalsUseCase(tx, ports.goals, clock)(
                JANUARY.yearMonth,
                Money(1_375_000),
                Money(40_000),
            ),
        )
        assertEquals(
            SaveLimitResult.Saved,
            SaveRevenueLimitUseCase(tx, ports.goals, ports.settings, clock)(LIMIT),
        )

        // 11. El índice de precios, escrito por la persona: +2,50 % en diciembre y +3,00 % en
        // enero. Febrero todavía no empezó y no se puede escribir.
        val saveIndex = SavePriceIndexMonthUseCase(tx, ports.settings, ports.priceIndex, clock)
        assertEquals(
            SavePriceIndexResult.Saved,
            saveIndex(DECEMBER.yearMonth, BasisPoints(250), INDEX_NOTE, editing = false),
        )
        assertEquals(
            SavePriceIndexResult.Saved,
            saveIndex(JANUARY.yearMonth, BasisPoints(300), INDEX_NOTE, editing = false),
        )
        assertEquals(
            SavePriceIndexResult.FutureMonth,
            saveIndex(YearMonth(2027, 2), BasisPoints(100), INDEX_NOTE, editing = false),
        )
        assertEquals(
            PriceIndexView(
                entries = listOf(
                    PriceIndexEntry(JANUARY.yearMonth, BasisPoints(300), INDEX_NOTE),
                    PriceIndexEntry(DECEMBER.yearMonth, BasisPoints(250), INDEX_NOTE),
                ),
                missing = emptyList(),
            ),
            ObservePriceIndexUseCase(ports.priceIndex)().first(),
        )
    }

    /** Los gastos recurrentes: se proponen, uno se confirma una vez y el otro no registra nada. */
    private suspend fun recurringExpenses() {
        // 12. El 31 de enero se proponen los dos, del más atrasado al más reciente: el internet
        // del 25 y el alquiler de hoy. Ninguno registró un gasto.
        val proposed = recurringOverview().first()
        assertEquals(JAN_31, proposed.today)
        assertEquals(listOf(internet, rent), proposed.due.map { it.id })
        assertEquals(
            0L,
            count("SELECT COUNT(*) FROM expenses WHERE recurring_expense_id IS NOT NULL"),
        )

        // 13. La persona confirma el alquiler desde los gastos, con sus datos puestos: un gasto
        // normal de 4.182,00 desde el banco, ligado al recurrente. Su próxima fecha es el día 31
        // de febrero, que no existe: el último del mes, el 28.
        val registration = requireNotNull(prepareRecurring(rent))
        val request = ExpenseRequest(
            amount = registration.amount,
            categoryId = requireNotNull(registration.categoryId),
            description = registration.description,
            moneyAccountId = registration.moneyAccountId,
            recurring = registration.occurrence,
        )
        val rentExpense = expense(request)
        assertEquals(
            "418200 $rent $bank 2027-01-31",
            rows(
                "SELECT amount || ' ' || recurring_expense_id || ' ' || money_account_id || ' ' " +
                    "|| business_date FROM expenses WHERE id = $rentExpense",
            ).single(),
        )
        assertEquals(FEB_28, nextDueOf(rent))
        // Confirmarlo otra vez lo contaría dos veces: no se registra nada.
        assertEquals(RegisterExpenseResult.RecurringAlreadyHandled, registerExpense(request))

        // 14. El internet sigue propuesto desde el 25 y no registró nada; el alquiler queda
        // programado para el 28 de febrero.
        val after = recurringOverview().first()
        assertEquals(listOf(internet), after.due.map { it.id })
        assertEquals(listOf(rent), after.scheduled.map { it.id })
        assertEquals(
            0L,
            count("SELECT COUNT(*) FROM expenses WHERE recurring_expense_id = $internet"),
        )
        assertEquals(JAN_25, nextDueOf(internet))
        assertEquals(2L, count("SELECT COUNT(*) FROM expenses"))
    }

    /** Las cifras del resumen de gestión de enero, como las lee su pantalla. */
    private suspend fun managementSummary() {
        // 15. Enero vende 300.000 + 350.000 + 200.000 + 250.000 = 1.100.000, con un costo de
        // 120.000 + 200.000 + 120.000 + 200.000 = 640.000: 460.000 de ganancia en ventas, y un
        // margen de 460.000 × 10000 / 1.100.000 = 4181,8, que redondea a 4182 bp. Los gastos,
        // 31.800 + 418.200 = 450.000, dejan 10.000 de ganancia final. El negocio no cobra
        // impuesto. Por método, de mayor a menor: efectivo 650.000 en dos ventas, a crédito
        // 250.000 y tarjeta 200.000, sin comisiones.
        val summary = observeSummary()
        assertEquals(
            ManagementSummary(
                period = JANUARY,
                profit = ProfitSummary(
                    netSales = Money(1_100_000),
                    cogs = Money(640_000),
                    grossProfit = Money(460_000),
                    grossMargin = Margin.Defined(BasisPoints(4_182)),
                    recordedExpenses = Money(450_000),
                    netShrinkage = Money.ZERO,
                    paymentCosts = Money.ZERO,
                    totalExpenses = Money(450_000),
                    netProfit = Money(10_000),
                ),
                tax = null,
                methods = listOf(
                    method("Initial cash", PaymentMethodKind.CASH, 2, 650_000),
                    method("Initial on_account", PaymentMethodKind.ON_ACCOUNT, 1, 250_000),
                    method("Initial card", PaymentMethodKind.CARD, 1, 200_000),
                ),
                salesWithoutCost = 0L,
                hasMovements = true,
                contents = SummaryContent.entries.toList(),
            ),
            summary,
        )
    }

    /** El paquete `.zip` del resumen de enero, con el PDF falso y todos los CSV encendidos. */
    private suspend fun summaryPackage() {
        // 16. Con todo encendido: el PDF primero, tal cual, y los CSV de enero. Diciembre no
        // entra en ningún archivo con fecha; el inventario al cierre suma el libro hasta el 31.
        val summary = observeSummary()
        val pdf = File(outputs, "summary.pdf").apply { writeBytes(PDF_BYTES) }
        val zip = File(outputs, "summary.zip")
        val result = createSummary(
            period = JANUARY,
            chosen = summary.contents.toSet(),
            offered = summary.contents,
            target = SummaryTarget(zip.absolutePath, "summary.pdf", pdf.absolutePath),
            naming = NAMING,
            progress = {},
        )
        val files = unzipCsv(zip)
        assertEquals(
            listOf(
                "summary.pdf",
                "sales.csv",
                "sale_lines.csv",
                "sale_payments.csv",
                "sale_returns.csv",
                "sale_return_lines.csv",
                "purchases.csv",
                "purchase_lines.csv",
                "purchase_returns.csv",
                "purchase_return_lines.csv",
                "expenses.csv",
                "closing_inventory.csv",
                "customers.csv",
                "suppliers.csv",
            ),
            files.keys.toList(),
        )
        assertEquals(PDF_BYTES.toString(Charsets.US_ASCII), files.getValue("summary.pdf"))
        val csvFiles = files.keys.drop(1)
        val dataRows = csvFiles.associateWith { csvLines(files.getValue(it)).size - 1 }
        // Cuatro ventas con cinco líneas y cuatro pagos, dos compras con sus dos líneas, dos
        // gastos, cuatro productos al cierre, una clienta y un proveedor. Sin devoluciones.
        assertEquals(
            listOf(4, 5, 4, 0, 0, 2, 2, 0, 0, 2, 4, 1, 1),
            csvFiles.map { dataRows.getValue(it) },
        )
        assertEquals(CsvExport.Saved(files = 14, rows = 25L), result)
        assertEquals(
            1L,
            count("SELECT COUNT(*) FROM audit_events WHERE event_type = 'EXPORT_CSV'"),
        )

        // 17. Quien lleva la contabilidad recibe lo vendido, su costo y su ganancia en ventas, con
        // la coma decimal del negocio: los mismos números del resumen, venta por venta.
        val (cashSale, customerSale, cardSale, creditSale) = januarySales
        assertEquals(
            listOf(
                "$cashSale 3000,00 1200,00 1800,00",
                "$customerSale 3500,00 2000,00 1500,00",
                "$cardSale 2000,00 1200,00 800,00",
                "$creditSale 2500,00 2000,00 500,00",
            ),
            cellsOf(files, "sales.csv", "sale_id", "net_total", "total_cost", "sales_profit"),
        )
        assertEquals(
            listOf("\"seed:UTILITIES\" 318,00", "\"seed:RENT\" 4182,00"),
            cellsOf(files, "expenses.csv", "category", "amount"),
        )
        // Pan: 200 − 100 − 60 − 10 = 30; café: 60 − 25 + 10 − 15 − 10 = 20; mermelada: 30 + 20
        // − 25 = 25; velas: 4.
        assertEquals(
            listOf(
                "\"Pan inventado\" 30,000",
                "\"Café inventado\" 20,000",
                "\"Mermelada inventada\" 25,000",
                "\"Vela quieta inventada\" 4,000",
            ),
            cellsOf(files, "closing_inventory.csv", "product", "on_hand"),
        )
        // Los pagos, los gastos, el inventario al cierre y los datos de la clienta y del
        // proveedor no llevan costo, margen ni ganancia.
        for (name in COST_FREE_FILES) {
            val header = csvLines(files.getValue(name)).first().lowercase()
            assertFalse(name, listOf("cost", "margin", "profit").any { it in header })
        }
    }

    /** El flujo de caja proyectado a 7 y 30 días desde el 31 de enero. */
    private suspend fun cashFlow() {
        // 18. Saldos: la caja con 1.618.200 y el banco con 261.800, 1.880.000, de los que 200.000
        // de la tarjeta se acreditan el 2 de febrero: disponible hoy 1.680.000.
        // - Hasta el 7 de febrero: se acreditan 200.000, vencen 160.000 de la compra a plazo y
        //   150,00 del internet atrasado: 1.680.000 + 200.000 − 160.000 − 15.000 = 1.705.000.
        // - Hasta el 2 de marzo: además, 250.000 por cobrar a la clienta, el internet del 25 de
        //   febrero y el alquiler del 28: 1.680.000 + 200.000 + 250.000 − 160.000 − 448.200 =
        //   1.521.800.
        val projection = ObserveCashFlowUseCase(
            source = ports.cashFlow,
            recurring = ports.recurringExpenses,
            usage = ports.moduleUsage,
            calendar = calendar,
            clock = clock,
        )().first()
        assertEquals(
            CashFlowProjection(
                today = JAN_31,
                availableToday = Money(1_680_000),
                horizons = listOf(
                    CashFlowHorizon(
                        days = 7,
                        until = FEB_7,
                        settlements = Money(200_000),
                        receivables = Money.ZERO,
                        payables = Money(160_000),
                        recurring = Money(15_000),
                        projected = Money(1_705_000),
                    ),
                    CashFlowHorizon(
                        days = 30,
                        until = MAR_2,
                        settlements = Money(200_000),
                        receivables = Money(250_000),
                        payables = Money(160_000),
                        recurring = Money(448_200),
                        projected = Money(1_521_800),
                    ),
                ),
            ),
            projection,
        )
    }

    /** El punto de equilibrio de enero y el de diciembre, sin gastos fijos. */
    private suspend fun breakEven() {
        // 19. Gastos fijos de enero: solo el alquiler, 418.200. Con el margen de 4182 bp hay que
        // vender 418.200 × 10000 / 4182 = 1.000.000, y enero ya vendió 1.100.000: no falta nada.
        // Diciembre vendió, pero no tuvo gastos fijos.
        val observe = ObserveBreakEvenUseCase(ports.planningReports, reports)
        assertEquals(
            BreakEven.Calculated(
                fixedExpenses = Money(418_200),
                margin = BasisPoints(4_182),
                breakEvenSales = Money(1_000_000),
                netSales = Money(1_100_000),
                remaining = Money.ZERO,
            ),
            observe(JANUARY).first(),
        )
        assertEquals(BreakEven.NoFixedExpenses, observe(DECEMBER).first())
    }

    /** El avance de las metas de enero, la tarjeta de Inicio y el nivel del límite. */
    private suspend fun goalsAndLimit() {
        // 20. Ventas: 1.100.000 × 10000 / 1.375.000 = 8000 bp. Ganancia final: 10.000 × 10000 /
        // 40.000 = 2500 bp. Diciembre no tiene metas, y no se muestra nada. Inicio muestra la
        // meta de ventas del mes en curso.
        assertEquals(
            MonthGoalView(
                month = JANUARY.yearMonth,
                goals = MonthGoals(JANUARY.yearMonth, Money(1_375_000), Money(40_000)),
                sales = GoalAdvance(Money(1_100_000), Money(1_375_000), BasisPoints(8_000)),
                profit = GoalAdvance(Money(10_000), Money(40_000), BasisPoints(2_500)),
            ),
            monthGoals(JANUARY.yearMonth).first(),
        )
        assertEquals(
            MonthGoalView(DECEMBER.yearMonth, goals = null, sales = null, profit = null),
            monthGoals(DECEMBER.yearMonth).first(),
        )
        assertEquals(
            GoalAdvance(Money(1_100_000), Money(1_375_000), BasisPoints(8_000)),
            ObserveCurrentMonthGoalUseCase(calendar, monthGoals, clock)(flowOf(Unit)).first(),
        )

        // 21. El límite suma de febrero de 2026 a enero de 2027: 1.000.000 + 1.100.000 =
        // 2.100.000, que son 2.100.000 × 10000 / 2.500.000 = 8400 bp del límite: el primer
        // aviso. Las ventas empiezan el 15 de diciembre, dentro de la ventana.
        assertEquals(
            RevenueLimitView(
                limit = LIMIT,
                window = ReportPeriod.Range(date("2026-02-01"), JAN_31),
                accumulated = Money(2_100_000),
                used = BasisPoints(8_400),
                level = LimitLevel.FIRST_NOTICE,
                since = DEC_15,
            ),
            ObserveRevenueLimitUseCase(ports.settings, ports.planningReports, reports)(
                JANUARY.yearMonth,
            ).first(),
        )
    }

    /** El análisis de enero y de diciembre: el 80 %, lo que no se movió y la mejor clienta. */
    private suspend fun analysis() {
        // 22. Lo que dejó cada producto en enero: pan 60 × 30,00 + 10 × 30,00 = 210.000, café
        // 15 × 80,00 + 10 × 80,00 = 200.000 y mermelada 25 × 20,00 = 50.000, de 460.000. El pan
        // entra porque antes no había nada; el café, porque 210.000 no llega al 80 %; ahí se
        // corta, porque 410.000 ya pasa de 368.000. Partes: 210.000 / 460.000 = 4565 bp,
        // 200.000 / 460.000 = 4348 bp, y juntos 410.000 / 460.000 = 8913 bp. Sin movimiento en
        // enero solo están las 4 velas, que valen 4 × 90,00 = 36.000. La clienta compró dos
        // veces, 350.000 + 250.000 = 600.000.
        val observe = ObservePeriodAnalysisUseCase(ports.periodBreakdowns, ports.analysisReports)
        val january = observe(JANUARY).first()
        assertEquals(
            ProfitConcentration.Leaders(
                leaders = listOf(
                    ProfitLeader(bread, BREAD.name, Money(210_000), BasisPoints(4_565)),
                    ProfitLeader(coffee, COFFEE.name, Money(200_000), BasisPoints(4_348)),
                ),
                soldProducts = 3,
                reachedShare = BasisPoints(8_913),
            ),
            january.concentration,
        )
        assertEquals(listOf("$candle 4000 36000"), january.idle.products.map(::idleRow))
        assertEquals(1L, january.idle.total)
        assertEquals(
            listOf(CustomerSales(customer, "Clienta inventada", 2L, Money(600_000))),
            january.customers,
        )

        // 23. En diciembre no se vendieron mermeladas ni velas, que hoy tienen stock: 25 × 80,00
        // = 200.000 y 36.000, del stock más valioso al menos. Nadie compró con su nombre.
        val december = observe(DECEMBER).first()
        assertEquals(
            listOf("$jam 25000 200000", "$candle 4000 36000"),
            december.idle.products.map(::idleRow),
        )
        assertEquals(2L, december.idle.total)
        assertEquals(emptyList<CustomerSales>(), december.customers)
    }

    /** Enero frente a diciembre, en moneda corriente y a precios de diciembre. */
    private suspend fun realTerms() {
        // 24. Enero sigue en curso el 31, así que se compara con los 31 días de diciembre:
        // 1.000.000 frente a 1.100.000, 1000 bp más. A precios de diciembre, con el +3,00 % de
        // enero, 1.100.000 × 10000 / 10300 = 1.067.961,2, que redondea a 1.067.961: 680 bp más,
        // porque 67.961 × 10000 / 1.000.000 = 679,6.
        assertEquals(
            RealComparison(
                period = JANUARY,
                comparedTo = ReportPeriod.Range(DEC_1, date("2026-12-31")),
                base = Money(1_000_000),
                nominal = Money(1_100_000),
                nominalVariation = BasisPoints(1_000),
                real = RealValue.Estimated(Money(1_067_961), BasisPoints(680)),
            ),
            ObserveRealComparisonUseCase(ports.periodReports, ports.priceIndex)(
                JANUARY,
                JAN_31,
            ).first(),
        )
    }

    /** El stock, las cuentas y los saldos de terceros al cerrar enero. */
    private suspend fun booksOfTheMonth() {
        // 25. Cada producto con su stock, su costo promedio sin cambios y su valor.
        assertEquals(
            listOf(
                "$bread 30000 2000 60000",
                "$coffee 20000 12000 240000",
                "$jam 25000 8000 200000",
                "$candle 4000 9000 36000",
            ),
            listOf(bread, coffee, jam, candle).map { "$it ${cached(it)}" },
        )
        // La caja recibe las ventas en efectivo y paga la luz; el banco abre con 600.000, paga
        // la compra al contado y el alquiler, y recibe la tarjeta, que se acredita el 2 de
        // febrero.
        assertEquals(
            listOf(
                "SALE_RECEIPT 500000 500000",
                "SALE_RECEIPT 500000 1000000",
                "EXPENSE_PAYMENT -31800 968200",
                "SALE_RECEIPT 300000 1268200",
                "SALE_RECEIPT 350000 1618200",
            ),
            moneyRows(drawer),
        )
        assertEquals(
            listOf(
                "OPENING_BALANCE 600000 600000",
                "PURCHASE_PAYMENT -120000 480000",
                "SALE_RECEIPT 200000 680000",
                "EXPENSE_PAYMENT -418200 261800",
            ),
            moneyRows(bank),
        )
        assertEquals(
            listOf("2027-02-02"),
            rows(
                "SELECT available_date FROM money_movements WHERE money_account_id = $bank " +
                    "AND type = 'SALE_RECEIPT'",
            ),
        )
        // La clienta debe la venta a crédito y el negocio debe la compra a plazo.
        assertEquals(Money(250_000), requireNotNull(ports.customers.findById(customer)).balance)
        assertEquals(160_000L, count("SELECT balance FROM suppliers WHERE id = $supplier"))
    }

    /** Cada consulta de invariantes da cero filas. */
    private suspend fun invariants() {
        for (query in InvariantQueries.MODEL) {
            val sql = query.sql.trimEnd().removeSuffix(";")
            assertEquals(query.label, 0L, count("SELECT COUNT(*) FROM ($sql)"))
        }
    }

    // Apoyo

    /** Pone el reloj al mediodía de [day] en la zona del negocio, tres horas al oeste de UTC. */
    private fun on(day: BusinessDate) {
        ticks.set(Instant.parse("${day.date}T15:00:00Z").toEpochMilliseconds())
    }

    /** Enlaza el medio de pago [methodId] a la cuenta [accountId], como en su pantalla. */
    private suspend fun link(methodId: Long, accountId: Long, settlementDays: Int) {
        val current = requireNotNull(ports.paymentMethods.findById(methodId)).details
        assertEquals(
            SavePaymentMethodResult.Saved(methodId),
            editMethod(
                methodId,
                PaymentMethodDraft(
                    name = current.name,
                    kind = current.kind,
                    feeRate = current.feeRate,
                    feeFixed = current.feeFixed,
                    settlementDays = settlementDays,
                    priceAdjustment = current.priceAdjustment,
                    moneyAccountId = accountId,
                    instructionsText = current.instructionsText,
                ),
            ),
        )
    }

    /** El resumen de enero, como lo lee su pantalla. */
    private suspend fun observeSummary(): ManagementSummary {
        val ready = calendar.current() as BusinessCalendarResult.Ready
        val observe = ObserveManagementSummaryUseCase(
            ports.periodReports,
            ObserveTaxEstimateUseCase(ports.taxSettings, ports.taxReports),
            ports.periodBreakdowns,
            ports.managementSummaries,
        )
        return requireNotNull(observe(JANUARY, ready.calendar).first())
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
        val header = csvCells(lines.first().removePrefix(CsvRowWriter.BYTE_ORDER_MARK))
        val indexes = columns.map { column ->
            header.indexOf("\"$column\"").also { check(it >= 0) { "Falta la columna $column" } }
        }
        return lines.drop(1).map { line ->
            val cells = csvCells(line)
            indexes.joinToString(" ") { cells[it] }
        }
    }

    private suspend fun categoryOf(systemKey: String): Long =
        count("SELECT id FROM expense_categories WHERE system_key = '$systemKey'")

    private suspend fun recurring(draft: RecurringExpenseDraft): Long {
        val result = saveRecurring(null, draft)
        return (result as? SaveRecurringExpenseResult.Saved)?.recurringExpenseId
            ?: throw AssertionError("No se guardó: $result")
    }

    private suspend fun nextDueOf(recurringId: Long): BusinessDate =
        requireNotNull(ports.recurringExpenses.findById(recurringId)).details.nextDueDate

    private suspend fun expense(request: ExpenseRequest): Long {
        val result = registerExpense(request)
        return (result as? RegisterExpenseResult.Registered)?.expenseId
            ?: throw AssertionError("No se registró: $result")
    }

    private suspend fun purchase(request: PurchaseRequest) {
        val result = registerPurchase(request)
        if (result !is RegisterPurchaseResult.Registered) {
            throw AssertionError("No se registró: $result")
        }
    }

    /** Cantidad, costo promedio y valor del stock guardados en el producto. */
    private suspend fun cached(productId: Long): String {
        val product = requireNotNull(ports.products.findById(productId))
        return "${product.onHand.milli} ${product.averageCost.minor} ${product.stockValue.minor}"
    }

    /** Tipo, monto y saldo de cada movimiento de [accountId], en el orden del libro. */
    private suspend fun moneyRows(accountId: Long): List<String> = rows(
        "SELECT type || ' ' || amount || ' ' || balance_after FROM money_movements " +
            "WHERE money_account_id = $accountId ORDER BY id",
    )

    private suspend fun rows(sql: String): List<String> =
        database.useReaderConnection { it.texts(sql) }

    /** Un producto sin movimiento: su id, lo que hay y lo que vale. */
    private fun idleRow(product: IdleProduct): String =
        "${product.productId} ${product.onHand.milli} ${product.stockValue.minor}"

    private fun method(name: String, kind: PaymentMethodKind, sales: Long, amount: Long) =
        PaymentMethodTotal(name, kind, sales, Money(amount), Money.ZERO)

    private fun line(productId: Long, units: Long) = SaleLineRequest(productId, milli(units))

    private fun milli(units: Long) = QuantityMilli(units * 1_000)

    private fun unitCost(minor: Long) = PurchaseLineAmount.UnitCost(Money(minor))

    private fun saved(result: SaveProductResult): Long =
        (result as? SaveProductResult.Saved)?.productId
            ?: throw AssertionError("No se guardó: $result")

    private fun registered(result: RegisterSaleResult): Long =
        (result as? RegisterSaleResult.Registered)?.saleId
            ?: throw AssertionError("No se registró: $result")

    private companion object {
        /**
         * La región que confirma la persona: moneda de pruebas de dos decimales, sin redondeo, y
         * una zona tres horas al oeste de UTC.
         */
        val REGION = RegionalSettingsInput(
            currencyCode = requireNotNull(CurrencyCode.of("XTS")),
            currencyDecimals = 2,
            currencySymbol = "¤",
            cashRounding = Money.ZERO,
            formatLocaleTag = "pt-CV",
            businessTimeZoneId = "Etc/GMT+3",
            weekStartDay = DayOfWeek.MONDAY,
            csvSeparator = CsvSeparator.SEMICOLON,
        )

        fun date(text: String) = requireNotNull(BusinessDate.parse(text))

        val DEC_1 = date("2026-12-01")
        val DEC_15 = date("2026-12-15")
        val JAN_5 = date("2027-01-05")
        val JAN_8 = date("2027-01-08")
        val JAN_10 = date("2027-01-10")
        val JAN_20 = date("2027-01-20")
        val JAN_25 = date("2027-01-25")
        val JAN_31 = date("2027-01-31")
        val FEB_4 = date("2027-02-04")
        val FEB_7 = date("2027-02-07")
        val FEB_20 = date("2027-02-20")
        val FEB_28 = date("2027-02-28")
        val MAR_2 = date("2027-03-02")

        val DECEMBER = ReportPeriod.Month(YearMonth(2026, 12))
        val JANUARY = ReportPeriod.Month(YearMonth(2027, 1))

        /** El límite que escribe la persona: un monto y unos meses suyos, de ningún régimen. */
        val LIMIT = RevenueLimit(Money(2_500_000), 12)

        const val INDEX_NOTE = "Fuente inventada"

        /** Un PDF inventado: el paquete lo copia tal cual, sin leerlo. */
        val PDF_BYTES = "%PDF-1.4 inventado".toByteArray(Charsets.US_ASCII)

        /** Los archivos del paquete que no llevan costo, margen ni ganancia. */
        val COST_FREE_FILES = listOf(
            "sale_payments.csv",
            "expenses.csv",
            "closing_inventory.csv",
            "customers.csv",
            "suppliers.csv",
        )

        /** Nombres de archivo y encabezados por su clave, y las filas iniciales por su clave. */
        val NAMING = object : SummaryNaming {
            override fun fileName(table: CsvExportTable): String = "${table.key}.csv"

            override fun header(column: String): String = column

            override fun seedName(table: SeedTable, systemKey: String): String = "seed:$systemKey"

            override fun closingInventoryFileName(): String =
                "${SummaryFile.CLOSING_INVENTORY_KEY}.csv"
        }

        val BREAD =
            ProductForm(name = "Pan inventado", salePrice = Money(5_000), cost = Money(2_000))
        val COFFEE =
            ProductForm(name = "Café inventado", salePrice = Money(20_000), cost = Money(12_000))
        val JAM =
            ProductForm(
                name = "Mermelada inventada",
                salePrice = Money(10_000),
                cost = Money(8_000),
            )
        val CANDLE =
            ProductForm(
                name = "Vela quieta inventada",
                salePrice = Money(15_000),
                cost = Money(9_000),
            )
    }
}
