package io.github.yesidrobayo.shopledger.domain.testing

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.cash.CashCountPhase
import io.github.yesidrobayo.shopledger.domain.model.cash.CashDenomination
import io.github.yesidrobayo.shopledger.domain.model.cash.CashSession
import io.github.yesidrobayo.shopledger.domain.model.cash.DenominationCount
import io.github.yesidrobayo.shopledger.domain.model.catalog.CatalogLink
import io.github.yesidrobayo.shopledger.domain.model.catalog.Category
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.catalog.PriceChange
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductConversion
import io.github.yesidrobayo.shopledger.domain.model.catalog.RecipeComponent
import io.github.yesidrobayo.shopledger.domain.model.catalog.Supplier
import io.github.yesidrobayo.shopledger.domain.model.catalog.SystemRow
import io.github.yesidrobayo.shopledger.domain.model.customer.Customer
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerLedgerEntry
import io.github.yesidrobayo.shopledger.domain.model.expense.Expense
import io.github.yesidrobayo.shopledger.domain.model.expense.ExpenseCategory
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccount
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyMovement
import io.github.yesidrobayo.shopledger.domain.model.payable.SupplierLedgerEntry
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodRecord
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceList
import io.github.yesidrobayo.shopledger.domain.model.purchase.Purchase
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseLine
import io.github.yesidrobayo.shopledger.domain.model.sale.HeldSale
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxMode
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxSettings
import io.github.yesidrobayo.shopledger.domain.model.stock.Location
import io.github.yesidrobayo.shopledger.domain.model.stock.LowStockAlert
import io.github.yesidrobayo.shopledger.domain.model.stock.StockKey
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementRecord
import io.github.yesidrobayo.shopledger.domain.model.stock.StockTransfer
import io.github.yesidrobayo.shopledger.domain.model.stock.StockTransferLine
import io.github.yesidrobayo.shopledger.domain.model.tax.TaxRate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** El saldo, el costo promedio y el valor del stock que un producto guarda en su fila. */
internal data class ProductStockHeader(
    val onHand: QuantityMilli,
    val averageCost: Money,
    val stockValue: Money,
)

/**
 * Las tablas de [InMemoryDatabase], inmutables: cada escritura produce otra copia, así que deshacer
 * una transacción es volver a la copia de antes.
 *
 * Las tablas del catálogo guardan los productos sin su estado de stock, que vive en [productStock]
 * como lo deja el libro. [catalogLinks] y [productsWithOtherHistory] dicen qué usa a un producto
 * desde tablas que estas pruebas no modelan.
 *
 * [lastIds] guarda el último id dado por tabla, y se deshace con todo lo demás: una base con ids
 * que nunca se reutilizan tampoco consume ids en una transacción revertida. [changeSeq] cuenta los
 * cambios confirmados que aún no se respaldan.
 */
internal data class Tables(
    val purchases: Map<Long, Purchase> = emptyMap(),
    val purchaseLines: Map<Long, PurchaseLine> = emptyMap(),
    val movements: Map<Long, StockMovementRecord> = emptyMap(),
    val productStock: Map<Long, ProductStockHeader> = emptyMap(),
    val products: Map<Long, Product> = emptyMap(),
    val categories: Map<Long, Category> = emptyMap(),
    val suppliers: Map<Long, Supplier> = emptyMap(),
    val units: Map<Long, MeasureUnit> = emptyMap(),
    val systemRows: Map<SystemRow, Long> = emptyMap(),
    val priceChanges: Map<Long, PriceChange> = emptyMap(),
    val alerts: Map<Long, LowStockAlert> = emptyMap(),
    val catalogLinks: Map<Long, Set<CatalogLink>> = emptyMap(),
    val productsWithOtherHistory: Set<Long> = emptySet(),
    val paymentMethods: Map<Long, PaymentMethodRecord> = emptyMap(),
    val taxRates: Map<Long, TaxRate> = emptyMap(),
    val taxSettings: TaxSettings = TaxSettings(TaxMode.NONE, null, purchaseTaxRecoverable = false),
    /** La tasa de cada producto que tiene una: id del producto a id de la tasa. */
    val productTaxRateIds: Map<Long, Long> = emptyMap(),
    val mediaAssets: Map<Long, MediaAssetRow> = emptyMap(),
    val productUnits: Map<Long, ProductConversion> = emptyMap(),
    /** Las unidades que usa alguna línea de compra, de venta o de otro documento. */
    val unitsUsedByLines: Set<Long> = emptySet(),
    /** Las ventas en espera, con sus líneas. */
    val heldSales: Map<Long, HeldSale> = emptyMap(),
    val expenses: Map<Long, Expense> = emptyMap(),
    val expenseCategories: Map<Long, ExpenseCategory> = emptyMap(),
    val customers: Map<Long, Customer> = emptyMap(),
    /** Los clientes con ventas, pedidos, cotizaciones o filas en sus libros. */
    val customersWithHistory: Set<Long> = emptySet(),
    val customerLedger: Map<Long, CustomerLedgerEntry> = emptyMap(),
    val moneyAccounts: Map<Long, MoneyAccount> = emptyMap(),
    /** El libro de dinero, por id: solo crece. */
    val moneyMovements: Map<Long, MoneyMovement> = emptyMap(),
    /** El libro de los proveedores, por id: solo crece. */
    val supplierEntries: Map<Long, SupplierLedgerEntry> = emptyMap(),
    /** Las sesiones de caja, por id. */
    val cashSessions: Map<Long, CashSession> = emptyMap(),
    val cashDenominations: Map<Long, CashDenomination> = emptyMap(),
    /** Los conteos por denominación, por sesión y fase, en el orden en que se guardaron. */
    val cashCounts: Map<Pair<Long, CashCountPhase>, List<DenominationCount>> = emptyMap(),
    /** Las listas de precios, por id. */
    val priceLists: Map<Long, PriceList> = emptyMap(),
    /** Los precios propios, por lista y producto. */
    val productPrices: Map<Pair<Long, Long>, Money> = emptyMap(),
    /** Las listas que usa alguna venta, línea o venta en espera, tablas que estas pruebas no modelan. */
    val priceListsUsedBySales: Set<Long> = emptySet(),
    /** Las filas de las recetas de los combos, por id. */
    val productComponents: Map<Long, RecipeComponent> = emptyMap(),
    /** Las ubicaciones del stock, por id. */
    val locations: Map<Long, Location> = emptyMap(),
    /** Los saldos por producto, ubicación y lote, que el libro suma en cada fila. */
    val stockBalances: Map<StockKey, QuantityMilli> = emptyMap(),
    /** Las transferencias de stock y sus líneas, por id. */
    val stockTransfers: Map<Long, StockTransfer> = emptyMap(),
    val stockTransferLines: Map<Long, StockTransferLine> = emptyMap(),
    val lastIds: Map<String, Long> = emptyMap(),
    val changeSeq: Long = 0L,
)

/** Una falla inyectada a propósito en una escritura. */
internal class InjectedFailure : IllegalStateException()

/**
 * Una base en memoria con transacciones de todo o nada, para probar casos de uso sin `:data`.
 *
 * Fuera de una transacción solo se lee lo confirmado. Dentro, se lee y se escribe una copia de
 * trabajo que solo se publica al confirmar: un observador nunca ve una transacción a medias, como
 * con la base real. Escribir fuera de una transacción falla, porque así se saltaría el todo o nada.
 *
 * [failure] inyecta una falla: la escritura número [failAtWrite] de la transacción lanza lo que
 * devuelva, antes de escribir nada.
 */
internal class InMemoryDatabase {
    private val committed = MutableStateFlow(Tables())
    private var working: Tables? = null
    private var writesInTransaction = 0

    var failAtWrite: Int? = null
    var failure: () -> Throwable = { InjectedFailure() }

    /** Cuántas escrituras hizo la última transacción, confirmada o no. */
    var lastTransactionWrites: Int = 0
        private set

    val inTransaction: Boolean get() = working != null

    /** Lo confirmado, que es lo único que ven los observadores. */
    val tables: StateFlow<Tables> = committed

    /** Lo que lee un repositorio: la copia de trabajo dentro de una transacción. */
    fun read(): Tables = working ?: committed.value

    fun begin() {
        check(working == null) { "Una transacción dentro de otra" }
        working = committed.value
        writesInTransaction = 0
    }

    /**
     * Confirma la copia de trabajo. Con [counted], suma un cambio sin respaldar en la misma
     * transacción; sin él, la confirma tal cual, como lo que no es un hecho del negocio.
     */
    fun commit(counted: Boolean = true) {
        val tables = checkNotNull(working) { "No hay transacción que confirmar" }
        committed.value = if (counted) tables.copy(changeSeq = tables.changeSeq + 1) else tables
        end()
    }

    fun rollback() {
        end()
    }

    /** Aplica [change] a la copia de trabajo. Falla fuera de una transacción. */
    fun write(change: (Tables) -> Tables) {
        val tables = checkNotNull(working) { "Una escritura fuera de una transacción" }
        writesInTransaction++
        if (writesInTransaction == failAtWrite) throw failure()
        working = change(tables)
    }

    /**
     * Deja [change] confirmado sin transacción ni cambio contado: es el estado del que parte una
     * prueba, no una escritura de un caso de uso.
     */
    fun seed(change: (Tables) -> Tables) {
        check(working == null) { "Sembrar dentro de una transacción" }
        committed.value = change(committed.value)
    }

    /** Da el id siguiente de [table], como una clave que nunca se reutiliza. */
    fun nextId(table: String): Long {
        var id = 0L
        write { tables ->
            id = (tables.lastIds[table] ?: 0L) + 1L
            tables.copy(lastIds = tables.lastIds + (table to id))
        }
        return id
    }

    private fun end() {
        lastTransactionWrites = writesInTransaction
        working = null
    }
}
