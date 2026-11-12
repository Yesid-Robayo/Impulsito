package io.github.yesidrobayo.shopledger.domain.testing

import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.CatalogLink
import io.github.yesidrobayo.shopledger.domain.model.catalog.Category
import io.github.yesidrobayo.shopledger.domain.model.catalog.CategoryDetails
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.catalog.NewPriceChange
import io.github.yesidrobayo.shopledger.domain.model.catalog.NewProduct
import io.github.yesidrobayo.shopledger.domain.model.catalog.PriceChange
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductDetails
import io.github.yesidrobayo.shopledger.domain.model.catalog.Supplier
import io.github.yesidrobayo.shopledger.domain.model.catalog.SupplierDetails
import io.github.yesidrobayo.shopledger.domain.model.catalog.SystemRow
import io.github.yesidrobayo.shopledger.domain.model.stock.LowStockAlert
import io.github.yesidrobayo.shopledger.domain.model.stock.LowStockNotificationStatus
import io.github.yesidrobayo.shopledger.domain.model.stock.NewLowStockAlert
import io.github.yesidrobayo.shopledger.domain.port.CategoryRepository
import io.github.yesidrobayo.shopledger.domain.port.LowStockAlertRepository
import io.github.yesidrobayo.shopledger.domain.port.PriceHistoryRepository
import io.github.yesidrobayo.shopledger.domain.port.ProductRepository
import io.github.yesidrobayo.shopledger.domain.port.SupplierRepository
import io.github.yesidrobayo.shopledger.domain.port.SystemRowRepository
import io.github.yesidrobayo.shopledger.domain.port.UnitRepository
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Un [ProductRepository] sobre [InMemoryDatabase].
 *
 * El producto se guarda sin su estado de stock: al leerlo se le suma el que dejó el libro en
 * [Tables.productStock], como en la base real, donde solo la escritura del libro lo cambia. Cada
 * escritura es una sola escritura de la base, para poder inyectar una falla en cada una.
 */
internal class FakeProductRepository(private val database: InMemoryDatabase) : ProductRepository {
    override fun observe(filter: ArchiveFilter): Flow<List<Product>> =
        database.tables.map { tables ->
            tables.products.values
                .map { withStock(tables, it) }
                .filter { it.isArchived == (filter == ArchiveFilter.ARCHIVED) }
                .sortedWith(compareBy<Product> { it.details.normalizedName }.thenBy { it.id })
        }

    override fun observeById(id: Long): Flow<Product?> =
        database.tables.map { tables -> tables.products[id]?.let { withStock(tables, it) } }

    override suspend fun findById(id: Long): Product? {
        val tables = database.read()
        return tables.products[id]?.let { withStock(tables, it) }
    }

    override suspend fun findByIds(ids: Collection<Long>): List<Product> = ids.mapNotNull {
        findById(it)
    }

    override suspend fun findActiveByNormalizedName(normalizedName: String): Product? =
        active().firstOrNull { it.details.normalizedName == normalizedName }

    override suspend fun findActiveByBarcode(barcode: String): Product? =
        active().firstOrNull { it.details.barcode == barcode }

    override suspend fun hasHistory(id: Long): Boolean {
        val tables = database.read()
        return id in tables.productsWithOtherHistory ||
            tables.movements.values.any { it.movement.key.productId == id }
    }

    override suspend fun catalogLinks(id: Long): Set<CatalogLink> =
        database.read().catalogLinks[id].orEmpty()

    override suspend fun insert(product: NewProduct): Long {
        val id = database.nextId(TABLE)
        val stored = Product(
            id = id,
            details = product.details,
            salePrice = product.salePrice,
            initialCost = product.initialCost,
            averageCost = product.initialCost,
            stockValue = Money.ZERO,
            onHand = QuantityMilli.ZERO,
            replacementCost = product.replacementCost,
            replacementCostAt = product.replacementCostAt,
            priceReviewSince = null,
            archivedAt = null,
            createdAt = product.createdAt,
            updatedAt = product.createdAt,
        )
        database.write { it.copy(products = it.products + (id to stored)) }
        return id
    }

    override suspend fun updateDetails(id: Long, details: ProductDetails, updatedAt: Instant) =
        change(id) { it.copy(details = details, updatedAt = updatedAt) }

    override suspend fun updateSalePrice(id: Long, salePrice: Money, updatedAt: Instant) =
        change(id) { it.copy(salePrice = salePrice, updatedAt = updatedAt) }

    override suspend fun updateInitialCost(id: Long, initialCost: Money, updatedAt: Instant) {
        // Como la base: con filas en el libro, el costo inicial ya no cambia.
        check(database.read().movements.values.none { it.movement.key.productId == id }) {
            "El costo inicial cambió con filas en el libro"
        }
        change(id) {
            it.copy(initialCost = initialCost, averageCost = initialCost, updatedAt = updatedAt)
        }
    }

    override suspend fun updateReplacementCost(
        id: Long,
        replacementCost: Money,
        replacementCostAt: Instant?,
        updatedAt: Instant,
    ) = change(id) {
        it.copy(
            replacementCost = replacementCost,
            replacementCostAt = replacementCostAt,
            updatedAt = updatedAt,
        )
    }

    override suspend fun setPriceReviewSince(id: Long, since: Instant?, updatedAt: Instant) =
        change(id) { it.copy(priceReviewSince = since, updatedAt = updatedAt) }

    override suspend fun setArchivedAt(id: Long, archivedAt: Instant?, updatedAt: Instant) =
        change(id) { it.copy(archivedAt = archivedAt, updatedAt = updatedAt) }

    override suspend fun delete(id: Long) {
        // Como la base: con historia o con vínculos del catálogo, una llave lo impide.
        check(!hasHistory(id) && catalogLinks(id).isEmpty()) { "Una llave impide borrarlo" }
        database.write { tables ->
            tables.copy(
                products = tables.products - id,
                priceChanges = tables.priceChanges.filterValues { it.content.productId != id },
                alerts = tables.alerts.filterValues { it.productId != id },
            )
        }
    }

    private suspend fun active(): List<Product> =
        database.read().products.values.filter { !it.isArchived }.sortedBy { it.id }

    private fun change(id: Long, update: (Product) -> Product) {
        database.write { tables ->
            val product = checkNotNull(tables.products[id]) { "No existe el producto" }
            tables.copy(products = tables.products + (id to update(product)))
        }
    }

    private fun withStock(tables: Tables, product: Product): Product {
        val stock = tables.productStock[product.id] ?: return product
        return product.copy(
            onHand = stock.onHand,
            averageCost = stock.averageCost,
            stockValue = stock.stockValue,
        )
    }

    private companion object {
        const val TABLE = "products"
    }
}

/** Un [CategoryRepository] sobre [InMemoryDatabase]. */
internal class FakeCategoryRepository(private val database: InMemoryDatabase) : CategoryRepository {
    override fun observeAll(): Flow<List<Category>> = database.tables.map { tables ->
        tables.categories.values.sortedWith(
            compareBy<Category> { it.details.normalizedName }.thenBy { it.id },
        )
    }

    override suspend fun findById(id: Long): Category? = database.read().categories[id]

    override suspend fun findByNormalizedName(normalizedName: String): Category? =
        database.read().categories.values.firstOrNull {
            it.details.normalizedName == normalizedName
        }

    override suspend fun countProducts(id: Long): Int =
        database.read().products.values.count { it.details.categoryId == id }

    override suspend fun insert(details: CategoryDetails, createdAt: Instant): Long {
        val id = database.nextId(TABLE)
        val category = Category(id, details, createdAt, createdAt)
        database.write { it.copy(categories = it.categories + (id to category)) }
        return id
    }

    override suspend fun update(id: Long, details: CategoryDetails, updatedAt: Instant) {
        database.write { tables ->
            val category = checkNotNull(tables.categories[id]) { "No existe la categoría" }
            val updated = category.copy(details = details, updatedAt = updatedAt)
            tables.copy(categories = tables.categories + (id to updated))
        }
    }

    override suspend fun delete(id: Long) {
        // Como la base: sus productos quedan sin categoría en la misma sentencia.
        database.write { tables ->
            tables.copy(
                categories = tables.categories - id,
                products = tables.products.mapValues { (_, product) ->
                    if (product.details.categoryId == id) {
                        product.copy(details = product.details.copy(categoryId = null))
                    } else {
                        product
                    }
                },
            )
        }
    }

    private companion object {
        const val TABLE = "categories"
    }
}

/** Un [PriceHistoryRepository] sobre [InMemoryDatabase]. Solo crece. */
internal class FakePriceHistoryRepository(private val database: InMemoryDatabase) :
    PriceHistoryRepository {
    override fun observeByProduct(productId: Long): Flow<List<PriceChange>> =
        database.tables.map { tables -> ofProduct(tables, productId) }

    override suspend fun findByBatch(batchId: Uuid): List<PriceChange> =
        database.read().priceChanges.values.filter {
            it.content.batchId == batchId
        }.sortedBy { it.id }

    override suspend fun append(change: NewPriceChange): Long {
        val id = database.nextId(TABLE)
        database.write { it.copy(priceChanges = it.priceChanges + (id to change.recordedAs(id))) }
        return id
    }

    fun ofProduct(tables: Tables, productId: Long): List<PriceChange> =
        tables.priceChanges.values.filter { it.content.productId == productId }.sortedBy { it.id }

    private companion object {
        const val TABLE = "product_price_history"
    }
}

/** Un [LowStockAlertRepository] sobre [InMemoryDatabase]. */
internal class FakeLowStockAlertRepository(private val database: InMemoryDatabase) :
    LowStockAlertRepository {
    override fun observeActive(): Flow<List<LowStockAlert>> = database.tables.map { tables ->
        tables.alerts.values.filter { it.isActive }.sortedBy { it.id }
    }

    override suspend fun findActiveByProducts(productIds: Collection<Long>): List<LowStockAlert> =
        database.read().alerts.values.filter { it.isActive && it.productId in productIds }

    override suspend fun findPendingNotifications(): List<LowStockAlert> =
        database.read().alerts.values
            .filter { it.notificationStatus == LowStockNotificationStatus.PENDING }
            .sortedBy { it.id }

    override suspend fun insert(alert: NewLowStockAlert): Long {
        check(findActiveByProducts(listOf(alert.productId)).isEmpty()) { "Dos alertas activas" }
        val id = database.nextId(TABLE)
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
        database.write { it.copy(alerts = it.alerts + (id to stored)) }
        return id
    }

    override suspend fun resolve(id: Long, resolvedAt: Instant) {
        database.write { tables ->
            val alert = checkNotNull(tables.alerts[id]) { "No existe la alerta" }
            tables.copy(alerts = tables.alerts + (id to alert.copy(resolvedAt = resolvedAt)))
        }
    }

    // Fuera de una transacción, como en la base: no es un dato del negocio.
    override suspend fun setNotificationStatus(id: Long, status: LowStockNotificationStatus) =
        database.seed { tables ->
            val alert = checkNotNull(tables.alerts[id]) { "No existe la alerta" }
            tables.copy(alerts = tables.alerts + (id to alert.copy(notificationStatus = status)))
        }

    private companion object {
        const val TABLE = "low_stock_alerts"
    }
}

/**
 * Un [SupplierRepository] sobre [InMemoryDatabase]. Su historia son sus compras, como en la base,
 * donde también cuentan devoluciones y libro. Borrarlo deja sin proveedor habitual a sus productos.
 */
internal class FakeSupplierRepository(private val database: InMemoryDatabase) : SupplierRepository {
    override fun observe(filter: ArchiveFilter): Flow<List<Supplier>> =
        database.tables.map { tables ->
            tables.suppliers.values
                .filter { it.isArchived == (filter == ArchiveFilter.ARCHIVED) }
                .sortedWith(compareBy<Supplier> { it.details.normalizedName }.thenBy { it.id })
        }

    override fun observeById(id: Long): Flow<Supplier?> =
        database.tables.map { tables -> tables.suppliers[id] }

    override suspend fun findById(id: Long): Supplier? = database.read().suppliers[id]

    override suspend fun findActiveByNormalizedName(normalizedName: String): Supplier? =
        database.read().suppliers.values.firstOrNull {
            !it.isArchived && it.details.normalizedName == normalizedName
        }

    override suspend fun hasHistory(id: Long): Boolean =
        database.read().purchases.values.any { it.content.supplierId == id }

    override suspend fun insert(details: SupplierDetails, createdAt: Instant): Long {
        val id = database.nextId(TABLE)
        val stored = Supplier(id, details, Money.ZERO, null, createdAt, createdAt)
        database.write { it.copy(suppliers = it.suppliers + (id to stored)) }
        return id
    }

    override suspend fun update(id: Long, details: SupplierDetails, updatedAt: Instant) =
        change(id) { it.copy(details = details, updatedAt = updatedAt) }

    override suspend fun setArchivedAt(id: Long, archivedAt: Instant?, updatedAt: Instant) =
        change(id) { it.copy(archivedAt = archivedAt, updatedAt = updatedAt) }

    override suspend fun delete(id: Long) {
        // Como la base: con historia, una llave lo impide.
        check(!hasHistory(id)) { "Una llave impide borrarlo" }
        database.write { tables ->
            checkNotNull(tables.suppliers[id]) { "No existe el proveedor" }
            tables.copy(
                suppliers = tables.suppliers - id,
                products = tables.products.mapValues { (_, product) ->
                    if (product.details.defaultSupplierId == id) {
                        product.copy(details = product.details.copy(defaultSupplierId = null))
                    } else {
                        product
                    }
                },
            )
        }
    }

    private fun change(id: Long, update: (Supplier) -> Supplier) {
        database.write { tables ->
            val supplier = checkNotNull(tables.suppliers[id]) { "No existe el proveedor" }
            tables.copy(suppliers = tables.suppliers + (id to update(supplier)))
        }
    }

    private companion object {
        const val TABLE = "suppliers"
    }
}

/** Un [UnitRepository] que lee lo sembrado. */
internal class FakeUnitRepository(private val database: InMemoryDatabase) : UnitRepository {
    override fun observeActive(): Flow<List<MeasureUnit>> = database.tables.map { tables ->
        tables.units.values.filter { !it.isArchived }.sortedBy { it.id }
    }

    override fun observeAll(): Flow<List<MeasureUnit>> =
        database.tables.map { tables -> tables.units.values.sortedBy { it.id } }

    override suspend fun findById(id: Long): MeasureUnit? = database.read().units[id]
}

/** Un [SystemRowRepository] que lee lo sembrado. */
internal class FakeSystemRowRepository(private val database: InMemoryDatabase) :
    SystemRowRepository {
    override suspend fun findId(row: SystemRow): Long? = database.read().systemRows[row]
}
