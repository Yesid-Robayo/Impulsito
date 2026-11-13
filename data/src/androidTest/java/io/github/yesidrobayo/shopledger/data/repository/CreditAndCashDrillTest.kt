package io.github.yesidrobayo.shopledger.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.DataPorts
import io.github.yesidrobayo.shopledger.data.backup.InvariantQueries
import io.github.yesidrobayo.shopledger.data.local.catalog.CREATED_AT
import io.github.yesidrobayo.shopledger.data.local.catalog.CatalogDatabaseTest
import io.github.yesidrobayo.shopledger.data.transaction.RestoreLock
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.VoidReason
import io.github.yesidrobayo.shopledger.domain.model.cash.CashSessionStatus
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductForm
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerForm
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerLedgerEntryType
import io.github.yesidrobayo.shopledger.domain.model.expense.ExpenseRequest
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccountDraft
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccountKind
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyMovementType
import io.github.yesidrobayo.shopledger.domain.model.money.TransferRequest
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodDraft
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SalePaymentRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleRequest
import io.github.yesidrobayo.shopledger.domain.model.settings.CsvSeparator
import io.github.yesidrobayo.shopledger.domain.model.settings.CurrencyCode
import io.github.yesidrobayo.shopledger.domain.model.settings.LoyaltySettings
import io.github.yesidrobayo.shopledger.domain.model.settings.RegionalSettingsInput
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.PaymentMethodNames
import io.github.yesidrobayo.shopledger.domain.usecase.cash.CloseCashSessionResult
import io.github.yesidrobayo.shopledger.domain.usecase.cash.CloseCashSessionUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.cash.OpenCashSessionResult
import io.github.yesidrobayo.shopledger.domain.usecase.cash.OpenCashSessionUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CreateProductUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveProductResult
import io.github.yesidrobayo.shopledger.domain.usecase.customer.CreateCustomerUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.customer.CustomerPaymentRequest
import io.github.yesidrobayo.shopledger.domain.usecase.customer.RecordCustomerPaymentResult
import io.github.yesidrobayo.shopledger.domain.usecase.customer.RecordCustomerPaymentUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.customer.SaveCustomerResult
import io.github.yesidrobayo.shopledger.domain.usecase.customer.SaveLoyaltyProgramResult
import io.github.yesidrobayo.shopledger.domain.usecase.customer.SaveLoyaltyProgramUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.customer.VoidCustomerPaymentResult
import io.github.yesidrobayo.shopledger.domain.usecase.customer.VoidCustomerPaymentUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.expense.RegisterExpenseResult
import io.github.yesidrobayo.shopledger.domain.usecase.expense.RegisterExpenseUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.expense.VoidExpenseResult
import io.github.yesidrobayo.shopledger.domain.usecase.expense.VoidExpenseUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.money.DocumentMoney
import io.github.yesidrobayo.shopledger.domain.usecase.money.MoneyLedgerWriter
import io.github.yesidrobayo.shopledger.domain.usecase.money.SaveMoneyAccountResult
import io.github.yesidrobayo.shopledger.domain.usecase.money.SaveMoneyAccountUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.money.TransferMoneyResult
import io.github.yesidrobayo.shopledger.domain.usecase.money.TransferMoneyUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.money.VoidMoneyMovementResult
import io.github.yesidrobayo.shopledger.domain.usecase.money.VoidMoneyMovementUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.payment.EditPaymentMethodUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.payment.SavePaymentMethodResult
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.domain.usecase.region.CurrencyLock
import io.github.yesidrobayo.shopledger.domain.usecase.region.SaveRegionalSettingsResult
import io.github.yesidrobayo.shopledger.domain.usecase.region.SaveRegionalSettingsUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleResult
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.sale.VoidSaleResult
import io.github.yesidrobayo.shopledger.domain.usecase.sale.VoidSaleUseCase
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DayOfWeek
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Simulacro de clientes y dinero sobre una base nueva, por el camino de la app: los puertos de la
 * base abierta y los casos de uso armados como los arman sus pantallas. Un día de un negocio
 * inventado, con caja: abrir, vender a crédito y en efectivo, recibir un abono en la caja, pagar
 * un gasto desde ella, pasar dinero al banco y cerrar con una diferencia. En el mismo día se
 * registra y se anula una cosa de cada tipo: una venta a crédito, una venta en efectivo, un abono,
 * un gasto y una transferencia.
 *
 * Los saldos del cliente, de sus puntos, de cada cuenta y de la caja se comparan con valores
 * calculados a mano, fila por fila, y cada consulta de invariantes del modelo de datos, también las
 * de los libros del cliente, del proveedor, del dinero y de los puntos, devuelve cero filas.
 *
 * El negocio, el cliente, los productos y los montos son inventados. La moneda es `XTS`, el código
 * de pruebas de ISO 4217, con dos decimales: los montos van en su unidad menor.
 */
@RunWith(AndroidJUnit4::class)
internal class CreditAndCashDrillTest : CatalogDatabaseTest() {
    // La base nace sin moneda, como en un teléfono nuevo, y la persona la confirma en su pantalla.
    override val confirmsCurrency: Boolean = false

    /** Un reloj que avanza un segundo en cada lectura, todo dentro del mismo día del negocio. */
    private val ticks = AtomicLong(CREATED_AT)
    private val clock = Clock { Instant.fromEpochMilliseconds(ticks.addAndGet(1_000L)) }

    @Test
    fun aDayWithCreditCashAndVoidsMatchesHandWrittenBalances() = runBlocking {
        val ports = DataPorts(database, file, Dispatchers.IO, clock, RestoreLock { false })
        val tx = ports.transactions
        val calendar = BusinessCalendarSource(ports.settings)
        val writer = MoneyLedgerWriter(ports.moneyAccounts, ports.moneyLedger)

        // Configuración, como en sus pantallas: la moneda, los puntos, una cuenta de banco, el
        // efectivo que entra en la caja inicial, dos productos y un cliente con solo su nombre.
        assertEquals(
            SaveRegionalSettingsResult.Saved,
            SaveRegionalSettingsUseCase(tx, ports.settings, clock)(REGION),
        )
        assertEquals(
            SaveLoyaltyProgramResult.Saved,
            SaveLoyaltyProgramUseCase(tx, ports.settings, clock)(
                LoyaltySettings(
                    enabled = true,
                    amountPerPoint = Money(1_000),
                    pointValue = Money(10),
                ),
            ),
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
        val onAccount = count("SELECT id FROM payment_methods WHERE system_key = 'ON_ACCOUNT'")
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
            clock,
        )
        val soap = savedProduct(createProduct(SOAP, QuantityMilli(10_000)))
        val candle = savedProduct(createProduct(CANDLE, QuantityMilli(10_000)))
        val customer = (
            CreateCustomerUseCase(tx, ports.customers, clock)(CustomerForm("Cliente inventado"))
                as SaveCustomerResult.Saved
            ).customerId
        val otherExpenses = count("SELECT id FROM expense_categories WHERE system_key = 'OTHER'")

        // Los casos de uso de cada pantalla, con los mismos puertos que les da la app.
        val openCash = OpenCashSessionUseCase(
            tx,
            ports.settings,
            ports.moneyAccounts,
            ports.moneyLedger,
            ports.cashSessions,
            clock,
            ports.cashDenominations,
        )
        val closeCash = CloseCashSessionUseCase(
            tx,
            ports.settings,
            ports.moneyAccounts,
            ports.moneyLedger,
            ports.cashSessions,
            clock,
            ports.cashDenominations,
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
        )
        val voidSale = VoidSaleUseCase(
            transactions = tx,
            settings = ports.settings,
            products = ports.products,
            sales = ports.sales,
            ledger = ports.stockLedger,
            clock = clock,
            alerts = ports.lowStockAlerts,
            customerLedger = ports.customerLedger,
            loyaltyLedger = ports.loyaltyLedger,
            payments = ports.salePayments,
            moneyAccounts = ports.moneyAccounts,
            moneyLedger = ports.moneyLedger,
        )
        val recordPayment = RecordCustomerPaymentUseCase(
            transactions = tx,
            settings = ports.settings,
            customers = ports.customers,
            ledger = ports.customerLedger,
            accounts = ports.moneyAccounts,
            money = writer,
            clock = clock,
        )
        val voidPayment = VoidCustomerPaymentUseCase(
            transactions = tx,
            settings = ports.settings,
            ledger = ports.customerLedger,
            movements = ports.moneyLedger,
            money = writer,
            clock = clock,
        )
        val registerExpense = RegisterExpenseUseCase(
            transactions = tx,
            settings = ports.settings,
            expenses = ports.expenses,
            categories = ports.expenseCategories,
            clock = clock,
            money = DocumentMoney(ports.moneyAccounts, ports.moneyLedger),
        )
        val voidExpense = VoidExpenseUseCase(
            transactions = tx,
            expenses = ports.expenses,
            clock = clock,
            settings = ports.settings,
            money = DocumentMoney(ports.moneyAccounts, ports.moneyLedger),
        )
        val transfer = TransferMoneyUseCase(tx, ports.settings, ports.moneyAccounts, writer, clock)
        val voidMovement =
            VoidMoneyMovementUseCase(tx, ports.settings, ports.moneyLedger, writer, clock)

        // 1. Abrir caja: la caja inicial está en cero y la persona cuenta 10.000 de cambio.
        val opened = openCash(drawer, Money(10_000)) as OpenCashSessionResult.Opened
        val session = opened.sessionId

        // 2. Venta a crédito: tres jabones, 10.500, todo a cuenta del cliente. Gana 10 puntos.
        val creditSale = registered(
            registerSale(
                SaleRequest(
                    lines = listOf(SaleLineRequest(soap, QuantityMilli(3_000))),
                    paymentMethodId = onAccount,
                    payments = listOf(SalePaymentRequest(onAccount, Money(10_500))),
                    customerId = customer,
                ),
            ),
        )
        // 3. Venta en efectivo: dos jabones, 7.000, que entran a la caja.
        val cashSale = registered(
            registerSale(
                SaleRequest(listOf(SaleLineRequest(soap, QuantityMilli(2_000))), cashMethod),
            ),
        )
        // 4. Abono de 4.000 que entra a la caja.
        val payment = recordPayment(
            CustomerPaymentRequest(customer, Money(4_000), drawer, date = null, note = null),
        ) as RecordCustomerPaymentResult.Recorded
        assertEquals(Money(6_500), payment.balanceAfter)
        // 5. Gasto de 1.500 pagado desde la caja.
        val expense = expenseId(
            registerExpense(
                ExpenseRequest(Money(1_500), otherExpenses, "Bolsas", moneyAccountId = drawer),
            ),
        )
        // 6. Transferencia de 5.000 de la caja al banco.
        val moved = transfer(TransferRequest(drawer, bank, Money(5_000)))
        assertTrue(moved is TransferMoneyResult.Transferred)

        // 7 a 11. Una de cada tipo registrada por error y anulada.
        val wrongCredit = registered(
            registerSale(
                SaleRequest(
                    lines = listOf(SaleLineRequest(candle, QuantityMilli(1_000))),
                    paymentMethodId = onAccount,
                    payments = listOf(SalePaymentRequest(onAccount, Money(2_000))),
                    customerId = customer,
                ),
            ),
        )
        assertEquals(VoidSaleResult.Voided, voidSale(wrongCredit, VoidReason.ENTRY_ERROR, null))
        val wrongCash = registered(
            registerSale(
                SaleRequest(listOf(SaleLineRequest(soap, QuantityMilli(1_000))), cashMethod),
            ),
        )
        assertEquals(VoidSaleResult.Voided, voidSale(wrongCash, VoidReason.ENTRY_ERROR, null))
        val wrongPayment = recordPayment(
            CustomerPaymentRequest(customer, Money(1_000), bank, date = null, note = null),
        ) as RecordCustomerPaymentResult.Recorded
        assertTrue(voidPayment(customer, wrongPayment.entryId) is VoidCustomerPaymentResult.Voided)
        val wrongExpense = expenseId(
            registerExpense(
                ExpenseRequest(Money(800), otherExpenses, "Cinta", moneyAccountId = drawer),
            ),
        )
        assertEquals(
            VoidExpenseResult.Voided,
            voidExpense(wrongExpense, VoidReason.ENTRY_ERROR, null),
        )
        val wrongTransfer =
            transfer(TransferRequest(bank, drawer, Money(2_000))) as TransferMoneyResult.Transferred
        assertTrue(voidMovement(wrongTransfer.outId) is VoidMoneyMovementResult.Voided)

        // 12. Cerrar caja: se esperan 14.500 y la persona cuenta 14.300.
        val closed =
            closeCash(session, Money(14_300), "Faltan 200") as CloseCashSessionResult.Closed
        val closing = closed.closing

        // La caja, a mano: abre con 10.000, y en la sesión entran 7.000 de la venta y 4.000 del
        // abono, salen 1.500 del gasto y 5.000 al banco, y lo anulado suma cero. Se esperan 14.500.
        assertEquals(Money(14_500), closing.expected)
        assertEquals(Money(14_300), closing.counted)
        assertEquals(Money(-200), closing.difference)
        val stored = requireNotNull(ports.cashSessions.findById(session))
        assertEquals(CashSessionStatus.CLOSED, stored.status)
        assertEquals(Money(10_000), stored.opening.openingAmount)
        assertEquals(null, ports.cashSessions.findOpen(drawer))

        // La cuenta de la caja, fila por fila, con su saldo después de cada una.
        val drawerRows = ports.moneyLedger.findByAccount(drawer)
        assertEquals(
            listOf(
                MoneyMovementType.CASH_DIFFERENCE to 10_000L,
                MoneyMovementType.SALE_RECEIPT to 7_000L,
                MoneyMovementType.CUSTOMER_PAYMENT to 4_000L,
                MoneyMovementType.EXPENSE_PAYMENT to -1_500L,
                MoneyMovementType.TRANSFER_OUT to -5_000L,
                MoneyMovementType.SALE_RECEIPT to 3_500L,
                MoneyMovementType.MOVEMENT_VOID to -3_500L,
                MoneyMovementType.EXPENSE_PAYMENT to -800L,
                MoneyMovementType.MOVEMENT_VOID to 800L,
                MoneyMovementType.TRANSFER_IN to 2_000L,
                MoneyMovementType.MOVEMENT_VOID to -2_000L,
                MoneyMovementType.CASH_DIFFERENCE to -200L,
            ),
            drawerRows.map { it.type to it.amount.minor },
        )
        assertEquals(
            listOf(
                10_000L, 17_000L, 21_000L, 19_500L, 14_500L, 18_000L,
                14_500L, 13_700L, 14_500L, 16_500L, 14_500L, 14_300L,
            ),
            drawerRows.map { it.balanceAfter.minor },
        )
        // Todo lo de la caja cayó en la sesión, también las anulaciones y las dos diferencias.
        assertTrue(drawerRows.all { it.draft.cashSessionId == session })
        assertEquals(Money(14_300), requireNotNull(ports.moneyAccounts.findById(drawer)).balance)

        // El banco: 50.000 iniciales, más 5.000 de la caja; el abono y la transferencia anulados
        // suman cero. Quedan 55.000, sin sesión de caja.
        val bankRows = ports.moneyLedger.findByAccount(bank)
        assertEquals(
            listOf(
                MoneyMovementType.OPENING_BALANCE to 50_000L,
                MoneyMovementType.TRANSFER_IN to 5_000L,
                MoneyMovementType.CUSTOMER_PAYMENT to 1_000L,
                MoneyMovementType.MOVEMENT_VOID to -1_000L,
                MoneyMovementType.TRANSFER_OUT to -2_000L,
                MoneyMovementType.MOVEMENT_VOID to 2_000L,
            ),
            bankRows.map { it.type to it.amount.minor },
        )
        assertEquals(
            listOf(50_000L, 55_000L, 56_000L, 55_000L, 53_000L, 55_000L),
            bankRows.map { it.balanceAfter.minor },
        )
        assertTrue(bankRows.all { it.draft.cashSessionId == null })
        assertEquals(Money(55_000), requireNotNull(ports.moneyAccounts.findById(bank)).balance)

        // El cliente: debe 10.500, abona 4.000, y la venta y el abono anulados se compensan con
        // su ENTRY_VOID. Debe 6.500.
        val entries = ports.customerLedger.findByCustomer(customer)
        assertEquals(
            listOf(
                CustomerLedgerEntryType.CREDIT_SALE to 10_500L,
                CustomerLedgerEntryType.PAYMENT to -4_000L,
                CustomerLedgerEntryType.CREDIT_SALE to 2_000L,
                CustomerLedgerEntryType.ENTRY_VOID to -2_000L,
                CustomerLedgerEntryType.PAYMENT to -1_000L,
                CustomerLedgerEntryType.ENTRY_VOID to 1_000L,
            ),
            entries.map { it.type to it.amount.minor },
        )
        assertEquals(
            listOf(10_500L, 6_500L, 8_500L, 6_500L, 5_500L, 6_500L),
            entries.map { it.balanceAfter.minor },
        )
        val after = requireNotNull(ports.customers.findById(customer))
        assertEquals(Money(6_500), after.balance)
        // Los puntos: 10 por la venta de 10.500 y 2 por la anulada, que se revierten. Nunca son
        // dinero: ninguna cuenta ni el saldo del cliente los cuentan.
        assertEquals(10L, after.pointsBalance)
        assertEquals(
            listOf(10L, 2L, -2L),
            listOf(0, 1, 2).map {
                count("SELECT points FROM loyalty_ledger_entries ORDER BY id LIMIT 1 OFFSET $it")
            },
        )
        assertEquals(3L, count("SELECT COUNT(*) FROM loyalty_ledger_entries"))

        // Los documentos: dos ventas vigentes y dos anuladas, un gasto vigente y uno anulado.
        assertEquals(
            listOf(false, false, true, true),
            listOf(creditSale, cashSale, wrongCredit, wrongCash).map { isVoided("sales", it) },
        )
        assertEquals(
            listOf(false, true),
            listOf(expense, wrongExpense).map { isVoided("expenses", it) },
        )
        // El stock: diez jabones menos 3, 2 y 1, más el que vuelve al anular. Las velas vuelven a
        // ser 10.
        assertEquals(QuantityMilli(5_000), requireNotNull(ports.products.findById(soap)).onHand)
        assertEquals(QuantityMilli(10_000), requireNotNull(ports.products.findById(candle)).onHand)

        // Cada consulta de invariantes da cero filas. Las de los libros de este hito están todas.
        val labels = InvariantQueries.MODEL.map { it.label }
        for (invariant in listOf("I-14", "I-15", "I-16", "I-17", "I-18", "I-20")) {
            assertTrue(invariant, labels.any { it == invariant || it.startsWith("$invariant,") })
        }
        for (query in InvariantQueries.MODEL) {
            val rows = query.sql.trimEnd().removeSuffix(";")
            assertEquals(query.label, 0L, count("SELECT COUNT(*) FROM ($rows)"))
        }
    }

    private fun savedProduct(result: SaveProductResult): Long =
        (result as SaveProductResult.Saved).productId

    private fun registered(result: RegisterSaleResult): Long =
        (result as RegisterSaleResult.Registered).saleId

    private fun expenseId(result: RegisterExpenseResult): Long =
        (result as RegisterExpenseResult.Registered).expenseId

    /** Si el documento [id] de [table] quedó anulado, con su estado y su instante de anulación. */
    private suspend fun isVoided(table: String, id: Long): Boolean = count(
        "SELECT COUNT(*) FROM $table " +
            "WHERE id = $id AND status = 'VOIDED' AND voided_at IS NOT NULL",
    ) == 1L

    private companion object {
        /** La región que confirma la persona: moneda de pruebas con dos decimales, sin redondeo. */
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

        val SOAP =
            ProductForm(name = "Jabón inventado", salePrice = Money(3_500), cost = Money(1_200))
        val CANDLE =
            ProductForm(name = "Vela inventada", salePrice = Money(2_000), cost = Money(900))
    }
}
