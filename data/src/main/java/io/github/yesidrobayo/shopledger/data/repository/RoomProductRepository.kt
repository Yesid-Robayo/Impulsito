package io.github.yesidrobayo.shopledger.data.repository

import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.data.local.catalog.ProductEntity
import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.NewProduct
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductDetails
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.port.ProductRepository
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Los productos sobre `products`.
 *
 * Nada de aquí escribe el saldo, el costo promedio ni el valor del stock de un producto que ya
 * tiene libro: los fija [RoomStockLedgerRepository] con cada fila. La única excepción es el costo
 * inicial, que también es el promedio mientras no haya filas, y la base lo rechaza en cuanto hay una.
 */
internal class RoomProductRepository(database: AppDatabase) : ProductRepository {
    private val products = database.productDao()
    private val writes = RepositoryWrites(database)

    override fun observe(filter: ArchiveFilter): Flow<List<Product>> {
        val rows =
            when (filter) {
                ArchiveFilter.ACTIVE -> products.observeActive()
                ArchiveFilter.ARCHIVED -> products.observeArchived()
            }
        return rows.map { list -> list.map(::toProduct) }.distinctUntilChanged()
    }

    override fun observeById(id: Long): Flow<Product?> =
        products.observeById(id).map { it?.let(::toProduct) }.distinctUntilChanged()

    override suspend fun findById(id: Long): Product? = products.findById(id)?.let(::toProduct)

    override suspend fun findByIds(ids: Collection<Long>): List<Product> =
        if (ids.isEmpty()) emptyList() else products.findByIds(ids.distinct()).map(::toProduct)

    override suspend fun findActiveByNormalizedName(normalizedName: String): Product? =
        products.findActiveByNormalizedName(normalizedName)?.let(::toProduct)

    override suspend fun findActiveByBarcode(barcode: String): Product? =
        products.findActiveByBarcode(barcode)?.let(::toProduct)

    override suspend fun hasHistory(id: Long): Boolean = products.hasHistory(id)

    override suspend fun insert(product: NewProduct): Long = writes.inTransaction {
        val details = product.details
        val at = product.createdAt.toStoredMillis()
        products.insert(
            ProductEntity(
                name = details.name,
                normalizedName = details.normalizedName,
                categoryId = details.categoryId,
                defaultSupplierId = details.defaultSupplierId,
                baseUnitId = details.baseUnitId,
                stockMode = details.stockMode.name,
                salePrice = product.salePrice.minor,
                // Sin filas en el libro, el promedio es el costo con que empieza su reproducción.
                averageCost = product.initialCost.minor,
                initialCost = product.initialCost.minor,
                fixedUnitCost = details.fixedUnitCost.minor,
                // Nace sin stock: un stock inicial entra después como la primera fila del libro.
                stockValue = 0L,
                onHandMilli = 0L,
                minStockMilli = details.minStock.milli,
                barcode = details.barcode,
                parentProductId = details.parentProductId,
                variantAxis1 = details.variantAxis1,
                variantAxis2 = details.variantAxis2,
                variantValue1 = details.variantValue1,
                variantValue2 = details.variantValue2,
                photoMediaId = details.photoMediaId,
                taxRateId = details.taxRateId,
                replacementCost = product.replacementCost.minor,
                replacementCostAt = product.replacementCostAt?.toStoredMillis(),
                targetMarginBp = details.targetMargin?.bp,
                referenceCost = details.referenceCost?.minor,
                referencePrice = details.referencePrice?.minor,
                priceReviewSince = null,
                tracksLots = details.tracksLots.toStoredFlag(),
                expiryNoticeDays = details.expiryNoticeDays?.toLong(),
                archivedAt = null,
                createdAt = at,
                updatedAt = at,
            ),
        )
    }

    override suspend fun updateDetails(id: Long, details: ProductDetails, updatedAt: Instant) {
        writes.inTransaction {
            requireOneRow(
                products.updateDetails(
                    id = id,
                    name = details.name,
                    normalizedName = details.normalizedName,
                    categoryId = details.categoryId,
                    defaultSupplierId = details.defaultSupplierId,
                    baseUnitId = details.baseUnitId,
                    stockMode = details.stockMode.name,
                    tracksLots = details.tracksLots.toStoredFlag(),
                    fixedUnitCost = details.fixedUnitCost.minor,
                    minStockMilli = details.minStock.milli,
                    barcode = details.barcode,
                    parentProductId = details.parentProductId,
                    variantAxis1 = details.variantAxis1,
                    variantAxis2 = details.variantAxis2,
                    variantValue1 = details.variantValue1,
                    variantValue2 = details.variantValue2,
                    photoMediaId = details.photoMediaId,
                    taxRateId = details.taxRateId,
                    targetMarginBp = details.targetMargin?.bp,
                    referenceCost = details.referenceCost?.minor,
                    referencePrice = details.referencePrice?.minor,
                    expiryNoticeDays = details.expiryNoticeDays?.toLong(),
                    updatedAt = updatedAt.toStoredMillis(),
                ),
            )
        }
    }

    override suspend fun updateSalePrice(id: Long, salePrice: Money, updatedAt: Instant) {
        writes.inTransaction {
            requireOneRow(products.updateSalePrice(id, salePrice.minor, updatedAt.toStoredMillis()))
        }
    }

    /**
     * Con filas en el libro, la base no lo cambia y la transacción se deshace como regla rota: el
     * costo ya lo lleva el libro, y pisar el promedio lo apartaría de él.
     */
    override suspend fun updateInitialCost(id: Long, initialCost: Money, updatedAt: Instant) {
        writes.inTransaction {
            requireOneRow(
                products.updateInitialCost(id, initialCost.minor, updatedAt.toStoredMillis()),
            )
        }
    }

    override suspend fun updateReplacementCost(
        id: Long,
        replacementCost: Money,
        replacementCostAt: Instant?,
        updatedAt: Instant,
    ) {
        writes.inTransaction {
            requireOneRow(
                products.updateReplacementCost(
                    id,
                    replacementCost.minor,
                    replacementCostAt?.toStoredMillis(),
                    updatedAt.toStoredMillis(),
                ),
            )
        }
    }

    override suspend fun setPriceReviewSince(id: Long, since: Instant?, updatedAt: Instant) {
        writes.inTransaction {
            requireOneRow(
                products.setPriceReviewSince(
                    id,
                    since?.toStoredMillis(),
                    updatedAt.toStoredMillis(),
                ),
            )
        }
    }

    override suspend fun setArchivedAt(id: Long, archivedAt: Instant?, updatedAt: Instant) {
        writes.inTransaction {
            requireOneRow(
                products.setArchivedAt(
                    id,
                    archivedAt?.toStoredMillis(),
                    updatedAt.toStoredMillis(),
                ),
            )
        }
    }

    /**
     * El historial de precios, las alertas y lo demás que cuelga solo del producto se borran con él,
     * en cascada. Con historia, o si otro producto lo usa como componente, extra o variante, una
     * llave `RESTRICT` rechaza el borrado y sale como regla rota.
     */
    override suspend fun delete(id: Long) {
        writes.inTransaction { requireOneRow(products.delete(id)) }
    }

    private fun toProduct(row: ProductEntity): Product = Product(
        id = row.id,
        details = ProductDetails(
            name = row.name,
            normalizedName = row.normalizedName,
            categoryId = row.categoryId,
            defaultSupplierId = row.defaultSupplierId,
            baseUnitId = row.baseUnitId,
            stockMode = storedEnum<ProductStockMode>(row.stockMode),
            tracksLots = row.tracksLots.toFlag(),
            fixedUnitCost = Money(row.fixedUnitCost),
            minStock = QuantityMilli(row.minStockMilli),
            barcode = row.barcode,
            parentProductId = row.parentProductId,
            variantAxis1 = row.variantAxis1,
            variantAxis2 = row.variantAxis2,
            variantValue1 = row.variantValue1,
            variantValue2 = row.variantValue2,
            photoMediaId = row.photoMediaId,
            taxRateId = row.taxRateId,
            targetMargin = row.targetMarginBp?.let(::BasisPoints),
            referenceCost = row.referenceCost?.let(::Money),
            referencePrice = row.referencePrice?.let(::Money),
            expiryNoticeDays = row.expiryNoticeDays?.toStoredInt(),
        ),
        salePrice = Money(row.salePrice),
        initialCost = Money(row.initialCost),
        averageCost = Money(row.averageCost),
        stockValue = Money(row.stockValue),
        onHand = QuantityMilli(row.onHandMilli),
        replacementCost = Money(row.replacementCost),
        replacementCostAt = row.replacementCostAt?.toInstant(),
        priceReviewSince = row.priceReviewSince?.toInstant(),
        archivedAt = row.archivedAt?.toInstant(),
        createdAt = row.createdAt.toInstant(),
        updatedAt = row.updatedAt.toInstant(),
    )
}
