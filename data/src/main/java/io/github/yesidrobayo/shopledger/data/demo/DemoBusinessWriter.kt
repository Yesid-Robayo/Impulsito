package io.github.yesidrobayo.shopledger.data.demo

import io.github.yesidrobayo.shopledger.data.DataPorts
import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductForm
import io.github.yesidrobayo.shopledger.domain.model.catalog.SupplierForm
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerForm
import io.github.yesidrobayo.shopledger.domain.model.expense.ExpenseRequest
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodKind
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseLineAmount
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseLineRequest
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleRequest
import io.github.yesidrobayo.shopledger.domain.model.settings.RegionalSettingsInput
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CreateProductUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CreateSupplierUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveProductResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveSupplierResult
import io.github.yesidrobayo.shopledger.domain.usecase.customer.CreateCustomerUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.customer.SaveCustomerResult
import io.github.yesidrobayo.shopledger.domain.usecase.expense.RegisterExpenseResult
import io.github.yesidrobayo.shopledger.domain.usecase.expense.RegisterExpenseUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.money.DocumentMoney
import io.github.yesidrobayo.shopledger.domain.usecase.onboarding.CompleteOnboardingResult
import io.github.yesidrobayo.shopledger.domain.usecase.onboarding.CompleteOnboardingUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.RegisterPurchaseResult
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.RegisterPurchaseUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.domain.usecase.region.CurrencyLock
import io.github.yesidrobayo.shopledger.domain.usecase.region.SaveRegionalSettingsResult
import io.github.yesidrobayo.shopledger.domain.usecase.region.SaveRegionalSettingsUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleResult
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.settings.BusinessProfileChange
import io.github.yesidrobayo.shopledger.domain.usecase.settings.SaveBusinessProfileResult
import io.github.yesidrobayo.shopledger.domain.usecase.settings.SaveBusinessProfileUseCase
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.flow.first

/**
 * Escribe el negocio de ejemplo en una base de ejemplo nueva, por el mismo camino que la app: los
 * casos de uso de sus pantallas, con las guardas de la base activas. Así los libros, el costo y el
 * stock quedan como los dejaría la persona, y cada pantalla tiene algo que mostrar.
 *
 * - **La región es la del negocio real:** la moneda, sus decimales, el formato, la zona y la
 *   semana llegan en [region], copiadas de la base real sin escribir en ella. Los nombres llegan
 *   en [texts], en el idioma de la interfaz.
 * - **Los montos son los mismos en toda moneda:** cada uno es una cantidad entera de la moneda,
 *   llevada a su unidad menor con sus decimales. Ninguna cifra depende de un país.
 * - **Una semana de historia** que termina hoy: el catálogo con su stock inicial, una compra,
 *   ventas cada día, una con cliente, un gasto, y un producto que queda bajo su mínimo.
 *
 * Cada paso es su propia transacción. Si alguno falla, quien llama descarta la base de ejemplo
 * entera: nunca queda una demostración a medias.
 *
 * @param ports los puertos de la base de ejemplo, nunca los de la base real.
 * @param now el instante en que termina la historia, el de ahora.
 */
internal class DemoBusinessWriter(
    private val ports: DataPorts,
    private val texts: DemoTexts,
    private val region: RegionalSettingsInput,
    now: Instant,
) {
    private val clock = StepClock(now)
    private val today = now
    private val scale: Long = (1..region.currencyDecimals).fold(1L) { power, _ ->
        Math.multiplyExact(power, DECIMAL_BASE)
    }

    private val tx get() = ports.transactions

    /** Escribe el negocio entero. Lanza si algún paso no queda escrito. */
    suspend fun write() {
        clock.at = today - HISTORY
        val saved = SaveRegionalSettingsUseCase(tx, ports.settings, clock)(region)
        check(saved == SaveRegionalSettingsResult.Saved) { "No se guardó la región de ejemplo" }
        val completed = CompleteOnboardingUseCase(tx, ports.settings, clock)()
        check(completed == CompleteOnboardingResult.Completed) { "No terminó el primer uso" }
        val named = SaveBusinessProfileUseCase(tx, ports.settings, clock)(
            BusinessProfileChange.Name(texts.businessName),
        )
        check(named == SaveBusinessProfileResult.Saved) { "No se guardó el nombre de ejemplo" }
        val products = createProducts()
        val customer = createCustomer()
        val supplier = createSupplier()
        clock.at = today - PURCHASE_AGO
        purchase(supplier, products)
        val cash = cashMethod()
        for (sale in SALES) {
            clock.at = if (sale.daysAgo == 0) today - TODAY_START else today - sale.daysAgo.days
            sell(sale, products, cash, customer)
        }
        clock.at = today - EXPENSE_AGO
        expense()
    }

    private suspend fun createProducts(): List<Long> {
        val create = CreateProductUseCase(
            tx, ports.products, ports.categories, ports.suppliers, ports.units,
            ports.systemRows, ports.priceHistory, ports.stockLedger, ports.lowStockAlerts,
            BusinessCalendarSource(ports.settings), CurrencyLock(ports.settings), clock,
            ports.mediaAssets,
        )
        return PRODUCTS.mapIndexed { index, product ->
            val form = ProductForm(
                name = texts.products[index],
                salePrice = amount(product.price),
                cost = amount(product.cost),
                minStock = units(product.minStock),
            )
            val result = create(form, units(product.stock))
            (result as? SaveProductResult.Saved)?.productId
                ?: error("No se guardó un producto de ejemplo")
        }
    }

    private suspend fun createCustomer(): Long {
        val create = CreateCustomerUseCase(tx, ports.customers, clock)
        val result = create(CustomerForm(texts.customer))
        return (result as? SaveCustomerResult.Saved)?.customerId
            ?: error("No se guardó el cliente de ejemplo")
    }

    private suspend fun createSupplier(): Long {
        val create = CreateSupplierUseCase(tx, ports.suppliers, clock)
        val result = create(SupplierForm(texts.supplier))
        return (result as? SaveSupplierResult.Saved)?.supplierId
            ?: error("No se guardó el proveedor de ejemplo")
    }

    private suspend fun purchase(supplier: Long, products: List<Long>) {
        val register = RegisterPurchaseUseCase(
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
        val lines = PURCHASE.map { (index, quantity) ->
            val cost = PRODUCTS[index].cost
            PurchaseLineRequest(
                products[index],
                units(quantity),
                PurchaseLineAmount.UnitCost(amount(cost)),
            )
        }
        val result = register(PurchaseRequest(lines, supplierId = supplier))
        check(result is RegisterPurchaseResult.Registered) {
            "No se registró la compra de ejemplo"
        }
    }

    private suspend fun cashMethod(): Long =
        ports.paymentMethods.findActive().firstOrNull { it.details.kind == PaymentMethodKind.CASH }
            ?.id ?: error("Falta el método de efectivo")

    private suspend fun sell(sale: DemoSale, products: List<Long>, cash: Long, customer: Long) {
        val register = RegisterSaleUseCase(
            transactions = tx,
            settings = ports.settings,
            products = ports.products,
            sales = ports.sales,
            payments = ports.salePayments,
            units = ports.units,
            systemRows = ports.systemRows,
            ledger = ports.stockLedger,
            methodNames = texts.paymentMethodNames,
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
        val lines = sale.lines.map { (index, quantity) ->
            SaleLineRequest(products[index], units(quantity))
        }
        val request = SaleRequest(
            lines = lines,
            paymentMethodId = cash,
            customerId = if (sale.withCustomer) customer else null,
        )
        val result = register(request)
        check(result is RegisterSaleResult.Registered) { "No se registró una venta de ejemplo" }
    }

    private suspend fun expense() {
        val categories = ports.expenseCategories.observe(ArchiveFilter.ACTIVE).first()
        val category = categories.firstOrNull { it.systemKey == EXPENSE_CATEGORY_KEY }
            ?: categories.firstOrNull()
            ?: error("Falta una categoría de gasto")
        val register = RegisterExpenseUseCase(
            transactions = tx,
            settings = ports.settings,
            expenses = ports.expenses,
            categories = ports.expenseCategories,
            clock = clock,
            money = DocumentMoney(ports.moneyAccounts, ports.moneyLedger),
            recurring = ports.recurringExpenses,
        )
        val result = register(ExpenseRequest(amount(EXPENSE_AMOUNT), category.id, texts.expense))
        check(result is RegisterExpenseResult.Registered) { "No se registró el gasto de ejemplo" }
    }

    /** [whole] unidades enteras de la moneda, en su unidad menor. */
    private fun amount(whole: Long): Money = Money(Math.multiplyExact(whole, scale))

    /** [whole] unidades enteras de producto, en milésimas. */
    private fun units(whole: Long): QuantityMilli = QuantityMilli(Math.multiplyExact(whole, MILLI))

    /**
     * El reloj de la historia: está donde lo deja cada paso, y avanza un segundo en cada lectura,
     * para que dos registros seguidos nunca compartan instante.
     */
    private class StepClock(var at: Instant) : Clock {
        override fun now(): Instant = at.also { at += 1.seconds }
    }

    /** Un producto del catálogo de ejemplo, en unidades enteras de la moneda y del producto. */
    private class DemoProduct(val price: Long, val cost: Long, val stock: Long, val minStock: Long)

    /**
     * Una venta de ejemplo: hace cuántos días, qué lleva por índice de producto y si con cliente.
     */
    private class DemoSale(
        val daysAgo: Int,
        val lines: List<Pair<Int, Long>>,
        val withCustomer: Boolean = false,
    )

    private companion object {
        const val DECIMAL_BASE = 10L
        const val MILLI = 1_000L
        const val EXPENSE_AMOUNT = 15L
        const val EXPENSE_CATEGORY_KEY = "UTILITIES"

        /** Cuánto antes de hoy empieza la historia: el catálogo y su stock inicial. */
        val HISTORY: Duration = 8.days
        val PURCHASE_AGO: Duration = 5.days
        val EXPENSE_AGO: Duration = 3.days

        /** Las ventas de hoy empiezan un poco antes de ahora, para nunca quedar en el futuro. */
        val TODAY_START: Duration = 30.minutes

        /**
         * El arroz, el aceite, los huevos, el café, el queso y el jabón, en el orden de los
         * nombres. El queso nace por debajo de su mínimo, y aparece entre los que se agotan.
         */
        private val PRODUCTS = listOf(
            DemoProduct(price = 4, cost = 3, stock = 40, minStock = 5),
            DemoProduct(price = 6, cost = 4, stock = 30, minStock = 5),
            DemoProduct(price = 12, cost = 8, stock = 15, minStock = 3),
            DemoProduct(price = 9, cost = 6, stock = 20, minStock = 4),
            DemoProduct(price = 7, cost = 5, stock = 4, minStock = 6),
            DemoProduct(price = 3, cost = 2, stock = 50, minStock = 10),
        )

        /** La compra: producto por índice y unidades, al costo de su ficha. */
        private val PURCHASE = listOf(1 to 12L, 5 to 24L)

        private val SALES = listOf(
            DemoSale(6, listOf(0 to 2L, 3 to 1L)),
            DemoSale(6, listOf(5 to 3L)),
            DemoSale(5, listOf(1 to 2L, 2 to 1L)),
            DemoSale(4, listOf(3 to 2L), withCustomer = true),
            DemoSale(4, listOf(0 to 1L, 5 to 2L)),
            DemoSale(3, listOf(2 to 1L, 1 to 1L)),
            DemoSale(2, listOf(4 to 1L)),
            DemoSale(2, listOf(0 to 3L, 5 to 4L)),
            DemoSale(1, listOf(3 to 1L, 1 to 2L)),
            DemoSale(1, listOf(2 to 2L, 0 to 2L), withCustomer = true),
            DemoSale(0, listOf(5 to 5L, 0 to 1L)),
            DemoSale(0, listOf(1 to 1L, 3 to 1L)),
        )
    }
}
