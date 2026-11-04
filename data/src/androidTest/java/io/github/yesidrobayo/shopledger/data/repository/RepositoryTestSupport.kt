package io.github.yesidrobayo.shopledger.data.repository

import app.cash.turbine.ReceiveTurbine
import io.github.yesidrobayo.shopledger.data.local.BUSINESS_DATE
import io.github.yesidrobayo.shopledger.data.local.LOCATION_ID
import io.github.yesidrobayo.shopledger.data.local.LedgerDatabaseTest
import io.github.yesidrobayo.shopledger.data.local.catalog.CREATED_AT
import io.github.yesidrobayo.shopledger.data.local.catalog.LATER
import io.github.yesidrobayo.shopledger.data.local.catalog.UNIT_ID
import io.github.yesidrobayo.shopledger.data.transaction.BackupStateChangeCounter
import io.github.yesidrobayo.shopledger.data.transaction.RoomTransactionRunner
import io.github.yesidrobayo.shopledger.data.transaction.runnerFor
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.NewProduct
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductDetails
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.purchase.NewPurchase
import io.github.yesidrobayo.shopledger.domain.model.purchase.NewPurchaseLine
import io.github.yesidrobayo.shopledger.domain.model.sale.LineStockEffect
import io.github.yesidrobayo.shopledger.domain.model.sale.NewSale
import io.github.yesidrobayo.shopledger.domain.model.sale.NewSaleLine
import io.github.yesidrobayo.shopledger.domain.model.stock.NewStockMovement
import io.github.yesidrobayo.shopledger.domain.model.stock.StockAdjustmentReason
import io.github.yesidrobayo.shopledger.domain.model.stock.StockKey
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementDraft
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementSource
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.rule.TextNormalizer
import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Before

// Apoyo de las pruebas de los repositorios. Todos los nombres, cantidades y montos son inventados,
// y cada documento cuadra como lo exigen las guardas, igual que las filas de las pruebas de los DAO.

/**
 * Además de la base de las pruebas del libro, con su lista de precios y su producto, cada prueba
 * tiene el runner de la app, que cuenta el cambio en la fila del respaldo, como en producción.
 */
internal abstract class RepositoryTest : LedgerDatabaseTest() {
    protected lateinit var runner: RoomTransactionRunner

    @Before
    fun createRunner() {
        runner = runnerFor(database, BackupStateChangeCounter(database.backupStateDao()))
    }

    /** Corre [block] en una transacción de la app que confirma, y devuelve su valor. */
    protected suspend fun <T> commit(block: suspend () -> T): T =
        runner.write { TxResult.Commit(block()) }

    /** Lo que cuenta la fila del respaldo: cuántas escrituras confirmadas hubo. */
    protected suspend fun changeSeq(): Long =
        requireNotNull(database.backupStateDao().get()).changeSeq
}

/** La falla tipada con que [block] terminó, o falla la prueba si terminó bien o con otra. */
internal suspend fun storageFailureOf(block: suspend () -> Unit): StorageFailure {
    try {
        block()
    } catch (expected: StorageFailureException) {
        // Sin texto: el de SQLite puede traer datos del negocio.
        assertEquals(null, expected.message)
        assertEquals(null, expected.cause)
        return expected.failure
    }
    throw AssertionError("Debía terminar con una falla de la base")
}

/**
 * Espera una emisión igual a [expected] y descarta las anteriores. Room puede juntar varias
 * escrituras seguidas en una sola emisión, o emitir cada una: lo que importa es que llegue esta. Si
 * no llega a tiempo, Turbine hace fallar la prueba.
 */
internal suspend fun <T> ReceiveTurbine<T>.awaitValue(expected: T) {
    while (awaitItem() != expected) {
        // Una emisión intermedia: se sigue esperando.
    }
}

/** El instante de las filas de prueba, y otros después de él. */
internal val AT: Instant = Instant.fromEpochMilliseconds(CREATED_AT)
internal val LATER_AT: Instant = Instant.fromEpochMilliseconds(LATER)

/** La fecha de negocio de los documentos que no piden otra. */
internal val DATE: BusinessDate = requireNotNull(BusinessDate.parse(BUSINESS_DATE))

/** Lo que la persona escribe de un producto con libro de stock, sin lotes, en la unidad base. */
internal fun productDetails(
    name: String,
    stockMode: ProductStockMode = ProductStockMode.TRACKED,
    tracksLots: Boolean = false,
    minStock: Long = 0,
    barcode: String? = null,
): ProductDetails = ProductDetails(
    name = name,
    normalizedName = TextNormalizer.normalize(name),
    categoryId = null,
    defaultSupplierId = null,
    baseUnitId = UNIT_ID,
    stockMode = stockMode,
    tracksLots = tracksLots,
    fixedUnitCost = Money.ZERO,
    minStock = QuantityMilli(minStock),
    barcode = barcode,
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
)

/** Un producto nuevo a 2.500 con costo inicial de 1.200. */
internal fun newProduct(name: String, details: ProductDetails = productDetails(name)): NewProduct =
    NewProduct(
        details = details,
        salePrice = Money(2_500),
        initialCost = Money(1_200),
        replacementCost = Money.ZERO,
        replacementCostAt = null,
        createdAt = AT,
    )

/**
 * Una fila del libro en la ubicación inicial, sin origen ni motivo. [quantity], [balanceAfter],
 * [totalCost], [averageCostAfter] y [stockValueAfter] los pone cada prueba, como el caso de uso.
 */
internal fun ledgerRow(
    productId: Long,
    type: StockMovementType,
    quantity: Long,
    balanceAfter: Long,
    unitCost: Long = 1_200,
    totalCost: Long = quantity / 1_000 * unitCost,
    averageCostAfter: Long = 1_200,
    stockValueAfter: Long = balanceAfter.coerceAtLeast(0) / 1_000 * 1_200,
    locationId: Long = LOCATION_ID,
    lotId: Long? = null,
    reason: StockAdjustmentReason? = null,
    reversesMovementId: Long? = null,
    source: StockMovementSource = StockMovementSource.NONE,
    occurredAt: Instant = AT,
): NewStockMovement = NewStockMovement(
    movement = StockMovementDraft(
        key = StockKey(productId, locationId, lotId),
        type = type,
        quantity = QuantityMilli(quantity),
        reversesMovementId = reversesMovementId,
    ),
    reason = reason,
    unitCost = Money(unitCost),
    totalCost = Money(totalCost),
    balanceAfter = QuantityMilli(balanceAfter),
    averageCostAfter = Money(averageCostAfter),
    stockValueAfter = Money(stockValueAfter),
    source = source,
    note = null,
    occurredAt = occurredAt,
    businessDate = DATE,
    createdAt = occurredAt,
)

/** Un inventario inicial de [units] unidades a 1.200, sobre un producto sin stock. */
internal fun openingRow(productId: Long, units: Long = 10, lotId: Long? = null): NewStockMovement =
    ledgerRow(
        productId,
        StockMovementType.OPENING_BALANCE,
        units * 1_000,
        units * 1_000,
        lotId = lotId,
    )

/**
 * Una venta de dos unidades a 5.250, con un descuento de 500 en toda la venta, impuesto agregado
 * del 19 %, propina de 1.000 y un redondeo de efectivo de -100. La misma de las pruebas de los DAO.
 */
internal fun newSale(
    priceListId: Long,
    businessDate: BusinessDate = DATE,
    at: Instant = AT,
): NewSale = NewSale(
    netTotal = Money(10_000),
    taxTotal = Money(1_900),
    total = Money(11_900),
    totalCost = Money(6_000),
    totalReplacementCost = Money(6_500),
    orderAdjustment = Money(-500),
    tipAmount = Money(1_000),
    cashRoundingAmount = Money(-100),
    amountDue = Money(12_800),
    customerId = null,
    priceListId = priceListId,
    locationId = LOCATION_ID,
    cashSessionId = null,
    quoteId = null,
    orderId = null,
    note = "Mesa del fondo",
    occurredAt = at,
    businessDate = businessDate,
    createdAt = at,
)

/** La línea de [newSale]: dos unidades a 5.250, con su parte de -500 del descuento de la venta. */
internal fun newSaleLine(
    productId: Long,
    priceListId: Long,
    parentLineId: Long? = null,
): NewSaleLine = NewSaleLine(
    productId = productId,
    unitId = UNIT_ID,
    unitFactor = QuantityMilli(1_000),
    enteredQuantity = QuantityMilli(2_000),
    quantity = QuantityMilli(2_000),
    unitPrice = Money(5_250),
    unitCost = Money(3_000),
    lineDiscount = Money.ZERO,
    orderAdjustmentShare = Money(-500),
    netAmount = Money(10_000),
    taxRate = BasisPoints(1_900),
    taxNameSnapshot = "Tasa general",
    taxAmount = Money(1_900),
    lineTotal = Money(11_900),
    lineCost = Money(6_000),
    unitReplacementCost = Money(3_250),
    lineReplacementCost = Money(6_500),
    productNameSnapshot = "Café",
    stockEffect = LineStockEffect.DIRECT,
    parentLineId = parentLineId,
    priceListId = priceListId,
)

/** Una compra de una caja por 20.000, pagada entera y sin impuesto. La de las pruebas de los DAO. */
internal fun newPurchase(
    supplierId: Long?,
    businessDate: BusinessDate = DATE,
    at: Instant = AT,
): NewPurchase = NewPurchase(
    supplierId = supplierId,
    invoiceReference = "F-0001",
    netTotal = Money(20_000),
    taxTotal = Money.ZERO,
    total = Money(20_000),
    costTotal = Money(20_000),
    amountPaid = Money(20_000),
    moneyAccountId = null,
    dueDate = null,
    locationId = LOCATION_ID,
    foreignCurrency = null,
    occurredAt = at,
    businessDate = businessDate,
    createdAt = at,
)

/** La línea de [newPurchase]: una caja de 12 unidades base por 20.000. */
internal fun newPurchaseLine(productId: Long): NewPurchaseLine = NewPurchaseLine(
    productId = productId,
    unitId = UNIT_ID,
    unitFactor = QuantityMilli(12_000),
    enteredQuantity = QuantityMilli(1_000),
    quantity = QuantityMilli(12_000),
    unitCost = Money(20_000),
    lineTotal = Money(20_000),
    taxRate = BasisPoints.ZERO,
    taxNameSnapshot = null,
    taxAmount = Money.ZERO,
    costAmount = Money(20_000),
    lotId = null,
    referenceUnitCost = null,
)
