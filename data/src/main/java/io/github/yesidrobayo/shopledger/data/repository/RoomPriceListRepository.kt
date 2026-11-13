package io.github.yesidrobayo.shopledger.data.repository

import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.data.local.pricing.PriceListEntity
import io.github.yesidrobayo.shopledger.data.local.pricing.ProductPriceEntity
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceList
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceListDetails
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceListKind
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceListPricing
import io.github.yesidrobayo.shopledger.domain.port.PriceListRepository
import io.github.yesidrobayo.shopledger.domain.port.ProductPriceRepository
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Las listas de precios sobre `price_lists`, y los precios propios de cada producto sobre
 * `product_prices`.
 *
 * Una relativa guarda su base y su ajuste en dos columnas que van juntas: el tipo se deduce de
 * ellas, y una fila que no cuadra es una fila rota. La inicial nunca se archiva ni se borra: su
 * guarda la protege, así que pedirlo deja una escritura rechazada, que aquí es un error de
 * programación.
 */
internal class RoomPriceListRepository(database: AppDatabase) :
    PriceListRepository,
    ProductPriceRepository {
    private val lists = database.priceListDao()
    private val prices = database.productPriceDao()
    private val writes = RepositoryWrites(database)

    // Room vuelve a emitir con cualquier cambio de la tabla: una lista igual no se repite.
    override fun observeActive(): Flow<List<PriceList>> =
        lists.observeActive().map { rows -> rows.map(::toPriceList) }.distinctUntilChanged()

    override fun observeArchived(): Flow<List<PriceList>> =
        lists.observeArchived().map { rows -> rows.map(::toPriceList) }.distinctUntilChanged()

    override fun observeInUse(): Flow<Set<Long>> =
        lists.observeInUseIds().map { it.toSet() }.distinctUntilChanged()

    override fun observeCustomerCounts(): Flow<Map<Long, Int>> = lists
        .observeCustomerCounts()
        .map { rows -> rows.associate { it.ownerId to it.count } }
        .distinctUntilChanged()

    override suspend fun findById(id: Long): PriceList? = lists.findById(id)?.let(::toPriceList)

    override suspend fun findAll(): List<PriceList> = lists.findAll().map(::toPriceList)

    override suspend fun findActiveByNormalizedName(normalizedName: String): PriceList? =
        lists.findActiveByNormalizedName(normalizedName)?.let(::toPriceList)

    override suspend fun wouldCreateCycle(id: Long, baseId: Long): Boolean =
        lists.wouldCreateCycle(id, baseId)

    override suspend fun isInUse(id: Long): Boolean = lists.isInUse(id)

    override suspend fun insert(details: PriceListDetails, createdAt: Instant): Long =
        writes.inTransaction {
            val at = createdAt.toStoredMillis()
            val relative = details.pricing as? PriceListPricing.Relative
            lists.insert(
                PriceListEntity(
                    systemKey = null,
                    name = details.name,
                    normalizedName = details.normalizedName,
                    kind = details.pricing.kind.name,
                    basePriceListId = relative?.baseId,
                    adjustmentBp = relative?.adjustment?.bp,
                    archivedAt = null,
                    createdAt = at,
                    updatedAt = at,
                ),
            )
        }

    override suspend fun update(id: Long, details: PriceListDetails, updatedAt: Instant): Boolean =
        writes.inTransaction {
            val relative = details.pricing as? PriceListPricing.Relative
            // La sentencia no cambia nada si la nueva base formaría un ciclo, o si la lista no existe.
            val changed = lists.update(
                id = id,
                name = details.name,
                normalizedName = details.normalizedName,
                kind = details.pricing.kind.name,
                basePriceListId = relative?.baseId,
                adjustmentBp = relative?.adjustment?.bp,
                updatedAt = updatedAt.toStoredMillis(),
            )
            changed == 1
        }

    override suspend fun setArchivedAt(id: Long, archivedAt: Instant?, updatedAt: Instant) {
        writes.inTransaction {
            requireOneRow(
                lists.setArchivedAt(
                    id = id,
                    archivedAt = archivedAt?.toStoredMillis(),
                    updatedAt = updatedAt.toStoredMillis(),
                ),
            )
        }
    }

    override suspend fun delete(id: Long) {
        writes.inTransaction { requireOneRow(lists.delete(id)) }
    }

    override fun observeByList(priceListId: Long): Flow<Map<Long, Money>> = prices
        .observeByPriceList(priceListId)
        .map { rows -> rows.associate { it.productId to Money(it.price) } }
        .distinctUntilChanged()

    override fun observeAll(): Flow<Map<Long, Map<Long, Money>>> = prices
        .observeAll()
        .map { rows ->
            rows.groupBy { it.priceListId }
                .mapValues { (_, ofList) -> ofList.associate { it.productId to Money(it.price) } }
        }
        .distinctUntilChanged()

    override suspend fun findByProduct(productId: Long): Map<Long, Money> =
        prices.findByProduct(productId).associate { it.priceListId to Money(it.price) }

    override suspend fun find(priceListId: Long, productId: Long): Money? =
        prices.find(priceListId, productId)?.let { Money(it.price) }

    override suspend fun hasAny(priceListId: Long): Boolean = prices.hasAny(priceListId)

    override suspend fun insert(priceListId: Long, productId: Long, price: Money, at: Instant) {
        writes.inTransaction {
            val stored = at.toStoredMillis()
            prices.insert(
                ProductPriceEntity(
                    priceListId = priceListId,
                    productId = productId,
                    price = price.minor,
                    createdAt = stored,
                    updatedAt = stored,
                ),
            )
        }
    }

    override suspend fun update(priceListId: Long, productId: Long, price: Money, at: Instant) {
        writes.inTransaction {
            requireOneRow(
                prices.updatePrice(priceListId, productId, price.minor, at.toStoredMillis()),
            )
        }
    }

    override suspend fun delete(priceListId: Long, productId: Long) {
        writes.inTransaction { requireOneRow(prices.delete(priceListId, productId)) }
    }

    private fun toPriceList(row: PriceListEntity): PriceList {
        val base = row.basePriceListId
        val adjustment = row.adjustmentBp
        val pricing =
            when (storedEnum<PriceListKind>(row.kind)) {
                PriceListKind.FIXED ->
                    if (base == null && adjustment == null) PriceListPricing.Fixed else brokenRow()

                PriceListKind.RELATIVE ->
                    if (base != null && adjustment != null) {
                        PriceListPricing.Relative(base, BasisPoints(adjustment))
                    } else {
                        brokenRow()
                    }
            }
        return PriceList(
            id = row.id,
            systemKey = row.systemKey,
            name = row.name,
            pricing = pricing,
            archivedAt = row.archivedAt?.toInstant(),
        )
    }
}
