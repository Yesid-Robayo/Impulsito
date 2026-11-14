package io.github.yesidrobayo.shopledger.domain.testing

import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.stock.Location
import io.github.yesidrobayo.shopledger.domain.model.stock.LocationDetails
import io.github.yesidrobayo.shopledger.domain.model.stock.LocationKind
import io.github.yesidrobayo.shopledger.domain.model.stock.NewLocation
import io.github.yesidrobayo.shopledger.domain.port.LocationRepository
import io.github.yesidrobayo.shopledger.domain.port.LocationStockSource
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Un [LocationRepository] sobre [InMemoryDatabase]. Repite lo que la base y su guarda impiden: dos
 * vigentes con el mismo nombre, y archivar la inicial.
 */
internal class FakeLocationRepository(private val database: InMemoryDatabase) :
    LocationRepository {
    override fun observe(filter: ArchiveFilter): Flow<List<Location>> = database.tables
        .map { tables ->
            tables.locations.values
                .filter { it.isArchived == (filter == ArchiveFilter.ARCHIVED) }
                .sortedBy { it.id }
        }.distinctUntilChanged()

    override suspend fun findById(id: Long): Location? = database.read().locations[id]

    override suspend fun findActiveByNormalizedName(normalizedName: String): Location? =
        database.read().locations.values.firstOrNull {
            !it.isArchived && it.details.normalizedName == normalizedName
        }

    override suspend fun insert(location: NewLocation): Long {
        location.details.normalizedName?.let { check(findActiveByNormalizedName(it) == null) }
        val id = database.nextId(TABLE)
        val row = Location(
            id = id,
            systemKey = null,
            details = location.details,
            archivedAt = null,
            createdAt = location.createdAt,
            updatedAt = location.createdAt,
        )
        database.write { it.copy(locations = it.locations + (id to row)) }
        return id
    }

    override suspend fun update(id: Long, details: LocationDetails, updatedAt: Instant) {
        val location = checkNotNull(database.read().locations[id]) { "La ubicación no existe" }
        database.write {
            it.copy(
                locations = it.locations +
                    (id to location.copy(details = details, updatedAt = updatedAt)),
            )
        }
    }

    override suspend fun setArchivedAt(id: Long, archivedAt: Instant?, updatedAt: Instant) {
        val location = checkNotNull(database.read().locations[id]) { "La ubicación no existe" }
        check(!location.isInitial) { "La ubicación inicial nunca se archiva" }
        database.write {
            it.copy(
                locations = it.locations +
                    (id to location.copy(archivedAt = archivedAt, updatedAt = updatedAt)),
            )
        }
    }

    /** La última venta no se modela aquí: la siembra la prueba que la necesita. */
    var lastSaleLocationId: Long? = null

    override fun observeLastSaleLocationId(): Flow<Long?> =
        database.tables.map { lastSaleLocationId }.distinctUntilChanged()

    /** Siembra la ubicación inicial, sin nombre propio, como la crea la base al nacer. */
    fun seedInitial(at: Instant): Long {
        val id = 1L
        database.seed {
            it.copy(
                locations = it.locations + (
                    id to Location(
                        id = id,
                        systemKey = INITIAL_KEY,
                        details = LocationDetails(null, null, LocationKind.STORE),
                        archivedAt = null,
                        createdAt = at,
                        updatedAt = at,
                    )
                    ),
                lastIds = it.lastIds + (TABLE to id),
            )
        }
        return id
    }

    companion object {
        const val TABLE: String = "locations"
        const val INITIAL_KEY: String = "MAIN"
    }
}

/**
 * Un [LocationStockSource] sobre los saldos de [InMemoryDatabase], que el libro falso suma en cada
 * fila, como la base.
 */
internal class FakeLocationStockSource(private val database: InMemoryDatabase) :
    LocationStockSource {
    override fun observeProduct(productId: Long): Flow<Map<Long, QuantityMilli>> = database.tables
        .map { tables ->
            tables.stockBalances.entries
                .filter { it.key.productId == productId }
                .groupBy({ it.key.locationId }, { it.value })
                .mapValues { (_, parts) -> parts.fold(QuantityMilli.ZERO, QuantityMilli::plus) }
        }.distinctUntilChanged()

    override fun observeStockedProductCounts(): Flow<Map<Long, Int>> = database.tables
        .map { tables ->
            tables.stockBalances.entries
                .filter { tables.products[it.key.productId]?.isArchived != true }
                .groupBy({ it.key.locationId to it.key.productId }, { it.value })
                .filterValues { parts ->
                    parts.fold(QuantityMilli.ZERO, QuantityMilli::plus) > QuantityMilli.ZERO
                }
                .keys
                .groupingBy { (location, _) -> location }
                .eachCount()
        }.distinctUntilChanged()

    override fun observeLocation(locationId: Long): Flow<Map<Long, QuantityMilli>> =
        database.tables.map { tables ->
            tables.stockBalances.entries
                .filter { it.key.locationId == locationId }
                .groupBy({ it.key.productId }, { it.value })
                .mapValues { (_, parts) -> parts.fold(QuantityMilli.ZERO, QuantityMilli::plus) }
        }.distinctUntilChanged()

    override suspend fun onHandAt(productId: Long, locationId: Long): QuantityMilli =
        database.read().stockBalances.entries
            .filter { it.key.productId == productId && it.key.locationId == locationId }
            .fold(QuantityMilli.ZERO) { sum, part -> sum + part.value }

    override suspend fun hasStock(locationId: Long): Boolean =
        database.read().stockBalances.any { (key, onHand) ->
            key.locationId == locationId && onHand != QuantityMilli.ZERO
        }
}
