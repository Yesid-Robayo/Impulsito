package io.github.yesidrobayo.shopledger.domain.usecase.sale

import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.DocumentStatus
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.Voiding
import io.github.yesidrobayo.shopledger.domain.model.catalog.CatalogLink
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.catalog.NewProduct
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductDetails
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.catalog.SystemRow
import io.github.yesidrobayo.shopledger.domain.model.catalog.UnitKind
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodKind
import io.github.yesidrobayo.shopledger.domain.model.sale.NewSale
import io.github.yesidrobayo.shopledger.domain.model.sale.NewSaleLine
import io.github.yesidrobayo.shopledger.domain.model.sale.NewSalePayment
import io.github.yesidrobayo.shopledger.domain.model.sale.Sale
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLine
import io.github.yesidrobayo.shopledger.domain.model.sale.SalePayment
import io.github.yesidrobayo.shopledger.domain.model.sale.SalePaymentMethod
import io.github.yesidrobayo.shopledger.domain.model.settings.BusinessCurrency
import io.github.yesidrobayo.shopledger.domain.model.settings.CurrencyCode
import io.github.yesidrobayo.shopledger.domain.model.stock.NewStockMovement
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementRecord
import io.github.yesidrobayo.shopledger.domain.port.PaymentMethodNames
import io.github.yesidrobayo.shopledger.domain.port.ProductRepository
import io.github.yesidrobayo.shopledger.domain.port.SalePaymentRepository
import io.github.yesidrobayo.shopledger.domain.port.SaleRepository
import io.github.yesidrobayo.shopledger.domain.port.StockLedgerRepository
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException
import io.github.yesidrobayo.shopledger.domain.port.SystemRowRepository
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.port.UnitRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakeAppSettingsRepository
import io.github.yesidrobayo.shopledger.domain.testing.FixedClock
import io.github.yesidrobayo.shopledger.domain.testing.appSettings
import io.github.yesidrobayo.shopledger.domain.testing.regionalSettings
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
    val lastIds: Map<String, Long> = emptyMap(),
    val changeSeq: Long = 0L,
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

        override suspend fun catalogLinks(id: Long): Set<CatalogLink> = unused()

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
        ) = unused()

        override suspend fun setPriceReviewSince(id: Long, since: Instant?, updatedAt: Instant) =
            unused()

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
            write { it.copy(sales = it.sales + (id to sale.copy(voiding = voiding))) }
        }

        override suspend fun hasActiveReturns(saleId: Long): Boolean =
            saleId in read().salesWithActiveReturns
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

    /** Las filas iniciales que la venta busca por su clave. */
    var systemRows: Map<SystemRow, Long> = mapOf(
        SystemRow.DEFAULT_UNIT to UNIT,
        SystemRow.MAIN_LOCATION to MAIN_LOCATION,
        SystemRow.DEFAULT_PRICE_LIST to DEFAULT_PRICE_LIST,
    )

    val unitRepository: UnitRepository = object : UnitRepository {
        override fun observeActive(): Flow<List<MeasureUnit>> = unused()

        override fun observeAll(): Flow<List<MeasureUnit>> = unused()

        override suspend fun findById(id: Long): MeasureUnit? = units[id]
    }

    val systemRowRepository: SystemRowRepository = object : SystemRowRepository {
        override suspend fun findId(row: SystemRow): Long? = systemRows[row]
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

        override suspend fun findByPurchase(purchaseId: Long): List<StockMovementRecord> = unused()

        override suspend fun isCompensated(movementId: Long): Boolean =
            read().movements.values.any { it.movement.reversesMovementId == movementId }

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
        clock = clock,
    )

    fun voidSale(): VoidSaleUseCase = VoidSaleUseCase(
        transactions = transactions,
        settings = settings,
        products = products,
        sales = sales,
        ledger = ledger,
        clock = clock,
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
        minStock = QuantityMilli.ZERO,
        barcode = null,
        parentProductId = null,
        variantAxis1 = null,
        variantAxis2 = null,
        variantValue1 = null,
        variantValue2 = null,
        photoMediaId = null,
        taxRateId = null,
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
)
