package io.github.yesidrobayo.shopledger.domain.usecase.sale

import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.DocumentStatus
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.Voiding
import io.github.yesidrobayo.shopledger.domain.model.catalog.CatalogLink
import io.github.yesidrobayo.shopledger.domain.model.catalog.ConversionDetails
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.catalog.NewProduct
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductConversion
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductDetails
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.catalog.RecipeComponent
import io.github.yesidrobayo.shopledger.domain.model.catalog.Supplier
import io.github.yesidrobayo.shopledger.domain.model.catalog.SupplierDetails
import io.github.yesidrobayo.shopledger.domain.model.catalog.SystemRow
import io.github.yesidrobayo.shopledger.domain.model.catalog.UnitKind
import io.github.yesidrobayo.shopledger.domain.model.customer.Customer
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerContact
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerDetails
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerEntrySource
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerLedgerEntry
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerLedgerEntryType
import io.github.yesidrobayo.shopledger.domain.model.customer.LoyaltyEntrySource
import io.github.yesidrobayo.shopledger.domain.model.customer.LoyaltyEntryType
import io.github.yesidrobayo.shopledger.domain.model.customer.LoyaltyLedgerEntry
import io.github.yesidrobayo.shopledger.domain.model.customer.NewCustomerLedgerEntry
import io.github.yesidrobayo.shopledger.domain.model.customer.NewLoyaltyLedgerEntry
import io.github.yesidrobayo.shopledger.domain.model.customer.PointsSummary
import io.github.yesidrobayo.shopledger.domain.model.money.AccountCurrency
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccount
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccountDetails
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccountKind
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyDocument
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyMovement
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyMovementType
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyOrigin
import io.github.yesidrobayo.shopledger.domain.model.money.NewMoneyAccount
import io.github.yesidrobayo.shopledger.domain.model.money.NewMoneyMovement
import io.github.yesidrobayo.shopledger.domain.model.payable.NewSupplierLedgerEntry
import io.github.yesidrobayo.shopledger.domain.model.payable.SupplierLedgerEntry
import io.github.yesidrobayo.shopledger.domain.model.payable.SupplierLedgerEntryType
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodKind
import io.github.yesidrobayo.shopledger.domain.model.purchase.NewPurchase
import io.github.yesidrobayo.shopledger.domain.model.purchase.NewPurchaseLine
import io.github.yesidrobayo.shopledger.domain.model.purchase.Purchase
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseLine
import io.github.yesidrobayo.shopledger.domain.model.purchase.SupplierLastCost
import io.github.yesidrobayo.shopledger.domain.model.returns.LineAmounts
import io.github.yesidrobayo.shopledger.domain.model.returns.NewPurchaseReturn
import io.github.yesidrobayo.shopledger.domain.model.returns.NewPurchaseReturnLine
import io.github.yesidrobayo.shopledger.domain.model.returns.NewSaleReturn
import io.github.yesidrobayo.shopledger.domain.model.returns.NewSaleReturnLine
import io.github.yesidrobayo.shopledger.domain.model.returns.PurchaseReturn
import io.github.yesidrobayo.shopledger.domain.model.returns.PurchaseReturnLine
import io.github.yesidrobayo.shopledger.domain.model.returns.PurchaseReturnResolution
import io.github.yesidrobayo.shopledger.domain.model.returns.SaleReturn
import io.github.yesidrobayo.shopledger.domain.model.returns.SaleReturnLine
import io.github.yesidrobayo.shopledger.domain.model.sale.HeldSale
import io.github.yesidrobayo.shopledger.domain.model.sale.LineStockEffect
import io.github.yesidrobayo.shopledger.domain.model.sale.NewHeldSale
import io.github.yesidrobayo.shopledger.domain.model.sale.NewSale
import io.github.yesidrobayo.shopledger.domain.model.sale.NewSaleLine
import io.github.yesidrobayo.shopledger.domain.model.sale.NewSaleLineComponent
import io.github.yesidrobayo.shopledger.domain.model.sale.NewSalePayment
import io.github.yesidrobayo.shopledger.domain.model.sale.Sale
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLine
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineComponent
import io.github.yesidrobayo.shopledger.domain.model.sale.SalePayment
import io.github.yesidrobayo.shopledger.domain.model.sale.SalePaymentMethod
import io.github.yesidrobayo.shopledger.domain.model.settings.BusinessCurrency
import io.github.yesidrobayo.shopledger.domain.model.settings.CurrencyCode
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxMode
import io.github.yesidrobayo.shopledger.domain.model.stock.LotStock
import io.github.yesidrobayo.shopledger.domain.model.stock.LowStockAlert
import io.github.yesidrobayo.shopledger.domain.model.stock.LowStockNotificationStatus
import io.github.yesidrobayo.shopledger.domain.model.stock.NewLowStockAlert
import io.github.yesidrobayo.shopledger.domain.model.stock.NewStockLot
import io.github.yesidrobayo.shopledger.domain.model.stock.NewStockMovement
import io.github.yesidrobayo.shopledger.domain.model.stock.StockKey
import io.github.yesidrobayo.shopledger.domain.model.stock.StockLot
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementRecord
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType
import io.github.yesidrobayo.shopledger.domain.model.tax.TaxRate
import io.github.yesidrobayo.shopledger.domain.model.tax.TaxRateDetails
import io.github.yesidrobayo.shopledger.domain.port.CustomerLedgerRepository
import io.github.yesidrobayo.shopledger.domain.port.CustomerRepository
import io.github.yesidrobayo.shopledger.domain.port.HeldSaleRepository
import io.github.yesidrobayo.shopledger.domain.port.LocationRepository
import io.github.yesidrobayo.shopledger.domain.port.LowStockAlertRepository
import io.github.yesidrobayo.shopledger.domain.port.LoyaltyLedgerRepository
import io.github.yesidrobayo.shopledger.domain.port.MoneyAccountRepository
import io.github.yesidrobayo.shopledger.domain.port.MoneyLedgerRepository
import io.github.yesidrobayo.shopledger.domain.port.PaymentMethodNames
import io.github.yesidrobayo.shopledger.domain.port.PriceListRepository
import io.github.yesidrobayo.shopledger.domain.port.ProductComponentRepository
import io.github.yesidrobayo.shopledger.domain.port.ProductPriceRepository
import io.github.yesidrobayo.shopledger.domain.port.ProductRepository
import io.github.yesidrobayo.shopledger.domain.port.ProductUnitRepository
import io.github.yesidrobayo.shopledger.domain.port.PurchaseRepository
import io.github.yesidrobayo.shopledger.domain.port.PurchaseReturnRepository
import io.github.yesidrobayo.shopledger.domain.port.SaleLineComponentRepository
import io.github.yesidrobayo.shopledger.domain.port.SalePaymentRepository
import io.github.yesidrobayo.shopledger.domain.port.SaleRepository
import io.github.yesidrobayo.shopledger.domain.port.SaleReturnRepository
import io.github.yesidrobayo.shopledger.domain.port.StockLedgerRepository
import io.github.yesidrobayo.shopledger.domain.port.StockLotRepository
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException
import io.github.yesidrobayo.shopledger.domain.port.SupplierLedgerRepository
import io.github.yesidrobayo.shopledger.domain.port.SupplierRepository
import io.github.yesidrobayo.shopledger.domain.port.SystemRowRepository
import io.github.yesidrobayo.shopledger.domain.port.TaxRateRepository
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.port.UnitRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakeAppSettingsRepository
import io.github.yesidrobayo.shopledger.domain.testing.FixedClock
import io.github.yesidrobayo.shopledger.domain.testing.appSettings
import io.github.yesidrobayo.shopledger.domain.testing.lastCostsOf
import io.github.yesidrobayo.shopledger.domain.testing.regionalSettings
import io.github.yesidrobayo.shopledger.domain.usecase.money.DocumentMoney
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.RegisterPurchaseUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.VoidPurchaseUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.returns.ExchangeSaleUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.returns.RegisterSaleReturnUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.returns.RegisterSupplierReturnUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.returns.VoidSaleReturnUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.returns.VoidSupplierReturnUseCase
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.datetime.DayOfWeek

/**
 * Las tablas que tocan una venta y su anulación, inmutables: cada escritura produce otra copia, así
 * que deshacer una transacción es volver a la copia de antes, también con los ids.
 */
internal data class SaleTables(
    val products: Map<Long, Product> = emptyMap(),
    val sales: Map<Long, Sale> = emptyMap(),
    val lines: Map<Long, SaleLine> = emptyMap(),
    val payments: Map<Long, SalePayment> = emptyMap(),
    val movements: Map<Long, StockMovementRecord> = emptyMap(),
    val methods: Map<Long, SalePaymentMethod> = emptyMap(),
    val salesWithActiveReturns: Set<Long> = emptySet(),
    val heldSales: Map<Long, HeldSale> = emptyMap(),
    val purchases: Map<Long, Purchase> = emptyMap(),
    val purchaseLines: Map<Long, PurchaseLine> = emptyMap(),
    val suppliers: Map<Long, Supplier> = emptyMap(),
    val supplierEntries: Map<Long, SupplierLedgerEntry> = emptyMap(),
    val customers: Map<Long, Customer> = emptyMap(),
    val customerEntries: Map<Long, CustomerLedgerEntry> = emptyMap(),
    val loyaltyEntries: Map<Long, LoyaltyLedgerEntry> = emptyMap(),
    val lastIds: Map<String, Long> = emptyMap(),
    val changeSeq: Long = 0L,
    val alerts: Map<Long, LowStockAlert> = emptyMap(),
    val moneyAccounts: Map<Long, MoneyAccount> = emptyMap(),
    val moneyMovements: Map<Long, MoneyMovement> = emptyMap(),
    val openCashSessions: Map<Long, Long> = emptyMap(),
    val saleReturns: Map<Long, SaleReturn> = emptyMap(),
    val saleReturnLines: Map<Long, SaleReturnLine> = emptyMap(),
    val purchaseReturns: Map<Long, PurchaseReturn> = emptyMap(),
    val purchaseReturnLines: Map<Long, PurchaseReturnLine> = emptyMap(),
    /** Las filas de las recetas de los combos, por id. */
    val recipes: Map<Long, RecipeComponent> = emptyMap(),
    /** Las hojas guardadas de las líneas de combo, por id: solo crecen. */
    val lineComponents: Map<Long, SaleLineComponent> = emptyMap(),
    /** Los lotes de los productos que se controlan por lotes, por id. */
    val lots: Map<Long, StockLot> = emptyMap(),
)

/** Una falla inyectada a propósito en una escritura. */
internal class InjectedSaleFailure : IllegalStateException()

/**
 * Una base en memoria con transacciones de todo o nada para las pruebas de ventas, con todos los
 * puertos que usan sus casos de uso. Hace lo que el contrato pide a la base real: con `Commit`
 * confirma y cuenta un cambio, con `Rollback` o una excepción deshace todo, ids incluidos.
 *
 * [failAtWrite] hace fallar la escritura número N de la transacción, antes de escribir nada. Los
 * repositorios repiten lo que comprueban las guardas de la base que importa aquí: la fila del libro
 * debe dejar el saldo que el producto tenía más su cantidad, una fila se compensa una sola vez y
 * solo se anula una venta activa.
 */
internal class SaleWorld(
    currencyConfirmed: Boolean = true,
    currencyLocked: Boolean = true,
    zoneId: String = "Etc/GMT+5",
    clockMillis: Long = NOW_MILLIS,
) {
    private val committed = MutableStateFlow(SaleTables())
    private var working: SaleTables? = null
    private var writesInTransaction = 0

    var failAtWrite: Int? = null
    var failure: () -> Throwable = { InjectedSaleFailure() }
    var writeCalls: Int = 0
        private set
    var commits: Int = 0
        private set
    var rollbacks: Int = 0
        private set

    /** Si los casos de uso que mueven stock evalúan las alertas de stock bajo. */
    var withAlerts: Boolean = false

    /** Si la venta lee las recetas y guarda las hojas de los combos. Sin eso, los rechaza. */
    var withCombos: Boolean = false

    val tables: SaleTables get() = committed.value

    val settings = FakeAppSettingsRepository(
        appSettings(
            regionalSettings(zoneId, DayOfWeek.MONDAY).let { regional ->
                regional.copy(
                    currency = BusinessCurrency(
                        code = if (currencyConfirmed) {
                            requireNotNull(CurrencyCode.of("XTS"))
                        } else {
                            CurrencyCode.UNCONFIRMED
                        },
                        decimals = 0,
                        symbol = null,
                        lockedAt = if (currencyConfirmed && currencyLocked) EPOCH else null,
                    ),
                )
            },
        ),
    )

    var clock: FixedClock = FixedClock.atEpochMillis(clockMillis)

    /** Los nombres de los métodos iniciales: la clave en minúsculas, como si fuera un idioma. */
    val methodNames = PaymentMethodNames { key -> "name of ${key.lowercase()}" }

    val transactions: TransactionRunner = object : TransactionRunner {
        override suspend fun <T> write(block: suspend () -> TxResult<T>): T {
            writeCalls++
            check(working == null) { "Una transacción dentro de otra" }
            working = committed.value
            writesInTransaction = 0
            val result = try {
                block()
            } catch (failure: Throwable) {
                working = null
                rollbacks++
                throw failure
            }
            val tables = checkNotNull(working)
            working = null
            return when (result) {
                is TxResult.Commit -> {
                    committed.value = tables.copy(changeSeq = tables.changeSeq + 1)
                    commits++
                    result.value
                }

                is TxResult.CommitUncounted -> {
                    committed.value = tables
                    commits++
                    result.value
                }

                is TxResult.Rollback -> {
                    rollbacks++
                    result.value
                }
            }
        }

        override suspend fun <T> read(block: suspend () -> T): T = block()
    }

    /** Lo que lee un repositorio: la copia de trabajo dentro de una transacción. */
    fun read(): SaleTables = working ?: committed.value

    private fun write(change: (SaleTables) -> SaleTables) {
        val tables = checkNotNull(working) { "Una escritura fuera de una transacción" }
        writesInTransaction++
        if (writesInTransaction == failAtWrite) throw failure()
        working = change(tables)
    }

    private fun nextId(table: String): Long {
        var id = 0L
        write { tables ->
            id = (tables.lastIds[table] ?: 0L) + 1L
            tables.copy(lastIds = tables.lastIds + (table to id))
        }
        return id
    }

    /** Cambia lo confirmado sin transacción: prepara el escenario de una prueba. */
    fun arrange(change: (SaleTables) -> SaleTables) {
        check(working == null)
        committed.value = change(committed.value)
    }

    val products: ProductRepository = object : ProductRepository {
        override fun observe(filter: ArchiveFilter): Flow<List<Product>> = unused()

        override fun observeById(id: Long): Flow<Product?> = committed.map { it.products[id] }

        override suspend fun findById(id: Long): Product? = read().products[id]

        override suspend fun findByIds(ids: Collection<Long>): List<Product> =
            ids.mapNotNull { read().products[it] }

        override suspend fun findActiveByNormalizedName(normalizedName: String): Product? = unused()

        override suspend fun findActiveByBarcode(barcode: String): Product? = unused()

        override suspend fun hasHistory(id: Long): Boolean = unused()

        // Solo el vínculo de variantes, que la venta revisa: los demás no la cambian.
        override suspend fun catalogLinks(id: Long): Set<CatalogLink> =
            if (read().products.values.any { it.details.parentProductId == id }) {
                setOf(CatalogLink.VARIANT_PARENT)
            } else {
                emptySet()
            }

        override suspend fun insert(product: NewProduct): Long = unused()

        override suspend fun updateDetails(id: Long, details: ProductDetails, updatedAt: Instant) =
            unused()

        override suspend fun updateSalePrice(id: Long, salePrice: Money, updatedAt: Instant) =
            unused()

        override suspend fun updateInitialCost(id: Long, initialCost: Money, updatedAt: Instant) =
            unused()

        override suspend fun updateReplacementCost(
            id: Long,
            replacementCost: Money,
            replacementCostAt: Instant?,
            updatedAt: Instant,
        ) {
            val product = read().products[id] ?: brokenRule()
            write {
                it.copy(
                    products = it.products + (
                        id to product.copy(
                            replacementCost = replacementCost,
                            replacementCostAt = replacementCostAt,
                            updatedAt = updatedAt,
                        )
                        ),
                )
            }
        }

        override suspend fun setPriceReviewSince(id: Long, since: Instant?, updatedAt: Instant) {
            val product = read().products[id] ?: brokenRule()
            write {
                it.copy(
                    products = it.products + (
                        id to product.copy(priceReviewSince = since, updatedAt = updatedAt)
                        ),
                )
            }
        }

        override suspend fun setArchivedAt(id: Long, archivedAt: Instant?, updatedAt: Instant) =
            unused()

        override suspend fun delete(id: Long) = unused()
    }

    val sales: SaleRepository = object : SaleRepository {
        override fun observeIn(period: ReportPeriod): Flow<List<Sale>> = unused()

        override fun observeById(id: Long): Flow<Sale?> = committed.map { it.sales[id] }

        override fun observeLines(saleId: Long): Flow<List<SaleLine>> =
            committed.map { tables -> linesOf(tables, saleId) }

        override suspend fun findById(id: Long): Sale? = read().sales[id]

        override suspend fun findLines(saleId: Long): List<SaleLine> = linesOf(read(), saleId)

        override suspend fun insert(sale: NewSale): Long {
            val id = nextId("sales")
            write { it.copy(sales = it.sales + (id to Sale(id, sale, voiding = null))) }
            return id
        }

        override suspend fun insertLine(saleId: Long, line: NewSaleLine): Long {
            val id = nextId("sale_lines")
            write { it.copy(lines = it.lines + (id to SaleLine(id, saleId, line))) }
            return id
        }

        override suspend fun markVoided(id: Long, voiding: Voiding) {
            val sale = read().sales[id]
            if (sale == null || sale.status != DocumentStatus.ACTIVE) brokenRule()
            // Como la guarda: una venta con una devolución activa no se anula.
            if (activeReturnsOf(read(), id).isNotEmpty()) brokenRule()
            write { it.copy(sales = it.sales + (id to sale.copy(voiding = voiding))) }
        }

        override suspend fun hasActiveReturns(saleId: Long): Boolean =
            saleId in read().salesWithActiveReturns || activeReturnsOf(read(), saleId).isNotEmpty()
    }

    /**
     * Las devoluciones de venta, con las reglas de sus guardas: nacen de una venta activa con la
     * cabecera cuadrada, y por línea lo devuelto en devoluciones activas no pasa de lo vendido.
     */
    val saleReturns: SaleReturnRepository = object : SaleReturnRepository {
        override fun observeBySale(saleId: Long): Flow<List<SaleReturn>> = committed.map { tables ->
            tables.saleReturns.values.filter { it.content.saleId == saleId }.sortedBy { it.id }
        }

        override fun observeTakenBySale(saleId: Long): Flow<Map<Long, LineAmounts>> =
            committed.map { tables -> takenOf(tables, saleId) }

        override suspend fun findById(id: Long): SaleReturn? = read().saleReturns[id]

        override suspend fun findLines(saleReturnId: Long): List<SaleReturnLine> =
            read().saleReturnLines.values.filter { it.saleReturnId == saleReturnId }
                .sortedBy { it.id }

        override suspend fun takenBySale(saleId: Long): Map<Long, LineAmounts> =
            takenOf(read(), saleId)

        override suspend fun insert(saleReturn: NewSaleReturn): Long {
            val sale = read().sales[saleReturn.saleId]
            if (sale == null || sale.status != DocumentStatus.ACTIVE) brokenRule()
            if (saleReturn.totalRefund != saleReturn.netRefund + saleReturn.taxRefund) brokenRule()
            val id = nextId("sale_returns")
            write {
                it.copy(saleReturns = it.saleReturns + (id to SaleReturn(id, saleReturn, null)))
            }
            return id
        }

        override suspend fun insertLine(saleReturnId: Long, line: NewSaleReturnLine): Long {
            val tables = read()
            val saleReturn = tables.saleReturns[saleReturnId] ?: brokenRule()
            val sold = tables.lines[line.saleLineId] ?: brokenRule()
            val already = takenOf(tables, saleReturn.content.saleId)[sold.id]?.quantity
                ?: QuantityMilli.ZERO
            val fits = line.quantity.milli > 0L &&
                already + line.quantity <= sold.content.quantity &&
                line.lineTotal == line.netAmount + line.taxAmount &&
                listOf(line.netAmount, line.taxAmount, line.lineCost).all { it >= Money.ZERO }
            if (!fits) brokenRule()
            val id = nextId("sale_return_lines")
            write {
                it.copy(
                    saleReturnLines = it.saleReturnLines +
                        (id to SaleReturnLine(id, saleReturnId, line)),
                )
            }
            return id
        }

        override suspend fun markVoided(id: Long, voiding: Voiding) {
            val found = read().saleReturns[id]
            if (found == null || found.status != DocumentStatus.ACTIVE) brokenRule()
            write { it.copy(saleReturns = it.saleReturns + (id to found.copy(voiding = voiding))) }
        }
    }

    private fun activeReturnsOf(tables: SaleTables, saleId: Long): List<SaleReturn> =
        tables.saleReturns.values.filter {
            it.content.saleId == saleId && it.status == DocumentStatus.ACTIVE
        }

    /** Lo devuelto por las devoluciones activas de cada línea de la venta. */
    private fun takenOf(tables: SaleTables, saleId: Long): Map<Long, LineAmounts> {
        val active = activeReturnsOf(tables, saleId).map { it.id }.toSet()
        return tables.saleReturnLines.values
            .filter { it.saleReturnId in active }
            .groupBy { it.content.saleLineId }
            .mapValues { (_, lines) ->
                lines.fold(LineAmounts.NONE) { sum, line ->
                    LineAmounts(
                        sum.quantity + line.content.quantity,
                        sum.net + line.content.netAmount,
                        sum.tax + line.content.taxAmount,
                        sum.cost + line.content.lineCost,
                    )
                }
            }
    }

    val payments: SalePaymentRepository = object : SalePaymentRepository {
        override suspend fun findMethod(id: Long): SalePaymentMethod? = read().methods[id]

        override fun observeBySale(saleId: Long): Flow<List<SalePayment>> =
            committed.map { tables -> paymentsOf(tables, saleId) }

        override suspend fun findBySale(saleId: Long): List<SalePayment> =
            paymentsOf(read(), saleId)

        override suspend fun insert(saleId: Long, payment: NewSalePayment): Long {
            val id = nextId("sale_payments")
            write { it.copy(payments = it.payments + (id to SalePayment(id, saleId, payment))) }
            return id
        }
    }

    /** Las dos unidades de las pruebas: una que se cuenta entera y una por peso. */
    var units: Map<Long, MeasureUnit> = mapOf(
        UNIT to measureUnit(UNIT, "UNIT", UnitKind.COUNT, allowsFraction = false),
        KILOGRAM to measureUnit(KILOGRAM, "KILOGRAM", UnitKind.MASS, allowsFraction = true),
    )

    /** Las conversiones de los productos: la venta las lee por producto y unidad. */
    var conversions: List<ProductConversion> = emptyList()

    val productUnitRepository: ProductUnitRepository = object : ProductUnitRepository {
        override fun observeAll(): Flow<List<ProductConversion>> = unused()

        override fun observeByProduct(productId: Long): Flow<List<ProductConversion>> = unused()

        override suspend fun findById(id: Long): ProductConversion? = unused()

        override suspend fun findByProductAndUnit(productId: Long, unitId: Long) =
            conversions.firstOrNull { it.productId == productId && it.details.unitId == unitId }

        override suspend fun findByBarcode(barcode: String): ProductConversion? = unused()

        override suspend fun insert(
            productId: Long,
            details: ConversionDetails,
            createdAt: Instant,
        ) = unused()

        override suspend fun update(id: Long, details: ConversionDetails, updatedAt: Instant) =
            unused()

        override suspend fun delete(id: Long) = unused()
    }

    /** Las filas iniciales que la venta busca por su clave. */
    var systemRows: Map<SystemRow, Long> = mapOf(
        SystemRow.DEFAULT_UNIT to UNIT,
        SystemRow.MAIN_LOCATION to MAIN_LOCATION,
        SystemRow.DEFAULT_PRICE_LIST to DEFAULT_PRICE_LIST,
    )

    val unitRepository: UnitRepository = object : UnitRepository {
        override fun observeActive(): Flow<List<MeasureUnit>> = unused()

        override fun observeAll(): Flow<List<MeasureUnit>> = flowOf(units.values.toList())

        override suspend fun findById(id: Long): MeasureUnit? = units[id]
    }

    val systemRowRepository: SystemRowRepository = object : SystemRowRepository {
        override suspend fun findId(row: SystemRow): Long? = systemRows[row]
    }

    /** Las tasas de impuesto que escribió la persona. La base nace sin ninguna. */
    var taxRates: Map<Long, TaxRate> = emptyMap()

    /** Cuántas veces se leyó una tasa por su id. */
    var taxRateReads: Int = 0
        private set

    val taxRateRepository: TaxRateRepository = object : TaxRateRepository {
        override fun observe(filter: ArchiveFilter): Flow<List<TaxRate>> = unused()

        override fun observeProductCounts(): Flow<Map<Long, Int>> = unused()

        override suspend fun findById(id: Long): TaxRate? {
            taxRateReads++
            return taxRates[id]
        }

        override suspend fun findActiveByNormalizedName(normalizedName: String): TaxRate? = unused()

        override suspend fun countProducts(id: Long): Int = unused()

        override suspend fun insert(details: TaxRateDetails, createdAt: Instant): Long = unused()

        override suspend fun update(id: Long, details: TaxRateDetails, updatedAt: Instant) =
            unused()

        override suspend fun setArchivedAt(id: Long, archivedAt: Instant?, updatedAt: Instant) =
            unused()

        override suspend fun delete(id: Long) = unused()
    }

    /** Cambia cómo trata el negocio sus impuestos, como si la persona lo hubiera elegido. */
    fun setTaxes(mode: TaxMode, defaultTaxRateId: Long? = null) {
        val current = settings.settings.value
        settings.settings.value = current.copy(
            taxes = current.taxes.copy(mode = mode, defaultTaxRateId = defaultTaxRateId),
        )
    }

    /** Las alertas de stock bajo, con la regla de la base: a lo sumo una activa por producto. */
    val alertRepository: LowStockAlertRepository = object : LowStockAlertRepository {
        override fun observeActive(): Flow<List<LowStockAlert>> = committed.map { tables ->
            tables.alerts.values.filter { it.isActive }.sortedBy { it.id }
        }

        override suspend fun findActiveByProducts(
            productIds: Collection<Long>,
        ): List<LowStockAlert> =
            read().alerts.values.filter { it.isActive && it.productId in productIds }

        override suspend fun findPendingNotifications(): List<LowStockAlert> = read().alerts.values
            .filter { it.notificationStatus == LowStockNotificationStatus.PENDING }
            .sortedBy { it.id }

        override suspend fun insert(alert: NewLowStockAlert): Long {
            if (findActiveByProducts(listOf(alert.productId)).isNotEmpty()) brokenRule()
            val id = nextId("low_stock_alerts")
            val stored = LowStockAlert(
                id = id,
                productId = alert.productId,
                onHandAtTrigger = alert.onHandAtTrigger,
                minStockAtTrigger = alert.minStockAtTrigger,
                notificationStatus = alert.notificationStatus,
                occurredAt = alert.occurredAt,
                businessDate = alert.businessDate,
                resolvedAt = null,
            )
            write { it.copy(alerts = it.alerts + (id to stored)) }
            return id
        }

        override suspend fun resolve(id: Long, resolvedAt: Instant) = write { tables ->
            val alert = checkNotNull(tables.alerts[id]) { "No existe la alerta" }
            tables.copy(alerts = tables.alerts + (id to alert.copy(resolvedAt = resolvedAt)))
        }

        override suspend fun setNotificationStatus(id: Long, status: LowStockNotificationStatus) =
            unused()
    }

    val ledger: StockLedgerRepository = object : StockLedgerRepository {
        override fun observeByProduct(productId: Long): Flow<List<StockMovementRecord>> =
            committed.map { tables ->
                movementsOf(tables) { it.movement.key.productId == productId }
            }

        override suspend fun findById(id: Long): StockMovementRecord? = read().movements[id]

        override suspend fun findByProduct(productId: Long): List<StockMovementRecord> =
            movementsOf(read()) { it.movement.key.productId == productId }

        override suspend fun findBySale(saleId: Long): List<StockMovementRecord> {
            val tables = read()
            val lineIds = linesOf(tables, saleId).map { it.id }.toSet()
            return movementsOf(tables) { it.source.saleLineId in lineIds }
        }

        override suspend fun findByPurchase(purchaseId: Long): List<StockMovementRecord> {
            val tables = read()
            val lineIds = purchaseLinesOf(tables, purchaseId).map { it.id }.toSet()
            return movementsOf(tables) { it.source.purchaseLineId in lineIds }
        }

        override suspend fun findByPurchaseReturn(
            purchaseReturnId: Long,
        ): List<StockMovementRecord> {
            val tables = read()
            val lineIds = purchaseReturnLinesOf(tables, purchaseReturnId).map { it.id }.toSet()
            return movementsOf(tables) { it.source.purchaseReturnLineId in lineIds }
        }

        override suspend fun findBySaleReturn(saleReturnId: Long): List<StockMovementRecord> {
            val tables = read()
            val lineIds = tables.saleReturnLines.values
                .filter { it.saleReturnId == saleReturnId }
                .map { it.id }
                .toSet()
            return movementsOf(tables) { it.source.saleReturnLineId in lineIds }
        }

        override suspend fun findByProductionBatch(
            productionBatchId: Long,
        ): List<StockMovementRecord> =
            movementsOf(read()) { it.source.productionBatchId == productionBatchId }

        override suspend fun isCompensated(movementId: Long): Boolean =
            read().movements.values.any { it.movement.reversesMovementId == movementId }

        override suspend fun onHandAt(key: StockKey): QuantityMilli = read().movements.values
            .filter { it.movement.key == key }
            .fold(QuantityMilli.ZERO) { sum, row -> sum + row.movement.quantity }

        override suspend fun unassignedLot(productId: Long, createdAt: Instant): Long {
            read().lots.values.firstOrNull { it.productId == productId && it.isUnassigned }
                ?.let { return it.id }
            val id = nextId("stock_lots")
            val lot = StockLot(id, productId, null, true, null, null, null, createdAt, null)
            write { it.copy(lots = it.lots + (id to lot)) }
            return id
        }

        override suspend fun append(movement: NewStockMovement): Long {
            val draft = movement.movement
            val product = read().products[draft.key.productId] ?: brokenRule()
            if (product.onHand + draft.quantity != movement.balanceAfter) brokenRule()
            val reverses = draft.reversesMovementId
            if (reverses != null && isCompensated(reverses)) brokenRule()
            val id = nextId("stock_movements")
            write { it.copy(movements = it.movements + (id to movement.recordedAs(id))) }
            write { tables ->
                val updated = product.copy(
                    onHand = movement.balanceAfter,
                    averageCost = movement.averageCostAfter,
                    stockValue = movement.stockValueAfter,
                    updatedAt = movement.createdAt,
                )
                tables.copy(products = tables.products + (product.id to updated))
            }
            return id
        }
    }

    /** Los lotes con código, con su saldo por ubicación sumado desde el libro. */
    val lotRepository: StockLotRepository = object : StockLotRepository {
        override fun observeByProduct(productId: Long): Flow<List<StockLot>> =
            committed.map { tables -> lotsOf(tables, productId) }

        override fun observeAll(): Flow<List<StockLot>> =
            committed.map { tables -> tables.lots.values.sortedBy { it.id } }

        override suspend fun findById(id: Long): StockLot? = read().lots[id]

        override suspend fun findByCode(productId: Long, code: String): StockLot? =
            lotsOf(read(), productId).firstOrNull { it.code == code }

        override suspend fun findStock(productId: Long, locationId: Long): List<LotStock> {
            val tables = read()
            return lotsOf(tables, productId).map { lot ->
                val onHand = ledger.onHandAt(StockKey(productId, locationId, lot.id))
                LotStock(lot.id, lot.code, lot.expiryDate, lot.isUnassigned, onHand)
            }
        }

        override suspend fun insert(lot: NewStockLot): Long {
            if (findByCode(lot.productId, lot.code) != null) brokenRule()
            val id = nextId("stock_lots")
            val row = StockLot(
                id, lot.productId, lot.code, false, lot.expiryDate, lot.purchaseLineId, null,
                lot.createdAt, null,
            )
            write { it.copy(lots = it.lots + (id to row)) }
            return id
        }

        override suspend fun attachPurchaseLine(lotId: Long, purchaseLineId: Long) {
            val lot = read().lots[lotId] ?: brokenRule()
            val attached = lot.copy(purchaseLineId = purchaseLineId)
            write { it.copy(lots = it.lots + (lotId to attached)) }
        }

        private fun lotsOf(tables: SaleTables, productId: Long): List<StockLot> =
            tables.lots.values.filter { it.productId == productId }.sortedBy { it.id }
    }

    /** Las ventas en espera: solo se borran al confirmar la venta que se retomó de ellas. */
    val heldSaleRepository: HeldSaleRepository = object : HeldSaleRepository {
        override fun observeAll(): Flow<List<HeldSale>> = unused()

        override suspend fun findById(id: Long): HeldSale? = read().heldSales[id]

        override suspend fun insert(sale: NewHeldSale): Long = unused()

        override suspend fun replace(id: Long, sale: NewHeldSale): Boolean = unused()

        override suspend fun rename(id: Long, label: String?, updatedAt: Instant): Boolean =
            unused()

        override suspend fun delete(id: Long): Boolean {
            if (id !in read().heldSales) return false
            write { it.copy(heldSales = it.heldSales - id) }
            return true
        }
    }

    /** Las compras y sus líneas: una compra solo cambia al anularse, una sola vez. */
    val purchases: PurchaseRepository = object : PurchaseRepository {
        override fun observeIn(period: ReportPeriod): Flow<List<Purchase>> = unused()

        override fun observeById(id: Long): Flow<Purchase?> = committed.map { it.purchases[id] }

        override fun observeLines(purchaseId: Long): Flow<List<PurchaseLine>> =
            committed.map { tables -> purchaseLinesOf(tables, purchaseId) }

        override fun observeLastCosts(productId: Long): Flow<List<SupplierLastCost>> =
            committed.map { tables ->
                lastCostsOf(tables.purchases, tables.purchaseLines, productId).map { cost ->
                    cost.copy(
                        supplierName = cost.supplierId?.let {
                            tables.suppliers[it]?.details?.name
                        },
                    )
                }
            }

        override suspend fun findById(id: Long): Purchase? = read().purchases[id]

        override suspend fun findLines(purchaseId: Long): List<PurchaseLine> =
            purchaseLinesOf(read(), purchaseId)

        override suspend fun insert(purchase: NewPurchase): Long {
            // Lo que exige la guarda de la base: la moneda bloqueada, el total cuadrado, lo pagado
            // dentro del total y proveedor si queda debiendo.
            if (settings.settings.value.regional.currency.lockedAt == null) brokenRule()
            if (purchase.total != purchase.netTotal + purchase.taxTotal) brokenRule()
            if (purchase.amountPaid < Money.ZERO ||
                purchase.amountPaid > purchase.total
            ) {
                brokenRule()
            }
            if (purchase.amountPaid != purchase.total && purchase.supplierId == null) brokenRule()
            val id = nextId("purchases")
            write { it.copy(purchases = it.purchases + (id to Purchase(id, purchase, null))) }
            return id
        }

        override suspend fun insertLine(purchaseId: Long, line: NewPurchaseLine): Long {
            val id = nextId("purchase_lines")
            write {
                it.copy(
                    purchaseLines =
                        it.purchaseLines + (id to PurchaseLine(id, purchaseId, line)),
                )
            }
            return id
        }

        override suspend fun markVoided(id: Long, voiding: Voiding) {
            val purchase = read().purchases[id]
            if (purchase == null || purchase.status != DocumentStatus.ACTIVE) brokenRule()
            write { it.copy(purchases = it.purchases + (id to purchase.copy(voiding = voiding))) }
        }

        override suspend fun hasActiveReturns(purchaseId: Long): Boolean =
            read().purchaseReturns.values.any {
                it.content.purchaseId == purchaseId && it.status == DocumentStatus.ACTIVE
            }
    }

    /**
     * Las devoluciones a proveedor y sus líneas. Repite lo que exigen las guardas: un crédito lleva
     * proveedor, y una devolución solo cambia al anularse, una sola vez.
     */
    val purchaseReturnRepository: PurchaseReturnRepository = object : PurchaseReturnRepository {
        override fun observeById(id: Long): Flow<PurchaseReturn?> =
            committed.map { it.purchaseReturns[id] }

        override fun observeLines(purchaseReturnId: Long): Flow<List<PurchaseReturnLine>> =
            committed.map { tables -> purchaseReturnLinesOf(tables, purchaseReturnId) }

        override fun observeByPurchase(purchaseId: Long): Flow<List<PurchaseReturn>> =
            committed.map { tables ->
                tables.purchaseReturns.values
                    .filter { it.content.purchaseId == purchaseId }
                    .sortedBy { it.id }
            }

        override suspend fun findById(id: Long): PurchaseReturn? = read().purchaseReturns[id]

        override suspend fun findLines(purchaseReturnId: Long): List<PurchaseReturnLine> =
            purchaseReturnLinesOf(read(), purchaseReturnId)

        override fun observeTakenByPurchase(purchaseId: Long): Flow<Map<Long, LineAmounts>> =
            committed.map { takenOf(it, purchaseId) }

        override suspend fun takenByPurchase(purchaseId: Long): Map<Long, LineAmounts> =
            takenOf(read(), purchaseId)

        /** Como la base: el costo de cada línea sale de su fila `PURCHASE_RETURN` del libro. */
        private fun takenOf(tables: SaleTables, purchaseId: Long): Map<Long, LineAmounts> {
            val active = tables.purchaseReturns.values.filter {
                it.content.purchaseId == purchaseId && it.status == DocumentStatus.ACTIVE
            }.map { it.id }.toSet()
            return tables.purchaseReturnLines.values
                .filter { it.purchaseReturnId in active && it.content.purchaseLineId != null }
                .groupBy { checkNotNull(it.content.purchaseLineId) }
                .mapValues { (_, lines) ->
                    lines.fold(LineAmounts.NONE) { sum, line ->
                        val cost = -tables.movements.values.single {
                            it.source.purchaseReturnLineId == line.id &&
                                it.movement.type == StockMovementType.PURCHASE_RETURN
                        }.totalCost
                        LineAmounts(
                            quantity = sum.quantity + line.content.quantity,
                            net = sum.net + line.content.lineTotal,
                            tax = Money.ZERO,
                            cost = sum.cost + cost,
                        )
                    }
                }
        }

        override suspend fun insert(purchaseReturn: NewPurchaseReturn): Long {
            val credit = purchaseReturn.resolution == PurchaseReturnResolution.SUPPLIER_CREDIT
            if (credit && purchaseReturn.supplierId == null) brokenRule()
            if (purchaseReturn.total < Money.ZERO) brokenRule()
            val id = nextId("purchase_returns")
            write {
                it.copy(
                    purchaseReturns =
                        it.purchaseReturns + (id to PurchaseReturn(id, purchaseReturn, null)),
                )
            }
            return id
        }

        override suspend fun insertLine(
            purchaseReturnId: Long,
            line: NewPurchaseReturnLine,
        ): Long {
            if (purchaseReturnId !in read().purchaseReturns) brokenRule()
            val id = nextId("purchase_return_lines")
            write {
                it.copy(
                    purchaseReturnLines = it.purchaseReturnLines +
                        (id to PurchaseReturnLine(id, purchaseReturnId, line)),
                )
            }
            return id
        }

        override suspend fun markVoided(id: Long, voiding: Voiding) {
            val found = read().purchaseReturns[id]
            if (found == null || found.status != DocumentStatus.ACTIVE) brokenRule()
            write {
                it.copy(
                    purchaseReturns = it.purchaseReturns + (id to found.copy(voiding = voiding)),
                )
            }
        }
    }

    /**
     * El libro de los proveedores. Repite lo que comprueban la guarda y el repositorio de la base:
     * el signo y el origen de cada tipo, la moneda bloqueada, el saldo encadenado y una sola
     * compensación por fila, del mismo proveedor y con el monto opuesto. Deja al proveedor con el
     * saldo de la fila.
     */
    val supplierLedger: SupplierLedgerRepository = object : SupplierLedgerRepository {
        override fun observeBySupplier(supplierId: Long): Flow<List<SupplierLedgerEntry>> =
            committed.map { tables -> supplierEntriesOf(tables, supplierId) }

        override suspend fun findById(id: Long): SupplierLedgerEntry? = read().supplierEntries[id]

        override suspend fun lastBalanceAfter(supplierId: Long): Money? =
            supplierEntriesOf(read(), supplierId).lastOrNull()?.content?.balanceAfter

        override suspend fun findByPurchase(purchaseId: Long): List<SupplierLedgerEntry> =
            read().supplierEntries.values
                .filter { it.content.purchaseId == purchaseId }
                .sortedBy { it.id }

        override suspend fun findByPurchaseReturn(
            purchaseReturnId: Long,
        ): List<SupplierLedgerEntry> = read().supplierEntries.values
            .filter { it.content.purchaseReturnId == purchaseReturnId }
            .sortedBy { it.id }

        override suspend fun isCompensated(entryId: Long): Boolean =
            read().supplierEntries.values.any { it.content.reversesEntryId == entryId }

        override suspend fun append(entry: NewSupplierLedgerEntry): Long {
            if (settings.settings.value.regional.currency.lockedAt == null) brokenRule()
            val supplier = read().suppliers[entry.supplierId] ?: brokenRule()
            val previous = lastBalanceAfter(entry.supplierId) ?: Money.ZERO
            if (previous + entry.amount != entry.balanceAfter) brokenRule()
            if (!supplierEntryFits(entry)) brokenRule()
            entry.reversesEntryId?.let { reverses ->
                val original = read().supplierEntries[reverses] ?: brokenRule()
                val matches = original.content.supplierId == entry.supplierId &&
                    original.content.amount == -entry.amount &&
                    original.content.type != SupplierLedgerEntryType.ENTRY_VOID
                if (!matches || isCompensated(reverses)) brokenRule()
            }
            val id = nextId("supplier_ledger_entries")
            write {
                it.copy(
                    supplierEntries =
                        it.supplierEntries + (id to SupplierLedgerEntry(id, entry)),
                )
            }
            write { tables ->
                val updated = supplier.copy(
                    balance = entry.balanceAfter,
                    updatedAt = entry.createdAt,
                )
                tables.copy(suppliers = tables.suppliers + (supplier.id to updated))
            }
            return id
        }
    }

    /** El libro de los clientes, con las reglas de su guarda: saldo encadenado y una compensación. */
    val customerLedger: CustomerLedgerRepository = object : CustomerLedgerRepository {
        override fun observeByCustomer(customerId: Long): Flow<List<CustomerLedgerEntry>> = unused()

        override suspend fun findByCustomer(customerId: Long): List<CustomerLedgerEntry> =
            customerEntriesOf(read(), customerId)

        override suspend fun findById(id: Long): CustomerLedgerEntry? = read().customerEntries[id]

        override suspend fun lastBalanceAfter(customerId: Long): Money? =
            customerEntriesOf(read(), customerId).lastOrNull()?.balanceAfter

        override suspend fun findBySale(saleId: Long): List<CustomerLedgerEntry> =
            read().customerEntries.values
                .filter { (it.source as? CustomerEntrySource.Sale)?.saleId == saleId }
                .sortedBy { it.id }

        override suspend fun findBySaleReturn(saleReturnId: Long): List<CustomerLedgerEntry> =
            read().customerEntries.values
                .filter { it.source == CustomerEntrySource.SaleReturn(saleReturnId) }
                .sortedBy { it.id }

        override suspend fun isCompensated(entryId: Long): Boolean =
            read().customerEntries.values.any {
                (it.source as? CustomerEntrySource.Reverses)?.entryId == entryId
            }

        override suspend fun append(entry: NewCustomerLedgerEntry): Long {
            val draft = entry.draft
            val customer = read().customers[draft.customerId] ?: brokenRule()
            val previous = lastBalanceAfter(draft.customerId) ?: Money.ZERO
            if (previous + draft.amount != entry.balanceAfter) brokenRule()
            val source = draft.source
            if (source is CustomerEntrySource.Sale && read().sales[source.saleId] == null) {
                brokenRule()
            }
            if (source is CustomerEntrySource.Reverses) {
                val original = read().customerEntries[source.entryId] ?: brokenRule()
                val matches = original.customerId == draft.customerId &&
                    original.amount == -draft.amount &&
                    original.type != CustomerLedgerEntryType.ENTRY_VOID
                if (!matches || isCompensated(source.entryId)) brokenRule()
            }
            val id = nextId("customer_ledger_entries")
            val saved = CustomerLedgerEntry(
                id = id,
                customerId = draft.customerId,
                type = draft.type,
                amount = draft.amount,
                balanceAfter = entry.balanceAfter,
                source = draft.source,
                dueDate = draft.dueDate,
                note = draft.note,
                occurredAt = entry.occurredAt,
                businessDate = entry.businessDate,
            )
            write { it.copy(customerEntries = it.customerEntries + (id to saved)) }
            write { tables ->
                val updated = customer.copy(balance = entry.balanceAfter)
                tables.copy(customers = tables.customers + (customer.id to updated))
            }
            return id
        }
    }

    /**
     * El libro de puntos, con las reglas de su guarda: el signo de cada tipo, la venta en lo que se
     * gana o se canjea, saldo encadenado dentro del tope y una sola compensación por fila.
     */
    val loyaltyLedger: LoyaltyLedgerRepository = object : LoyaltyLedgerRepository {
        override fun observeByCustomer(customerId: Long): Flow<List<LoyaltyLedgerEntry>> = unused()

        override suspend fun findById(id: Long): LoyaltyLedgerEntry? = read().loyaltyEntries[id]

        override suspend fun lastBalanceAfter(customerId: Long): Long? =
            loyaltyEntriesOf(read(), customerId).lastOrNull()?.balanceAfter

        override suspend fun findBySale(saleId: Long): List<LoyaltyLedgerEntry> =
            read().loyaltyEntries.values
                .filter { (it.source as? LoyaltyEntrySource.Sale)?.saleId == saleId }
                .sortedBy { it.id }

        override suspend fun isCompensated(entryId: Long): Boolean =
            read().loyaltyEntries.values.any {
                (it.source as? LoyaltyEntrySource.Reverses)?.entryId == entryId
            }

        override suspend fun append(entry: NewLoyaltyLedgerEntry): Long {
            val draft = entry.draft
            val customer = read().customers[draft.customerId] ?: brokenRule()
            val previous = lastBalanceAfter(draft.customerId) ?: 0L
            if (previous + draft.points != entry.balanceAfter) brokenRule()
            if (draft.points == 0L || entry.balanceAfter !in -MAX_POINTS..MAX_POINTS) brokenRule()
            val signFits = when (draft.type) {
                LoyaltyEntryType.EARN -> draft.points > 0L
                LoyaltyEntryType.REDEEM -> draft.points < 0L
                LoyaltyEntryType.ADJUSTMENT, LoyaltyEntryType.ENTRY_VOID -> true
            }
            if (!signFits) brokenRule()
            val source = draft.source
            val bySale =
                draft.type == LoyaltyEntryType.EARN || draft.type == LoyaltyEntryType.REDEEM
            if (bySale != (source is LoyaltyEntrySource.Sale)) brokenRule()
            if (source is LoyaltyEntrySource.Sale && read().sales[source.saleId] == null) {
                brokenRule()
            }
            if ((draft.type == LoyaltyEntryType.ENTRY_VOID) !=
                (source is LoyaltyEntrySource.Reverses)
            ) {
                brokenRule()
            }
            if (source is LoyaltyEntrySource.Reverses) {
                val original = read().loyaltyEntries[source.entryId] ?: brokenRule()
                val matches = original.customerId == draft.customerId &&
                    original.points == -draft.points &&
                    original.type != LoyaltyEntryType.ENTRY_VOID
                if (!matches || isCompensated(source.entryId)) brokenRule()
            }
            val id = nextId("loyalty_ledger_entries")
            val saved = LoyaltyLedgerEntry(
                id = id,
                customerId = draft.customerId,
                type = draft.type,
                points = draft.points,
                balanceAfter = entry.balanceAfter,
                source = draft.source,
                note = draft.note,
                occurredAt = entry.occurredAt,
                businessDate = entry.businessDate,
            )
            write { it.copy(loyaltyEntries = it.loyaltyEntries + (id to saved)) }
            write { tables ->
                val updated = customer.copy(pointsBalance = entry.balanceAfter)
                tables.copy(customers = tables.customers + (customer.id to updated))
            }
            return id
        }
    }

    private fun loyaltyEntriesOf(tables: SaleTables, customerId: Long): List<LoyaltyLedgerEntry> =
        tables.loyaltyEntries.values.filter { it.customerId == customerId }.sortedBy { it.id }

    /** Los clientes, que la venta solo lee. */
    val customerRepository: CustomerRepository = object : CustomerRepository {
        override fun observe(filter: ArchiveFilter): Flow<List<Customer>> = unused()

        override fun observeSelectable(): Flow<List<Customer>> = unused()

        override fun observeRecent(limit: Int): Flow<List<Customer>> = unused()

        override fun observePointsSummary(): Flow<PointsSummary> = unused()

        override fun observeById(id: Long): Flow<Customer?> = committed.map { it.customers[id] }

        override suspend fun findById(id: Long): Customer? = read().customers[id]

        override suspend fun findActiveByNormalizedName(normalizedName: String): List<Customer> =
            unused()

        override suspend fun hasHistory(id: Long): Boolean = unused()

        override suspend fun insert(details: CustomerDetails, createdAt: Instant): Long = unused()

        override suspend fun update(id: Long, details: CustomerDetails, updatedAt: Instant) =
            unused()

        override suspend fun setArchivedAt(id: Long, archivedAt: Instant?, updatedAt: Instant) =
            unused()

        override suspend fun anonymize(id: Long, anonymizedAt: Instant) = unused()

        override suspend fun delete(id: Long) = unused()
    }

    /** Agrega un cliente sin saldo, vigente salvo que se pida otra cosa. */
    fun addCustomer(id: Long, archived: Boolean = false, anonymized: Boolean = false) = arrange {
        val name = "Customer $id"
        val customer = Customer(
            id = id,
            contact = CustomerContact(name, name.lowercase(), null, null, null)
                .takeIf { !anonymized },
            priceListId = null,
            balance = Money.ZERO,
            pointsBalance = 0L,
            anonymizedAt = if (anonymized) EPOCH else null,
            archivedAt = if (archived) EPOCH else null,
            createdAt = EPOCH,
            updatedAt = EPOCH,
        )
        it.copy(customers = it.customers + (id to customer))
    }

    private fun customerEntriesOf(tables: SaleTables, customerId: Long): List<CustomerLedgerEntry> =
        tables.customerEntries.values.filter { it.customerId == customerId }.sortedBy { it.id }

    /** Los proveedores, que la compra solo lee. */
    val supplierRepository: SupplierRepository = object : SupplierRepository {
        override fun observe(filter: ArchiveFilter): Flow<List<Supplier>> = unused()

        override suspend fun findById(id: Long): Supplier? = read().suppliers[id]

        override fun observeById(id: Long): Flow<Supplier?> = committed.map { it.suppliers[id] }

        override suspend fun findActiveByNormalizedName(normalizedName: String): Supplier? =
            unused()

        override suspend fun hasHistory(id: Long): Boolean = unused()

        override suspend fun insert(details: SupplierDetails, createdAt: Instant): Long = unused()

        override suspend fun update(id: Long, details: SupplierDetails, updatedAt: Instant) =
            unused()

        override suspend fun setArchivedAt(id: Long, archivedAt: Instant?, updatedAt: Instant) =
            unused()

        override suspend fun delete(id: Long) = unused()
    }

    fun registerPurchase(): RegisterPurchaseUseCase = RegisterPurchaseUseCase(
        transactions = transactions,
        settings = settings,
        products = products,
        purchases = purchases,
        suppliers = supplierRepository,
        units = unitRepository,
        systemRows = systemRowRepository,
        ledger = ledger,
        taxRates = taxRateRepository,
        clock = clock,
        productUnits = productUnitRepository,
        alerts = alertRepository.takeIf { withAlerts },
        supplierLedger = supplierLedger,
        money = DocumentMoney(moneyAccountRepository, moneyLedger),
        locations = locationRepository,
        lots = lotRepository,
    )

    fun voidPurchase(): VoidPurchaseUseCase = VoidPurchaseUseCase(
        transactions = transactions,
        settings = settings,
        products = products,
        purchases = purchases,
        ledger = ledger,
        clock = clock,
        alerts = alertRepository.takeIf { withAlerts },
        supplierLedger = supplierLedger,
        money = DocumentMoney(moneyAccountRepository, moneyLedger),
    )

    fun registerSupplierReturn(): RegisterSupplierReturnUseCase = RegisterSupplierReturnUseCase(
        transactions = transactions,
        settings = settings,
        products = products,
        units = unitRepository,
        purchases = purchases,
        purchaseReturns = purchaseReturnRepository,
        suppliers = supplierRepository,
        supplierLedger = supplierLedger,
        systemRows = systemRowRepository,
        ledger = ledger,
        clock = clock,
        money = DocumentMoney(moneyAccountRepository, moneyLedger),
        alerts = alertRepository.takeIf { withAlerts },
    )

    fun voidSupplierReturn(): VoidSupplierReturnUseCase = VoidSupplierReturnUseCase(
        transactions = transactions,
        settings = settings,
        products = products,
        purchaseReturns = purchaseReturnRepository,
        supplierLedger = supplierLedger,
        ledger = ledger,
        clock = clock,
        money = DocumentMoney(moneyAccountRepository, moneyLedger),
        alerts = alertRepository.takeIf { withAlerts },
    )

    /** Agrega un proveedor con nombre inventado, archivado o no, y con plazo habitual o sin él. */
    fun addSupplier(id: Long, archived: Boolean = false, termsDays: Int? = null) = arrange {
        val name = "Supplier $id"
        val supplier = Supplier(
            id = id,
            details = SupplierDetails(name, name.lowercase(), null, null, null, termsDays),
            balance = Money.ZERO,
            archivedAt = if (archived) EPOCH else null,
            createdAt = EPOCH,
            updatedAt = EPOCH,
        )
        it.copy(suppliers = it.suppliers + (id to supplier))
    }

    /**
     * Las cuentas de dinero, que la venta lee. [openCashSessionId] sale de las sesiones abiertas que
     * prepara cada prueba, una por cuenta como mucho.
     */
    val moneyAccountRepository: MoneyAccountRepository = object : MoneyAccountRepository {
        override fun observe(filter: ArchiveFilter): Flow<List<MoneyAccount>> = unused()

        override suspend fun findById(id: Long): MoneyAccount? {
            moneyAccountReads++
            return read().moneyAccounts[id]
        }

        override suspend fun findActiveByNormalizedName(normalizedName: String): MoneyAccount? =
            unused()

        override suspend fun insert(account: NewMoneyAccount): Long = unused()

        override suspend fun update(id: Long, details: MoneyAccountDetails, updatedAt: Instant) =
            unused()

        override suspend fun setArchivedAt(id: Long, archivedAt: Instant?, updatedAt: Instant) =
            unused()

        override suspend fun openCashSessionId(accountId: Long): Long? {
            moneyAccountReads++
            return read().openCashSessions[accountId]
        }
    }

    /** Cuántas veces se leyó una cuenta o su caja: quien no usa cuentas no debe leer ninguna. */
    var moneyAccountReads: Int = 0
        private set

    /**
     * El libro de dinero, con las reglas de su guarda y su repositorio: la moneda bloqueada, el saldo
     * encadenado, el signo y el origen de cada tipo, y una sola compensación por movimiento, en la
     * misma cuenta y con el monto opuesto. Deja la cuenta con el saldo del movimiento.
     */
    val moneyLedger: MoneyLedgerRepository = object : MoneyLedgerRepository {
        override fun observeByAccount(accountId: Long): Flow<List<MoneyMovement>> = unused()

        override suspend fun findById(id: Long): MoneyMovement? = read().moneyMovements[id]

        override suspend fun findByAccount(accountId: Long): List<MoneyMovement> =
            read().moneyMovements.values.filter { it.accountId == accountId }.sortedBy { it.id }

        override suspend fun findByTransferGroup(groupId: String): List<MoneyMovement> = unused()

        override suspend fun findBySupplierLedgerEntry(
            supplierLedgerEntryId: Long,
        ): List<MoneyMovement> = unused()

        override suspend fun findBySalePayment(salePaymentId: Long): List<MoneyMovement> =
            read().moneyMovements.values
                .filter { it.draft.origin == MoneyOrigin.SalePayment(salePaymentId) }
                .sortedBy { it.id }

        override suspend fun findByExpense(expenseId: Long): List<MoneyMovement> = unused()

        override suspend fun findByPurchase(purchaseId: Long): List<MoneyMovement> =
            read().moneyMovements.values
                .filter { it.draft.origin == MoneyOrigin.Purchase(purchaseId) }
                .sortedBy { it.id }

        override suspend fun findByPurchaseReturn(purchaseReturnId: Long): List<MoneyMovement> =
            read().moneyMovements.values
                .filter { it.draft.origin == MoneyOrigin.PurchaseReturn(purchaseReturnId) }
                .sortedBy { it.id }

        override suspend fun findBySaleReturn(saleReturnId: Long): List<MoneyMovement> =
            read().moneyMovements.values
                .filter { it.draft.origin == MoneyOrigin.SaleReturn(saleReturnId) }
                .sortedBy { it.id }

        override suspend fun findByCustomerLedgerEntry(
            customerLedgerEntryId: Long,
        ): List<MoneyMovement> = unused()

        override fun observeOtherIncome(
            period: ReportPeriod,
            currency: AccountCurrency,
        ): Flow<Money> = unused()

        override fun observePendingSettlement(today: BusinessDate): Flow<Map<Long, Money>> =
            unused()

        override suspend fun findDocument(movementId: Long): MoneyDocument? = unused()

        override suspend fun isCompensated(movementId: Long): Boolean =
            read().moneyMovements.values.any {
                (it.draft.origin as? MoneyOrigin.Void)?.reversesMovementId == movementId
            }

        override suspend fun append(movement: NewMoneyMovement): Long {
            if (settings.settings.value.regional.currency.lockedAt == null) brokenRule()
            val draft = movement.draft
            val account = read().moneyAccounts[draft.accountId] ?: brokenRule()
            if (account.balance + draft.amount != movement.balanceAfter) brokenRule()
            val origin = draft.origin
            if (origin is MoneyOrigin.SalePayment &&
                read().payments[origin.salePaymentId] == null
            ) {
                brokenRule()
            }
            if (origin is MoneyOrigin.Void) {
                val original = read().moneyMovements[origin.reversesMovementId] ?: brokenRule()
                val matches = original.accountId == draft.accountId &&
                    original.amount == -draft.amount &&
                    original.type != MoneyMovementType.MOVEMENT_VOID
                if (!matches || isCompensated(original.id)) brokenRule()
            }
            val id = nextId("money_movements")
            val saved = MoneyMovement(
                id = id,
                draft = draft,
                balanceAfter = movement.balanceAfter,
                time = movement.time,
                createdAt = movement.createdAt,
            )
            write { it.copy(moneyMovements = it.moneyMovements + (id to saved)) }
            write { tables ->
                val updated = account.copy(balance = movement.balanceAfter)
                tables.copy(moneyAccounts = tables.moneyAccounts + (account.id to updated))
            }
            return id
        }
    }

    /**
     * Agrega una cuenta de dinero con el saldo [balance], vigente salvo que se pida otra cosa, en la
     * moneda del negocio (`XTS` con 0 decimales) o en [currency].
     */
    fun addMoneyAccount(
        id: Long,
        currency: AccountCurrency = AccountCurrency(requireNotNull(CurrencyCode.of("XTS")), 0),
        archived: Boolean = false,
        kind: MoneyAccountKind = MoneyAccountKind.BANK,
        balance: Long = 0L,
    ) = arrange {
        val name = "Account $id"
        val account = MoneyAccount(
            id = id,
            systemKey = null,
            details = MoneyAccountDetails(name, name.lowercase(), kind),
            currency = currency,
            balance = Money(balance),
            archivedAt = if (archived) EPOCH else null,
            createdAt = EPOCH,
            updatedAt = EPOCH,
        )
        it.copy(moneyAccounts = it.moneyAccounts + (id to account))
    }

    /** Deja abierta la sesión de caja [sessionId] en la cuenta [accountId]. */
    fun openCashSession(accountId: Long, sessionId: Long) = arrange {
        it.copy(openCashSessions = it.openCashSessions + (accountId to sessionId))
    }

    fun registerSale(): RegisterSaleUseCase = RegisterSaleUseCase(
        transactions = transactions,
        settings = settings,
        products = products,
        sales = sales,
        payments = payments,
        units = unitRepository,
        systemRows = systemRowRepository,
        ledger = ledger,
        methodNames = methodNames,
        taxRates = taxRateRepository,
        clock = clock,
        heldSales = heldSaleRepository,
        productUnits = productUnitRepository,
        alerts = alertRepository.takeIf { withAlerts },
        customers = customerRepository,
        customerLedger = customerLedger,
        loyaltyLedger = loyaltyLedger,
        moneyAccounts = moneyAccountRepository,
        moneyLedger = moneyLedger,
        priceLists = priceListRepository,
        productPrices = productPriceRepository,
        recipes = recipeRepository.takeIf { withCombos },
        lineComponents = lineComponentRepository.takeIf { withCombos },
        locations = locationRepository,
    )

    /** Las recetas de los combos: la venta solo las lee. */
    val recipeRepository: ProductComponentRepository = object : ProductComponentRepository {
        override fun observeAll(): Flow<List<RecipeComponent>> = unused()

        override fun observeByParent(parentId: Long): Flow<List<RecipeComponent>> = unused()

        override suspend fun findAll(): List<RecipeComponent> =
            read().recipes.values.sortedBy { it.id }

        override suspend fun findByParent(parentId: Long): List<RecipeComponent> = unused()

        override suspend fun insert(
            parentId: Long,
            componentId: Long,
            quantity: QuantityMilli,
            createdAt: Instant,
        ): Long = unused()

        override suspend fun updateQuantity(
            id: Long,
            quantity: QuantityMilli,
            updatedAt: Instant,
        ) = unused()

        override suspend fun delete(id: Long) = unused()
    }

    /**
     * Las hojas de las líneas de combo, con lo que comprueba la guarda: la línea es de combo, la
     * hoja no es otro combo y `tracked` coincide con el modo de su producto.
     */
    val lineComponentRepository: SaleLineComponentRepository =
        object : SaleLineComponentRepository {
            override suspend fun findBySaleLine(saleLineId: Long): List<SaleLineComponent> =
                read().lineComponents.values.filter { it.saleLineId == saleLineId }
                    .sortedBy { it.id }

            override suspend fun findBySale(saleId: Long): List<SaleLineComponent> {
                val tables = read()
                val lineIds = linesOf(tables, saleId).map { it.id }.toSet()
                return tables.lineComponents.values.filter { it.saleLineId in lineIds }
                    .sortedBy { it.id }
            }

            override suspend fun insert(saleLineId: Long, component: NewSaleLineComponent): Long {
                val tables = read()
                val line = tables.lines[saleLineId] ?: brokenRule()
                val product = tables.products[component.componentProductId] ?: brokenRule()
                val mode = product.details.stockMode
                if (line.content.stockEffect != LineStockEffect.COMPONENTS ||
                    mode == ProductStockMode.COMPONENTS ||
                    (mode == ProductStockMode.TRACKED) != component.tracked ||
                    component.quantity.milli <= 0L
                ) {
                    brokenRule()
                }
                val id = nextId("sale_line_components")
                val row = SaleLineComponent(id, saleLineId, component)
                write { it.copy(lineComponents = it.lineComponents + (id to row)) }
                return id
            }
        }

    /** Guarda [rows] como recetas, como las dejaría la base: parte de lo que arma una prueba. */
    fun addRecipe(vararg rows: RecipeComponent) = arrange { tables ->
        tables.copy(recipes = tables.recipes + rows.associateBy { it.id })
    }

    /** Las listas de precios de la venta, si la prueba las usa; sin ellas, solo la inicial. */
    var priceListRepository: PriceListRepository? = null
    var productPriceRepository: ProductPriceRepository? = null

    /** Las ubicaciones de la venta, si la prueba las usa; sin ellas, solo la inicial. */
    var locationRepository: LocationRepository? = null

    fun registerSaleReturn(): RegisterSaleReturnUseCase = RegisterSaleReturnUseCase(
        transactions = transactions,
        settings = settings,
        products = products,
        sales = sales,
        returns = saleReturns,
        units = unitRepository,
        ledger = ledger,
        clock = clock,
        alerts = alertRepository.takeIf { withAlerts },
        moneyAccounts = moneyAccountRepository,
        moneyLedger = moneyLedger,
        customers = customerRepository,
        customerLedger = customerLedger,
        lineComponents = lineComponentRepository.takeIf { withCombos },
    )

    fun voidSaleReturn(): VoidSaleReturnUseCase = VoidSaleReturnUseCase(
        transactions = transactions,
        settings = settings,
        products = products,
        sales = sales,
        returns = saleReturns,
        ledger = ledger,
        clock = clock,
        money = DocumentMoney(moneyAccountRepository, moneyLedger),
        customerLedger = customerLedger,
        alerts = alertRepository.takeIf { withAlerts },
    )

    fun exchangeSale(): ExchangeSaleUseCase =
        ExchangeSaleUseCase(transactions, registerSale(), registerSaleReturn())

    fun voidSale(): VoidSaleUseCase = VoidSaleUseCase(
        transactions = transactions,
        settings = settings,
        products = products,
        sales = sales,
        ledger = ledger,
        clock = clock,
        alerts = alertRepository.takeIf { withAlerts },
        customerLedger = customerLedger,
        loyaltyLedger = loyaltyLedger,
        payments = payments,
        moneyAccounts = moneyAccountRepository,
        moneyLedger = moneyLedger,
    )

    /** Agrega un producto ya guardado, con el estado de stock que se le dé. */
    fun addProduct(product: Product) = arrange {
        it.copy(
            products =
                it.products + (product.id to product),
        )
    }

    fun addMethod(method: SalePaymentMethod) = arrange {
        it.copy(
            methods =
                it.methods + (method.id to method),
        )
    }

    private fun linesOf(tables: SaleTables, saleId: Long): List<SaleLine> =
        tables.lines.values.filter { it.saleId == saleId }.sortedBy { it.id }

    private fun supplierEntriesOf(tables: SaleTables, supplierId: Long): List<SupplierLedgerEntry> =
        tables.supplierEntries.values.filter {
            it.content.supplierId == supplierId
        }.sortedBy { it.id }

    /** El signo y el origen que la guarda exige a cada tipo de fila del libro del proveedor. */
    private fun supplierEntryFits(entry: NewSupplierLedgerEntry): Boolean {
        val sign = when (entry.type) {
            SupplierLedgerEntryType.CREDIT_PURCHASE -> entry.amount > Money.ZERO

            SupplierLedgerEntryType.PAYMENT, SupplierLedgerEntryType.RETURN_CREDIT ->
                entry.amount < Money.ZERO

            SupplierLedgerEntryType.OPENING_BALANCE,
            SupplierLedgerEntryType.ADJUSTMENT,
            SupplierLedgerEntryType.ENTRY_VOID,
            -> entry.amount != Money.ZERO
        }
        return sign &&
            (entry.type == SupplierLedgerEntryType.CREDIT_PURCHASE) == (entry.purchaseId != null) &&
            (entry.type == SupplierLedgerEntryType.RETURN_CREDIT) ==
            (entry.purchaseReturnId != null) &&
            (entry.type == SupplierLedgerEntryType.ENTRY_VOID) == (entry.reversesEntryId != null) &&
            (entry.type != SupplierLedgerEntryType.ADJUSTMENT || entry.note != null)
    }

    private fun purchaseLinesOf(tables: SaleTables, purchaseId: Long): List<PurchaseLine> =
        tables.purchaseLines.values.filter { it.purchaseId == purchaseId }.sortedBy { it.id }

    private fun purchaseReturnLinesOf(
        tables: SaleTables,
        purchaseReturnId: Long,
    ): List<PurchaseReturnLine> = tables.purchaseReturnLines.values
        .filter { it.purchaseReturnId == purchaseReturnId }
        .sortedBy { it.id }

    private fun paymentsOf(tables: SaleTables, saleId: Long): List<SalePayment> =
        tables.payments.values.filter { it.saleId == saleId }.sortedBy { it.id }

    private fun movementsOf(
        tables: SaleTables,
        keep: (StockMovementRecord) -> Boolean,
    ): List<StockMovementRecord> = tables.movements.values.filter(keep).sortedBy { it.id }

    private fun unused(): Nothing = error("No se usa en estas pruebas")

    /** Lo que la base real devuelve cuando una guarda rechaza la escritura. */
    private fun brokenRule(): Nothing =
        throw StorageFailureException(StorageFailure.INVARIANT_VIOLATION)

    companion object {
        /** 2026-11-02 15:00 UTC: lunes 2026-11-02 10:00 en `Etc/GMT+5`. */
        const val NOW_MILLIS = 1_793_631_600_000L
        val EPOCH: Instant = Instant.fromEpochMilliseconds(0)
        const val MAIN_LOCATION = 1L
        const val DEFAULT_PRICE_LIST = 1L
        const val UNIT = 1L
        const val KILOGRAM = 2L
        const val CASH = 1L

        /** El tope de puntos de un cliente, hacia arriba y hacia abajo, como la guarda. */
        const val MAX_POINTS = 1_000_000_000_000L
    }
}

/** Un producto de prueba con nombre inventado; el stock y el costo se dan por parámetro. */
internal fun product(
    id: Long,
    name: String = "Product $id",
    salePrice: Long = 0L,
    stockMode: ProductStockMode = ProductStockMode.TRACKED,
    unitId: Long = SaleWorld.UNIT,
    onHandMilli: Long = 0L,
    averageCost: Long = 0L,
    stockValue: Long = 0L,
    initialCost: Long = 0L,
    fixedUnitCost: Long = 0L,
    replacementCost: Long = 0L,
    tracksLots: Boolean = false,
    archived: Boolean = false,
    taxRateId: Long? = null,
    minStockMilli: Long = 0L,
): Product = Product(
    id = id,
    details = ProductDetails(
        name = name,
        normalizedName = name.lowercase(),
        categoryId = null,
        defaultSupplierId = null,
        baseUnitId = unitId,
        stockMode = stockMode,
        tracksLots = tracksLots,
        fixedUnitCost = Money(fixedUnitCost),
        minStock = QuantityMilli(minStockMilli),
        barcode = null,
        parentProductId = null,
        variantAxis1 = null,
        variantAxis2 = null,
        variantValue1 = null,
        variantValue2 = null,
        photoMediaId = null,
        taxRateId = taxRateId,
        targetMargin = null,
        referenceCost = null,
        referencePrice = null,
        expiryNoticeDays = null,
    ),
    salePrice = Money(salePrice),
    initialCost = Money(initialCost),
    averageCost = Money(averageCost),
    stockValue = Money(stockValue),
    onHand = QuantityMilli(onHandMilli),
    replacementCost = Money(replacementCost),
    replacementCostAt = null,
    priceReviewSince = null,
    archivedAt = if (archived) SaleWorld.EPOCH else null,
    createdAt = SaleWorld.EPOCH,
    updatedAt = SaleWorld.EPOCH,
)

/** Una unidad inicial de prueba, vigente. */
internal fun measureUnit(id: Long, systemKey: String, kind: UnitKind, allowsFraction: Boolean) =
    MeasureUnit(
        id = id,
        systemKey = systemKey,
        name = null,
        symbol = null,
        kind = kind,
        allowsFraction = allowsFraction,
        archivedAt = null,
    )

/** Un método de pago de prueba, inicial y sin comisión salvo que se diga otra cosa. */
internal fun method(
    id: Long = SaleWorld.CASH,
    kind: PaymentMethodKind = PaymentMethodKind.CASH,
    systemKey: String? = kind.name,
    name: String? = null,
    feeBp: Long = 0L,
    feeFixed: Long = 0L,
    settlementDays: Int = 0,
    moneyAccountId: Long? = null,
    archived: Boolean = false,
    priceAdjustmentBp: Long = 0L,
): SalePaymentMethod = SalePaymentMethod(
    id = id,
    systemKey = systemKey,
    name = name,
    kind = kind,
    feeRate = BasisPoints(feeBp),
    feeFixed = Money(feeFixed),
    settlementDays = settlementDays,
    moneyAccountId = moneyAccountId,
    isArchived = archived,
    priceAdjustment = BasisPoints(priceAdjustmentBp),
)

/** Una tasa de impuesto escrita por la persona, con nombre inventado. */
internal fun taxRate(id: Long, name: String, bp: Long, archived: Boolean = false): TaxRate =
    TaxRate(
        id = id,
        details = TaxRateDetails(name, name.lowercase(), BasisPoints(bp)),
        archivedAt = if (archived) SaleWorld.EPOCH else null,
        createdAt = SaleWorld.EPOCH,
        updatedAt = SaleWorld.EPOCH,
    )
