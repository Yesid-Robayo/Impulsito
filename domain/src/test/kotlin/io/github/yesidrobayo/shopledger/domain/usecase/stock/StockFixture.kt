package io.github.yesidrobayo.shopledger.domain.usecase.stock

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.SystemRow
import io.github.yesidrobayo.shopledger.domain.model.stock.CostState
import io.github.yesidrobayo.shopledger.domain.model.stock.LowStockAlert
import io.github.yesidrobayo.shopledger.domain.model.stock.NewStockLot
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementRecord
import io.github.yesidrobayo.shopledger.domain.model.valueOrNull
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.rule.stock.OpeningBalanceRule
import io.github.yesidrobayo.shopledger.domain.testing.ProductStockHeader
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CatalogFixture
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.OpeningBalanceResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.RegisterOpeningBalanceUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.openingMovement
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.stamp
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.domain.usecase.region.CurrencyLock
import kotlin.time.Instant
import org.junit.Assert.assertTrue

/**
 * El catálogo en memoria de [CatalogFixture], con los casos de uso del ajuste de stock conectados
 * y un reloj que la prueba mueve con [now]. Todos los productos y montos son inventados.
 */
internal class StockFixture(val catalog: CatalogFixture) {
    constructor(zoneId: String = "UTC", currencyConfirmed: Boolean = true) :
        this(CatalogFixture(zoneId, currencyConfirmed))

    /** El instante que da el reloj de los casos de uso; la prueba lo mueve para cambiar de día. */
    var now: Instant = CatalogFixture.NOW
    private val clock = Clock { now }
    private val calendar = BusinessCalendarSource(catalog.settings)
    private val currencyLock = CurrencyLock(catalog.settings)

    val adjust = AdjustStockUseCase(
        catalog.transactions, catalog.products, catalog.units, catalog.systemRows, catalog.ledger,
        catalog.alerts, calendar, currencyLock, clock, catalog.lots,
    )
    val void = VoidStockAdjustmentUseCase(
        catalog.transactions,
        catalog.products,
        catalog.ledger,
        catalog.alerts,
        calendar,
        clock,
    )
    val observe = ObserveStockAdjustmentSubjectUseCase(
        catalog.transactions,
        catalog.products,
        catalog.units,
        catalog.ledger,
    )
    private val opening = RegisterOpeningBalanceUseCase(
        catalog.transactions, catalog.products, catalog.units, catalog.systemRows, catalog.ledger,
        catalog.alerts, calendar, currencyLock, clock,
    )

    fun product(id: Long): Product = catalog.product(id)

    fun movementsOf(productId: Long): List<StockMovementRecord> = catalog.movementsOf(productId)

    fun alertsOf(productId: Long): List<LowStockAlert> =
        catalog.tables.alerts.values.filter { it.productId == productId }.sortedBy { it.id }

    /**
     * Siembra el producto [id] y, si [units] no es cero, le registra un inventario inicial de
     * [units] milésimas a [unitCost] por unidad entera, como lo haría la persona.
     */
    suspend fun seedStock(
        id: Long = PRODUCT_ID,
        units: Long,
        unitCost: Long,
        minStock: Long = 0L,
        baseUnitId: Long = CatalogFixture.UNIT_ID,
        tracksLots: Boolean = false,
    ): Long? {
        catalog.seedProduct(
            id = id,
            name = "Producto inventado $id",
            initialCost = Money(unitCost),
            minStock = QuantityMilli(minStock),
            baseUnitId = baseUnitId,
            tracksLots = tracksLots,
        )
        if (units == 0L) return null
        val result = opening(id, QuantityMilli(units), Money(unitCost))
        assertTrue("El inventario inicial debía entrar", result is OpeningBalanceResult.Registered)
        return (result as OpeningBalanceResult.Registered).movementId
    }

    /** Agrega al producto [productId] un lote con [code], como lo haría una compra. */
    suspend fun seedLot(productId: Long, code: String, expiry: String? = null): Long =
        catalog.transactions.write {
            val id = catalog.lots.insert(
                NewStockLot(
                    productId = productId,
                    code = code,
                    expiryDate = expiry?.let { requireNotNull(BusinessDate.parse(it)) },
                    purchaseLineId = null,
                    createdAt = now,
                ),
            )
            TxResult.Commit(id)
        }

    /**
     * Entra [units] milésimas a [unitCost] al lote [lotId] del producto [productId], en
     * [locationId] o, sin ella, en la ubicación principal, como lo haría una compra con lote.
     */
    suspend fun receiveIntoLot(
        productId: Long,
        lotId: Long,
        units: Long,
        unitCost: Long,
        locationId: Long? = null,
    ) {
        catalog.transactions.write {
            val product = requireNotNull(catalog.products.findById(productId))
            val before = CostState(product.onHand, product.averageCost, product.stockValue)
            val entry = OpeningBalanceRule.entry(before, QuantityMilli(units), Money(unitCost))
            val time = requireNotNull(calendar.stamp(now))
            val location = locationId
                ?: requireNotNull(catalog.systemRows.findId(SystemRow.MAIN_LOCATION))
            val movement = openingMovement(
                productId,
                location,
                lotId,
                QuantityMilli(units),
                Money(unitCost),
                requireNotNull(entry.valueOrNull()),
                time,
            )
            TxResult.Commit(catalog.ledger.append(movement))
        }
    }

    /** Deja al producto [id] con [onHand] milésimas que valen [value], sin filas en el libro. */
    fun seedValue(id: Long, onHand: QuantityMilli, value: Money) {
        catalog.database.seed { tables ->
            val average = requireNotNull(tables.productStock[id]).averageCost
            val header = ProductStockHeader(onHand, average, value)
            tables.copy(productStock = tables.productStock + (id to header))
        }
    }

    companion object {
        const val PRODUCT_ID = 1L
    }
}
