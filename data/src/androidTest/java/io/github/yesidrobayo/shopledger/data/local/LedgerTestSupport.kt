package io.github.yesidrobayo.shopledger.data.local

import androidx.room3.withWriteTransaction
import io.github.yesidrobayo.shopledger.data.local.catalog.CREATED_AT
import io.github.yesidrobayo.shopledger.data.local.catalog.CatalogDatabaseTest
import io.github.yesidrobayo.shopledger.data.local.catalog.UNIT_ID
import io.github.yesidrobayo.shopledger.data.local.catalog.product
import io.github.yesidrobayo.shopledger.data.local.purchase.PurchaseEntity
import io.github.yesidrobayo.shopledger.data.local.purchase.PurchaseLineEntity
import io.github.yesidrobayo.shopledger.data.local.sale.SaleEntity
import io.github.yesidrobayo.shopledger.data.local.sale.SaleLineComponentEntity
import io.github.yesidrobayo.shopledger.data.local.sale.SaleLineEntity
import io.github.yesidrobayo.shopledger.data.local.stock.StockMovementEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Before

// Apoyo de las pruebas de ventas, compras y libro de stock. Todos los nombres y montos son
// inventados. Cada documento cuadra como lo exigirán las guardas: el total es neto más impuesto, lo
// que se cobra es total, propina y redondeo, y cada fila del libro lleva el origen de su tipo.

/**
 * Además de la base de las pruebas del catálogo, cada prueba empieza con una lista de precios y un
 * producto con libro de stock, a los que apuntan las ventas, las compras y el libro.
 */
internal abstract class LedgerDatabaseTest : CatalogDatabaseTest() {
    protected var priceListId = 0L
    protected var productId = 0L

    @Before
    fun createPriceListAndProduct() = runBlocking {
        priceListId = insertPriceList("default")
        productId = database.productDao().insert(product("Café"))
    }

    /** Guarda una venta con una línea del producto, y devuelve esa línea con su id. */
    protected suspend fun insertSaleWithLine(): SaleLineEntity {
        val saleId = database.saleDao().insert(sale(priceListId))
        val line = saleLine(saleId, productId, priceListId)
        return line.copy(id = database.saleLineDao().insert(line))
    }

    /**
     * Agrega a la venta [saleId] la línea de un combo nuevo que consumió el producto, y devuelve ese
     * componente con su id.
     */
    protected suspend fun insertComboLineWithComponent(saleId: Long): SaleLineComponentEntity {
        // Un nombre por combo: dos productos vigentes no se llaman igual.
        val name = "Combo ${count("SELECT COUNT(*) FROM products")}"
        val comboId = database.productDao().insert(product(name).copy(stockMode = "COMPONENTS"))
        val comboLine =
            saleLine(saleId, comboId, priceListId).copy(stockEffect = "COMPONENTS")
        val lineId = database.saleLineDao().insert(comboLine)
        val component = saleLineComponent(lineId, productId)
        return component.copy(id = database.saleLineComponentDao().insert(component))
    }

    /** Guarda una compra con una línea del producto, y devuelve esa línea con su id. */
    protected suspend fun insertPurchaseWithLine(): PurchaseLineEntity {
        val purchaseId = database.purchaseDao().insert(purchase(supplierId = null))
        val line = purchaseLine(purchaseId, productId)
        return line.copy(id = database.purchaseLineDao().insert(line))
    }

    /**
     * Agrega la fila al libro y deja al producto con el saldo, el costo y el valor que dice tener
     * después, en una sola transacción: lo que hará el repositorio del libro.
     */
    protected suspend fun append(row: StockMovementEntity): Long = database.withWriteTransaction {
        val id = database.stockMovementDao().insert(row)
        database.productDao().updateStockState(
            row.productId,
            onHandMilli = row.balanceAfterMilli,
            averageCost = row.averageCostAfter,
            stockValue = row.stockValueAfter,
            updatedAt = row.createdAt,
        )
        id
    }
}

/** Ubicación cualquiera: la tabla de ubicaciones todavía no existe, y la columna no tiene llave. */
internal const val LOCATION_ID = 1L

/** Fecha de negocio de los documentos que no piden otra. */
internal const val BUSINESS_DATE = "2026-01-01"

/**
 * Una venta activa de dos unidades a 5.250, con un descuento de 500 en toda la venta, impuesto
 * agregado del 19 %, propina de 1.000 y un redondeo de efectivo de -100.
 */
internal fun sale(
    priceListId: Long,
    businessDate: String = BUSINESS_DATE,
    occurredAt: Long = CREATED_AT,
): SaleEntity = SaleEntity(
    status = "ACTIVE",
    netTotal = 10_000,
    taxTotal = 1_900,
    total = 11_900,
    totalCost = 6_000,
    totalReplacementCost = 6_500,
    orderAdjustment = -500,
    tipAmount = 1_000,
    cashRoundingAmount = -100,
    amountDue = 12_800,
    customerId = null,
    priceListId = priceListId,
    locationId = LOCATION_ID,
    cashSessionId = null,
    quoteId = null,
    orderId = null,
    note = null,
    occurredAt = occurredAt,
    createdAt = occurredAt,
    businessDate = businessDate,
    voidedAt = null,
    voidReason = null,
    voidNote = null,
)

/** La línea de [sale]: dos unidades a 5.250, con su parte de -500 del descuento de la venta. */
internal fun saleLine(
    saleId: Long,
    productId: Long,
    priceListId: Long,
    parentLineId: Long? = null,
): SaleLineEntity = SaleLineEntity(
    saleId = saleId,
    productId = productId,
    unitId = UNIT_ID,
    unitFactorMilli = 1_000,
    enteredQuantityMilli = 2_000,
    quantityMilli = 2_000,
    unitPrice = 5_250,
    unitCost = 3_000,
    lineDiscount = 0,
    orderAdjustmentShare = -500,
    netAmount = 10_000,
    taxRateBp = 1_900,
    taxNameSnapshot = "Tasa general",
    taxAmount = 1_900,
    lineTotal = 11_900,
    lineCost = 6_000,
    unitReplacementCost = 3_250,
    lineReplacementCost = 6_500,
    productNameSnapshot = "Café",
    stockEffect = "DIRECT",
    parentLineId = parentLineId,
    priceListId = priceListId,
)

/** Lo que consumió de [componentProductId], con libro de stock, una línea de combo: dos unidades. */
internal fun saleLineComponent(
    saleLineId: Long,
    componentProductId: Long,
): SaleLineComponentEntity = SaleLineComponentEntity(
    saleLineId = saleLineId,
    componentProductId = componentProductId,
    quantityMilli = 2_000,
    tracked = 1,
    unitCost = 3_000,
    lineCost = 6_000,
)

/** Una compra activa de una caja por 20.000, pagada entera y sin impuesto. */
internal fun purchase(
    supplierId: Long?,
    businessDate: String = BUSINESS_DATE,
    occurredAt: Long = CREATED_AT,
): PurchaseEntity = PurchaseEntity(
    supplierId = supplierId,
    status = "ACTIVE",
    invoiceReference = "F-0001",
    netTotal = 20_000,
    taxTotal = 0,
    total = 20_000,
    costTotal = 20_000,
    amountPaid = 20_000,
    moneyAccountId = null,
    dueDate = null,
    locationId = LOCATION_ID,
    fxCurrencyCode = null,
    fxRateMicro = null,
    occurredAt = occurredAt,
    createdAt = occurredAt,
    businessDate = businessDate,
    voidedAt = null,
    voidReason = null,
    voidNote = null,
)

/** La línea de [purchase]: una caja de 12 unidades base por 20.000. */
internal fun purchaseLine(purchaseId: Long, productId: Long): PurchaseLineEntity =
    PurchaseLineEntity(
        purchaseId = purchaseId,
        productId = productId,
        unitId = UNIT_ID,
        unitFactorMilli = 12_000,
        enteredQuantityMilli = 1_000,
        quantityMilli = 12_000,
        unitCost = 20_000,
        lineTotal = 20_000,
        taxRateBp = 0,
        taxNameSnapshot = null,
        taxAmount = 0,
        costAmount = 20_000,
        lotId = null,
        referenceUnitCost = null,
    )

/**
 * Una fila del libro sin origen ni motivo, en la ubicación de siempre y sin lote. Cada prueba le
 * pone el origen de su tipo.
 */
internal fun movement(
    productId: Long,
    type: String,
    quantityMilli: Long,
    unitCost: Long,
    totalCost: Long,
    balanceAfterMilli: Long,
    averageCostAfter: Long,
    stockValueAfter: Long,
    occurredAt: Long = CREATED_AT,
    businessDate: String = BUSINESS_DATE,
): StockMovementEntity = StockMovementEntity(
    productId = productId,
    locationId = LOCATION_ID,
    lotId = null,
    type = type,
    reason = null,
    quantityMilli = quantityMilli,
    unitCost = unitCost,
    totalCost = totalCost,
    averageCostAfter = averageCostAfter,
    stockValueAfter = stockValueAfter,
    balanceAfterMilli = balanceAfterMilli,
    reversesMovementId = null,
    saleLineId = null,
    saleLineComponentId = null,
    purchaseLineId = null,
    saleReturnLineId = null,
    purchaseReturnLineId = null,
    transferLineId = null,
    productionBatchId = null,
    countSessionId = null,
    note = null,
    occurredAt = occurredAt,
    createdAt = occurredAt,
    businessDate = businessDate,
)

/** Un inventario inicial de diez unidades a 1.200: la primera fila de casi todo libro. */
internal fun openingBalance(
    productId: Long,
    occurredAt: Long = CREATED_AT,
    businessDate: String = BUSINESS_DATE,
): StockMovementEntity = movement(
    productId,
    type = "OPENING_BALANCE",
    quantityMilli = 10_000,
    unitCost = 1_200,
    totalCost = 12_000,
    balanceAfterMilli = 10_000,
    averageCostAfter = 1_200,
    stockValueAfter = 12_000,
    occurredAt = occurredAt,
    businessDate = businessDate,
)

/** Solo los ids de cada lista que emite el [Flow], para comparar órdenes. */
internal fun <T> Flow<List<T>>.ids(id: (T) -> Long): Flow<List<Long>> = map { rows -> rows.map(id) }

/** Las funciones que declara un DAO, sin las que agrega el compilador. */
internal fun functionsOf(dao: Class<*>): Set<String> =
    dao.declaredMethods.filterNot { it.isSynthetic }.map { it.name }.toSet()
