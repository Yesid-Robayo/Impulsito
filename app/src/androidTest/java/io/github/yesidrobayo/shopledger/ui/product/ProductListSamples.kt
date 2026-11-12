package io.github.yesidrobayo.shopledger.ui.product

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.Category
import io.github.yesidrobayo.shopledger.domain.model.catalog.CategoryDetails
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductDetails
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.catalog.UnitKind
import io.github.yesidrobayo.shopledger.domain.rule.TextNormalizer
import kotlin.time.Instant

// Productos inventados para las pruebas de la lista en el teléfono: nombres sin marcas, de pocas
// palabras para que cada búsqueda encuentre varios, y montos cualquiera.

internal val SAMPLE_UNIT = MeasureUnit(1L, "UNIT", null, null, UnitKind.COUNT, false, null)

private val CREATED: Instant = Instant.fromEpochMilliseconds(1_700_000_000_000L)

/** Categorías inventadas para la hoja de filtros, con nombres de largo variado. */
internal val SAMPLE_CATEGORIES: List<Category> =
    listOf("Granos", "Aseo del hogar y lavandería", "Bebidas", "Panadería").mapIndexed { i, name ->
        Category(
            id = i + 1L,
            details = CategoryDetails(name, TextNormalizer.normalize(name), targetMargin = null),
            createdAt = CREATED,
            updatedAt = CREATED,
        )
    }

private val WORDS = listOf("Arroz", "Aceite", "Azúcar", "Café", "Jabón", "Piña", "Sal", "Té")

/** El producto número [index]: un nombre de [WORDS] con su número, y un stock que va de 0 a 29. */
internal fun sampleProduct(index: Int): Product = sampleProduct(
    id = index.toLong(),
    name = "${WORDS[index % WORDS.size]} $index",
    salePrice = 1_000L + index,
    averageCost = 600L,
    onHand = 1_000L * (index % 30),
)

/** Un producto con sus montos y su stock, como los dejaría el libro. */
internal fun sampleProduct(
    id: Long,
    name: String,
    salePrice: Long,
    averageCost: Long,
    onHand: Long,
    minStock: Long = 5_000L,
    stockMode: ProductStockMode = ProductStockMode.TRACKED,
    fixedUnitCost: Long = 0L,
): Product = Product(
    id = id,
    details = ProductDetails(
        name = name,
        normalizedName = TextNormalizer.normalize(name),
        categoryId = null,
        defaultSupplierId = null,
        baseUnitId = SAMPLE_UNIT.id,
        stockMode = stockMode,
        tracksLots = false,
        fixedUnitCost = Money(fixedUnitCost),
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
    salePrice = Money(salePrice),
    initialCost = Money(averageCost),
    averageCost = Money(averageCost),
    stockValue = Money(if (onHand > 0L) averageCost * onHand / 1_000L else 0L),
    onHand = QuantityMilli(onHand),
    replacementCost = Money.ZERO,
    replacementCostAt = null,
    priceReviewSince = null,
    archivedAt = null,
    createdAt = CREATED,
    updatedAt = CREATED,
)
