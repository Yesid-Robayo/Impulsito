package io.github.yesidrobayo.shopledger.domain.testing

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceList
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceListDetails
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceListPricing
import io.github.yesidrobayo.shopledger.domain.port.PriceListRepository
import io.github.yesidrobayo.shopledger.domain.port.ProductPriceRepository
import io.github.yesidrobayo.shopledger.domain.rule.TextNormalizer
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Un [PriceListRepository] y un [ProductPriceRepository] sobre [InMemoryDatabase], con las mismas
 * reglas que la base: la cadena de bases nunca vuelve a la misma lista, y una escritura que no
 * encuentra su fila falla.
 */
internal class FakePriceListRepository(private val database: InMemoryDatabase) :
    PriceListRepository,
    ProductPriceRepository {
    override fun observeActive(): Flow<List<PriceList>> = database.tables.map { tables ->
        tables.priceLists.values.filterNot { it.isArchived }.sortedBy { it.id }
    }

    override fun observeArchived(): Flow<List<PriceList>> = database.tables.map { tables ->
        tables.priceLists.values.filter { it.isArchived }.sortedBy { it.id }
    }

    override fun observeInUse(): Flow<Set<Long>> = database.tables.map { tables ->
        tables.priceLists.keys.filter { inUse(tables, it) }.toSet()
    }

    override fun observeCustomerCounts(): Flow<Map<Long, Int>> = database.tables.map { tables ->
        tables.customers.values
            .filter { !it.isArchived }
            .mapNotNull { it.priceListId }
            .groupingBy { it }
            .eachCount()
    }

    override suspend fun findById(id: Long): PriceList? = database.read().priceLists[id]

    override suspend fun findAll(): List<PriceList> =
        database.read().priceLists.values.sortedBy { it.id }

    override suspend fun findActiveByNormalizedName(normalizedName: String): PriceList? =
        database.read().priceLists.values.firstOrNull { list ->
            !list.isArchived && list.name?.let(TextNormalizer::normalize) == normalizedName
        }

    override suspend fun wouldCreateCycle(id: Long, baseId: Long): Boolean =
        chainOf(database.read(), baseId).contains(id)

    override suspend fun isInUse(id: Long): Boolean = inUse(database.read(), id)

    override suspend fun insert(details: PriceListDetails, createdAt: Instant): Long {
        val id = database.nextId(LISTS)
        val list = PriceList(id, systemKey = null, details.name, details.pricing, archivedAt = null)
        database.write { it.copy(priceLists = it.priceLists + (id to list)) }
        return id
    }

    override suspend fun update(id: Long, details: PriceListDetails, updatedAt: Instant): Boolean {
        val tables = database.read()
        val current = checkNotNull(tables.priceLists[id]) { "Lista inexistente" }
        val base = (details.pricing as? PriceListPricing.Relative)?.baseId
        if (base != null && chainOf(tables, base).contains(id)) return false
        val updated = current.copy(name = details.name, pricing = details.pricing)
        database.write { it.copy(priceLists = it.priceLists + (id to updated)) }
        return true
    }

    override suspend fun setArchivedAt(id: Long, archivedAt: Instant?, updatedAt: Instant) {
        val current = checkNotNull(database.read().priceLists[id]) { "Lista inexistente" }
        check(!current.isDefault) { "La inicial no se archiva" }
        database.write {
            it.copy(priceLists = it.priceLists + (id to current.copy(archivedAt = archivedAt)))
        }
    }

    override suspend fun delete(id: Long) {
        check(!inUse(database.read(), id)) { "Una lista con uso no se borra" }
        database.write { it.copy(priceLists = it.priceLists - id) }
    }

    override fun observeByList(priceListId: Long): Flow<Map<Long, Money>> =
        database.tables.map { tables ->
            tables.productPrices.filterKeys { it.first == priceListId }
                .mapKeys { it.key.second }
        }

    override fun observeAll(): Flow<Map<Long, Map<Long, Money>>> = database.tables.map { tables ->
        tables.productPrices.entries
            .groupBy({ it.key.first }, { it.key.second to it.value })
            .mapValues { (_, prices) -> prices.toMap() }
    }

    override suspend fun findByProduct(productId: Long): Map<Long, Money> =
        database.read().productPrices.filterKeys { it.second == productId }.mapKeys { it.key.first }

    override suspend fun find(priceListId: Long, productId: Long): Money? =
        database.read().productPrices[priceListId to productId]

    override suspend fun hasAny(priceListId: Long): Boolean =
        database.read().productPrices.keys.any { it.first == priceListId }

    override suspend fun insert(priceListId: Long, productId: Long, price: Money, at: Instant) {
        val key = priceListId to productId
        check(key !in database.read().productPrices) { "Ya tiene precio en esa lista" }
        database.write { it.copy(productPrices = it.productPrices + (key to price)) }
    }

    override suspend fun update(priceListId: Long, productId: Long, price: Money, at: Instant) {
        val key = priceListId to productId
        check(key in database.read().productPrices) { "No tiene precio en esa lista" }
        database.write { it.copy(productPrices = it.productPrices + (key to price)) }
    }

    override suspend fun delete(priceListId: Long, productId: Long) {
        val key = priceListId to productId
        check(key in database.read().productPrices) { "No tiene precio en esa lista" }
        database.write { it.copy(productPrices = it.productPrices - key) }
    }

    /** La cadena de bases desde [start], ella incluida, sin repetir. */
    private fun chainOf(tables: Tables, start: Long): Set<Long> {
        val chain = linkedSetOf<Long>()
        var next: Long? = start
        while (next != null && chain.add(next)) {
            next = (tables.priceLists[next]?.pricing as? PriceListPricing.Relative)?.baseId
        }
        return chain
    }

    private fun inUse(tables: Tables, id: Long): Boolean = id in tables.priceListsUsedBySales ||
        tables.customers.values.any { it.priceListId == id } ||
        tables.priceLists.values.any {
            (it.pricing as? PriceListPricing.Relative)?.baseId == id
        } ||
        tables.productPrices.keys.any { it.first == id } ||
        tables.priceChanges.values.any { it.content.priceListId == id }

    private companion object {
        const val LISTS = "price_lists"
    }
}
