package io.github.yesidrobayo.shopledger.ui.product

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.catalog.MovementDocument
import io.github.yesidrobayo.shopledger.domain.model.catalog.NewPriceChange
import io.github.yesidrobayo.shopledger.domain.model.catalog.PriceChange
import io.github.yesidrobayo.shopledger.domain.model.catalog.PriceChangeSource
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductDetails
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductMovement
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductOverview
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.catalog.UnitKind
import io.github.yesidrobayo.shopledger.domain.model.stock.StockKey
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovement
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementRecord
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementSource
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType
import io.github.yesidrobayo.shopledger.domain.rule.TextNormalizer
import kotlin.time.Instant

/**
 * Un producto inventado con su libro y su historial de precios, para las vistas previas y la
 * galería: un nombre de 80 caracteres, montos largos, una corrección de costo y una venta anulada.
 */
object ProductDetailSamples {
    const val LONG_NAME: String =
        "Café molido de altura en bolsa resellable con válvula, tostión media, 500 gramos"

    private val AT: Instant = Instant.fromEpochMilliseconds(1_700_000_000_000L)
    private val DATE: BusinessDate = requireNotNull(BusinessDate.parse("2026-11-10"))
    private val EARLIER: BusinessDate = requireNotNull(BusinessDate.parse("2026-10-28"))

    val UNIT: MeasureUnit = MeasureUnit(
        id = 1L,
        systemKey = "UNIT",
        name = null,
        symbol = null,
        kind = UnitKind.COUNT,
        allowsFraction = false,
        archivedAt = null,
    )

    fun product(
        onHand: Long = 3_000L,
        minStock: Long = 5_000L,
        price: Long = 5_000_00L,
        cost: Long = 3_800_00L,
        mode: ProductStockMode = ProductStockMode.TRACKED,
        archived: Boolean = false,
    ): Product = Product(
        id = 1L,
        details = ProductDetails(
            name = LONG_NAME,
            normalizedName = TextNormalizer.normalize(LONG_NAME),
            categoryId = null,
            defaultSupplierId = null,
            baseUnitId = UNIT.id,
            stockMode = mode,
            tracksLots = false,
            fixedUnitCost = if (mode == ProductStockMode.UNTRACKED) Money(cost) else Money.ZERO,
            minStock = QuantityMilli(minStock),
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
        salePrice = Money(price),
        initialCost = Money(3_000_00L),
        averageCost = if (mode == ProductStockMode.TRACKED) Money(cost) else Money.ZERO,
        stockValue = Money(maxOf(0L, onHand) / 1_000 * cost),
        onHand = QuantityMilli(onHand),
        replacementCost = Money.ZERO,
        replacementCostAt = null,
        priceReviewSince = null,
        archivedAt = if (archived) AT else null,
        createdAt = AT,
        updatedAt = AT,
    )

    fun overview(product: Product = product(), withHistory: Boolean = true): ProductOverview =
        ProductOverview(
            product = product,
            unit = UNIT,
            movements = if (withHistory) movements() else emptyList(),
            prices = if (withHistory) prices() else listOf(prices().last()),
        )

    private fun movements(): List<ProductMovement> {
        fun row(
            id: Long,
            type: StockMovementType,
            quantity: Long,
            balance: Long,
            cost: Long,
            date: BusinessDate,
            reverses: Long? = null,
        ) = StockMovementRecord(
            movement = StockMovement(
                id,
                StockKey(1L, 1L, null),
                type,
                QuantityMilli(quantity),
                reverses,
            ),
            reason = null,
            unitCost = Money(cost),
            totalCost = Money.ZERO,
            balanceAfter = QuantityMilli(balance),
            averageCostAfter = Money(cost),
            stockValueAfter = Money.ZERO,
            source = StockMovementSource.NONE,
            note = null,
            occurredAt = AT,
            businessDate = date,
            createdAt = AT,
        )
        return listOf(
            ProductMovement(
                row(5L, StockMovementType.COST_CORRECTION, 0L, 3_000L, 3_800_00L, DATE),
                Money(3_650_00L),
                null,
                null,
            ),
            ProductMovement(
                row(
                    4L,
                    StockMovementType.SALE_VOID,
                    2_000L,
                    3_000L,
                    3_650_00L,
                    DATE,
                    reverses = 3L,
                ),
                Money(3_650_00L),
                null,
                MovementDocument.Sale(9L),
            ),
            ProductMovement(
                row(3L, StockMovementType.SALE, -2_000L, 1_000L, 3_650_00L, DATE),
                Money(3_650_00L),
                4L,
                MovementDocument.Sale(9L),
            ),
            ProductMovement(
                row(2L, StockMovementType.PURCHASE, 24_000L, 3_000L, 3_650_00L, EARLIER),
                Money(3_000_00L),
                null,
                MovementDocument.Purchase(7L),
            ),
        )
    }

    private fun prices(): List<PriceChange> {
        fun change(id: Long, old: Long?, new: Long, source: PriceChangeSource, date: BusinessDate) =
            PriceChange(
                id,
                NewPriceChange(
                    productId = 1L,
                    priceListId = 1L,
                    oldPrice = old?.let(::Money),
                    newPrice = Money(new),
                    source = source,
                    batchId = null,
                    bulkRule = null,
                    occurredAt = AT,
                    businessDate = date,
                ),
            )
        return listOf(
            change(3L, 4_800_00L, 5_000_00L, PriceChangeSource.MANUAL, DATE),
            change(2L, 4_500_00L, 4_800_00L, PriceChangeSource.IMPORT, EARLIER),
            change(1L, null, 4_500_00L, PriceChangeSource.INITIAL, EARLIER),
        )
    }
}
