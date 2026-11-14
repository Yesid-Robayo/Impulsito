package io.github.yesidrobayo.shopledger.perf

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.ShopLedgerApp
import io.github.yesidrobayo.shopledger.data.DataAccess
import io.github.yesidrobayo.shopledger.data.DataPorts
import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductForm
import io.github.yesidrobayo.shopledger.domain.model.expense.ExpenseRequest
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodKind
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseLineAmount
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseLineRequest
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleRequest
import io.github.yesidrobayo.shopledger.domain.model.settings.RegionalSettingsInput
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CreateCategoryUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CreateProductUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveCategoryResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveProductResult
import io.github.yesidrobayo.shopledger.domain.usecase.expense.RegisterExpenseResult
import io.github.yesidrobayo.shopledger.domain.usecase.expense.RegisterExpenseUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.RegisterPurchaseResult
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.RegisterPurchaseUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.domain.usecase.region.CurrencyLock
import io.github.yesidrobayo.shopledger.domain.usecase.region.SaveRegionalSettingsResult
import io.github.yesidrobayo.shopledger.domain.usecase.region.SaveRegionalSettingsUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleResult
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleUseCase
import io.github.yesidrobayo.shopledger.format.TEST_CURRENCY
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Llena la base de la app con un negocio inventado y grande, para medir la fluidez de las
 * pantallas con listas largas y meses de historia, como las tendría un negocio después de un
 * tiempo de uso.
 *
 * Escribe todo por los casos de uso, igual que la app: categorías, productos con precio, costo y
 * stock, compras, ventas de una a cuatro líneas y gastos. Las ventas, compras y gastos se reparten
 * a lo largo de [HISTORY_DAYS] días con un reloj que avanza, de modo que hoy, la semana y el mes
 * tienen datos. Los nombres se arman de listas de palabras inventadas, con algunos largos, y la
 * semilla del azar es fija: dos corridas dan la misma base.
 *
 * Escribe datos en la base de la app y fija su moneda, así que solo corre si se pide con el
 * argumento `volumeSeed=true` del runner de instrumentación. Las cantidades se cambian con
 * `volumeProducts`, `volumeSales`, `volumePurchases` y `volumeExpenses`. Registra solo conteos y
 * duraciones: ningún dato del negocio.
 */
@RunWith(AndroidJUnit4::class)
class VolumeSeedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as ShopLedgerApp
    private val arguments = InstrumentationRegistry.getArguments()
    private val random = Random(SEED)

    @Test
    fun fillsTheDatabaseWithAnInventedBusiness() = runBlocking<Unit> {
        assumeTrue(arguments.getString(ARGUMENT) == "true")
        val ports = (app.container.data.open() as DataAccess.Ready).ports
        val productCount = count("volumeProducts", PRODUCTS)
        val saleCount = count("volumeSales", SALES)
        val purchaseCount = count("volumePurchases", PURCHASES)
        val expenseCount = count("volumeExpenses", EXPENSES)
        // Un reloj que empieza en el pasado y avanza un paso por documento, hasta llegar a hoy.
        val end = app.container.clock.now()
        val documents = saleCount + purchaseCount + expenseCount
        val step = HISTORY_DAYS.days / documents.coerceAtLeast(1)
        val clock = SteppingClock(end - HISTORY_DAYS.days, step)
        val started = SystemClock.elapsedRealtime()

        confirmCurrency(ports, clock)
        val categoryIds = createCategories(ports, clock)
        val productIds = createProducts(ports, clock, categoryIds, productCount)
        Log.i(TAG, "productos: ${productIds.size} en ${elapsedSince(started)} ms")

        val sell = registerSale(ports, clock)
        val buy = registerPurchase(ports, clock)
        val spend = registerExpense(ports, clock)
        // Sin el método a crédito: una venta así pide quién compra.
        val methodIds = ports.paymentMethods.findActive()
            .filter { it.details.kind != PaymentMethodKind.ON_ACCOUNT }
            .sortedBy { it.sortOrder }
            .map { it.id }
        val expenseCategoryIds =
            ports.expenseCategories.observe(ArchiveFilter.ACTIVE).first().map { it.id }
        // Los documentos se intercalan: cada uno avanza el reloj, así que todos quedan en orden.
        val order = List(saleCount) { DocumentKind.SALE } +
            List(purchaseCount) { DocumentKind.PURCHASE } +
            List(expenseCount) { DocumentKind.EXPENSE }
        var sold = 0
        for (kind in order.shuffled(random)) {
            clock.step()
            when (kind) {
                DocumentKind.SALE -> {
                    val lines = List(random.nextInt(1, MAX_LINES + 1)) {
                        SaleLineRequest(
                            productIds.pickFrequent(),
                            QuantityMilli(ONE_UNIT * random.nextInt(1, MAX_UNITS + 1)),
                        )
                    }.distinctBy { it.productId }
                    val result = sell(SaleRequest(lines, methodIds.pickFrequent()))
                    check(result is RegisterSaleResult.Registered) { "Venta rechazada: $result" }
                    sold++
                    if (sold % LOG_EVERY == 0) {
                        Log.i(TAG, "ventas: $sold en ${elapsedSince(started)} ms")
                    }
                }

                DocumentKind.PURCHASE -> {
                    val lines = List(random.nextInt(1, MAX_LINES + 1)) {
                        PurchaseLineRequest(
                            productIds.random(random),
                            QuantityMilli(ONE_UNIT * PURCHASE_UNITS),
                            PurchaseLineAmount.UnitCost(
                                Money(BASE_COST + random.nextLong(PRICE_SPREAD)),
                            ),
                        )
                    }.distinctBy { it.productId }
                    val result = buy(PurchaseRequest(lines))
                    check(result is RegisterPurchaseResult.Registered) {
                        "Compra rechazada: $result"
                    }
                }

                DocumentKind.EXPENSE -> {
                    val amount = Money(EXPENSE_BASE + random.nextLong(EXPENSE_SPREAD))
                    val result = spend(ExpenseRequest(amount, expenseCategoryIds.random(random)))
                    check(result is RegisterExpenseResult.Registered) {
                        "Gasto rechazado: $result"
                    }
                }
            }
        }
        Log.i(
            TAG,
            "listo: ${productIds.size} productos, $saleCount ventas, $purchaseCount compras y " +
                "$expenseCount gastos en ${elapsedSince(started)} ms",
        )
    }

    private fun elapsedSince(started: Long): Long = SystemClock.elapsedRealtime() - started

    private fun count(name: String, default: Int): Int =
        arguments.getString(name)?.toIntOrNull() ?: default

    private suspend fun confirmCurrency(ports: DataPorts, clock: Clock) {
        val settings = ports.settings
        val regional = settings.get().regional
        if (!regional.currency.code.isConfirmed) {
            val confirmed = SaveRegionalSettingsUseCase(ports.transactions, settings, clock)(
                RegionalSettingsInput(
                    currencyCode = TEST_CURRENCY,
                    currencyDecimals = TEST_DECIMALS,
                    currencySymbol = null,
                    cashRounding = Money.ZERO,
                    formatLocaleTag = regional.formatLocaleTag,
                    businessTimeZoneId = regional.businessTimeZoneId,
                    weekStartDay = regional.weekStartDay,
                    csvSeparator = regional.csvSeparator,
                ),
            )
            assertEquals(SaveRegionalSettingsResult.Saved, confirmed)
        }
        if (settings.get().onboardingCompletedAt == null) {
            settings.markOnboardingCompleted(clock.now())
        }
    }

    private suspend fun createCategories(ports: DataPorts, clock: Clock): List<Long> {
        val create = CreateCategoryUseCase(ports.transactions, ports.categories, clock)
        return CATEGORY_NAMES.mapNotNull { name ->
            (create(name) as? SaveCategoryResult.Saved)?.categoryId
        }
    }

    private suspend fun createProducts(
        ports: DataPorts,
        clock: Clock,
        categoryIds: List<Long>,
        count: Int,
    ): List<Long> {
        val settings = ports.settings
        val create = CreateProductUseCase(
            ports.transactions, ports.products, ports.categories, ports.suppliers,
            ports.units, ports.systemRows, ports.priceHistory, ports.stockLedger,
            ports.lowStockAlerts, BusinessCalendarSource(settings), CurrencyLock(settings), clock,
            ports.mediaAssets,
        )
        val ids = mutableListOf<Long>()
        for (index in 0 until count) {
            val noun = NOUNS[index % NOUNS.size]
            val trait = TRAITS[(index / NOUNS.size) % TRAITS.size]
            // Uno de cada cincuenta lleva un nombre largo, como los que se cortan en una fila.
            val tail = if (index % LONG_NAME_EVERY == 0) " $LONG_TAIL" else ""
            val name = "$noun $trait ${index + 1}$tail"
            val price = BASE_PRICE + random.nextLong(PRICE_SPREAD)
            val form = ProductForm(
                name = name,
                categoryId = categoryIds.getOrNull(index % (categoryIds.size + 1)),
                salePrice = Money(price),
                cost = Money(price * COST_PERCENT / 100),
                minStock = QuantityMilli(ONE_UNIT * MIN_STOCK),
            )
            val result = create(form, QuantityMilli(ONE_UNIT * random.nextInt(0, MAX_STOCK)))
            if (result is SaveProductResult.Saved) ids += result.productId
        }
        check(ids.isNotEmpty()) { "No se creó ningún producto" }
        return ids
    }

    private fun registerSale(ports: DataPorts, clock: Clock) = RegisterSaleUseCase(
        transactions = ports.transactions,
        settings = ports.settings,
        products = ports.products,
        sales = ports.sales,
        payments = ports.salePayments,
        units = ports.units,
        systemRows = ports.systemRows,
        ledger = ports.stockLedger,
        methodNames = app.container.paymentMethodNames,
        taxRates = ports.taxRates,
        clock = clock,
        heldSales = ports.heldSales,
        productUnits = ports.productUnits,
    )

    private fun registerPurchase(ports: DataPorts, clock: Clock) = RegisterPurchaseUseCase(
        transactions = ports.transactions,
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
    )

    private fun registerExpense(ports: DataPorts, clock: Clock) = RegisterExpenseUseCase(
        ports.transactions,
        ports.settings,
        ports.expenses,
        ports.expenseCategories,
        clock,
    )

    /** Un elemento al azar, con más peso en los primeros: unos pocos se venden mucho. */
    private fun <T> List<T>.pickFrequent(): T {
        val roll = random.nextDouble()
        return this[(roll * roll * size).toInt().coerceIn(0, size - 1)]
    }

    private enum class DocumentKind { SALE, PURCHASE, EXPENSE }

    /** Un reloj que solo avanza cuando se le pide, para fechar la historia en el pasado. */
    private class SteppingClock(start: Instant, private val stepBy: Duration) : Clock {
        private var current = start

        fun step() {
            current += stepBy
        }

        override fun now(): Instant = current
    }

    private companion object {
        const val ARGUMENT = "volumeSeed"
        const val TAG = "VolumeSeed"
        const val SEED = 20_261_113
        const val PRODUCTS = 3_000
        const val SALES = 8_000
        const val PURCHASES = 300
        const val EXPENSES = 600
        const val HISTORY_DAYS = 120
        const val LOG_EVERY = 1_000
        const val MAX_LINES = 4
        const val MAX_UNITS = 3
        const val PURCHASE_UNITS = 24
        const val ONE_UNIT = 1_000L
        const val MIN_STOCK = 5
        const val MAX_STOCK = 400
        const val TEST_DECIMALS = 2
        const val BASE_PRICE = 500L
        const val PRICE_SPREAD = 50_000L
        const val BASE_COST = 300L
        const val COST_PERCENT = 62L
        const val EXPENSE_BASE = 2_000L
        const val EXPENSE_SPREAD = 200_000L
        const val LONG_NAME_EVERY = 50
        const val LONG_TAIL = "edición de temporada con empaque familiar y sabor intenso"

        // Palabras inventadas para armar un catálogo variado, sin marcas ni lugares.
        val CATEGORY_NAMES = listOf(
            "Abarrotes", "Bebidas", "Aseo", "Lácteos", "Panadería", "Dulces", "Papelería",
            "Ferretería", "Mascotas", "Hogar",
        )
        val NOUNS = listOf(
            "Arroz", "Café", "Galletas", "Harina", "Jabón", "Leche", "Lentejas", "Mantequilla",
            "Mermelada", "Panela", "Pasta", "Queso", "Salsa", "Té", "Vinagre", "Yogur", "Velas",
            "Cuaderno", "Lápiz", "Tornillos", "Cepillo", "Esponja", "Detergente", "Avena",
        )
        val TRAITS = listOf(
            "clásico", "integral", "familiar", "pequeño", "grande", "suave", "fuerte", "natural",
            "de la casa", "surtido", "económico", "premium", "mini", "doble", "ligero",
        )
    }
}
