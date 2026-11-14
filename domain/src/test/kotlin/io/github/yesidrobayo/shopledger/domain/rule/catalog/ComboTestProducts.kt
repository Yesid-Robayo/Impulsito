package io.github.yesidrobayo.shopledger.domain.rule.catalog

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.catalog.RecipeComponent
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CatalogFixture
import kotlin.time.Instant

/** Productos y filas de receta inventados para las pruebas de combos. */
internal object ComboTestProducts {
    private val AT: Instant = Instant.fromEpochMilliseconds(1_700_000_000_000L)

    fun product(
        id: Long,
        mode: ProductStockMode = ProductStockMode.TRACKED,
        archived: Boolean = false,
        onHand: Long = 0L,
        averageCost: Long = 0L,
        stockValue: Long = 0L,
        fixedUnitCost: Long = 0L,
    ): Product = Product(
        id = id,
        details = CatalogFixture.details("Producto $id", mode)
            .copy(fixedUnitCost = Money(fixedUnitCost)),
        salePrice = Money.ZERO,
        initialCost = Money.ZERO,
        averageCost = Money(averageCost),
        stockValue = Money(stockValue),
        onHand = QuantityMilli(onHand),
        replacementCost = Money.ZERO,
        replacementCostAt = null,
        priceReviewSince = null,
        archivedAt = if (archived) AT else null,
        createdAt = AT,
        updatedAt = AT,
    )

    private var nextRowId = 1_000L

    /** Una fila de receta: [parent] lleva [milli] milésimas de [component] por unidad. */
    fun row(parent: Long, component: Long, milli: Long): RecipeComponent =
        RecipeComponent(nextRowId++, parent, component, QuantityMilli(milli))
}
