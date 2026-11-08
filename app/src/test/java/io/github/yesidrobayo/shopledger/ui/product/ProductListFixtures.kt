package io.github.yesidrobayo.shopledger.ui.product

import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.CatalogLink
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.catalog.NewProduct
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductDetails
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.catalog.UnitKind
import io.github.yesidrobayo.shopledger.domain.port.ProductRepository
import io.github.yesidrobayo.shopledger.domain.port.UnitRepository
import io.github.yesidrobayo.shopledger.domain.rule.TextNormalizer
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

// Datos inventados para las pruebas de la lista de productos: nombres sin marcas y montos cualquiera.

internal val CREATED: Instant = Instant.fromEpochMilliseconds(1_700_000_000_000L)

internal val COUNT_UNIT = MeasureUnit(
    id = 1L,
    systemKey = "UNIT",
    name = null,
    symbol = null,
    kind = UnitKind.COUNT,
    allowsFraction = false,
    archivedAt = null,
)

/** Un producto guardado, con su stock y su costo como los dejaría el libro. */
internal fun product(
    id: Long,
    name: String,
    salePrice: Long = 0L,
    averageCost: Long = 0L,
    stockValue: Long = 0L,
    onHand: Long = 0L,
    minStock: Long = 0L,
    stockMode: ProductStockMode = ProductStockMode.TRACKED,
    fixedUnitCost: Long = 0L,
    barcode: String? = null,
): Product = Product(
    id = id,
    details = ProductDetails(
        name = name,
        normalizedName = TextNormalizer.normalize(name),
        categoryId = null,
        defaultSupplierId = null,
        baseUnitId = COUNT_UNIT.id,
        stockMode = stockMode,
        tracksLots = false,
        fixedUnitCost = Money(fixedUnitCost),
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
    ),
    salePrice = Money(salePrice),
    initialCost = Money(averageCost),
    averageCost = Money(averageCost),
    stockValue = Money(stockValue),
    onHand = QuantityMilli(onHand),
    replacementCost = Money.ZERO,
    replacementCostAt = null,
    priceReviewSince = null,
    archivedAt = null,
    createdAt = CREATED,
    updatedAt = CREATED,
)

/**
 * Los productos en memoria, que solo se leen. Con [failure], cada lectura falla, como una base que
 * no se puede leer. [reads] cuenta cuántas veces se pidió la lista.
 */
internal class FakeCatalog : ProductRepository {
    val products = MutableStateFlow<List<Product>>(emptyList())
    var failure: Throwable? = null
    var reads: Int = 0
        private set

    override fun observe(filter: ArchiveFilter): Flow<List<Product>> {
        reads++
        return products.map { list ->
            failure?.let { throw it }
            list.filter { it.isArchived == (filter == ArchiveFilter.ARCHIVED) }
                .sortedWith(compareBy<Product> { it.details.normalizedName }.thenBy { it.id })
        }
    }

    override fun observeById(id: Long): Flow<Product?> = notUsed()

    override suspend fun findById(id: Long): Product? = notUsed()

    override suspend fun findByIds(ids: Collection<Long>): List<Product> = notUsed()

    override suspend fun findActiveByNormalizedName(normalizedName: String): Product? = notUsed()

    override suspend fun findActiveByBarcode(barcode: String): Product? = notUsed()

    override suspend fun hasHistory(id: Long): Boolean = notUsed()

    override suspend fun catalogLinks(id: Long): Set<CatalogLink> = notUsed()

    override suspend fun insert(product: NewProduct): Long = notUsed()

    override suspend fun updateDetails(id: Long, details: ProductDetails, updatedAt: Instant) =
        notUsed()

    override suspend fun updateSalePrice(id: Long, salePrice: Money, updatedAt: Instant) = notUsed()

    override suspend fun updateInitialCost(id: Long, initialCost: Money, updatedAt: Instant) =
        notUsed()

    override suspend fun updateReplacementCost(
        id: Long,
        replacementCost: Money,
        replacementCostAt: Instant?,
        updatedAt: Instant,
    ) = notUsed()

    override suspend fun setPriceReviewSince(id: Long, since: Instant?, updatedAt: Instant) =
        notUsed()

    override suspend fun setArchivedAt(id: Long, archivedAt: Instant?, updatedAt: Instant) =
        notUsed()

    override suspend fun delete(id: Long) = notUsed()

    private fun notUsed(): Nothing = error("La lista solo lee el catálogo")
}

/** Las unidades en memoria. */
internal class FakeUnits : UnitRepository {
    val units = MutableStateFlow(listOf(COUNT_UNIT))

    override fun observeActive(): Flow<List<MeasureUnit>> = units

    override fun observeAll(): Flow<List<MeasureUnit>> = units

    override suspend fun findById(id: Long): MeasureUnit? = units.value.firstOrNull { it.id == id }
}
