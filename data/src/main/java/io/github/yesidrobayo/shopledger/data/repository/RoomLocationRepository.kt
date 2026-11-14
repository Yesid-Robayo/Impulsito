package io.github.yesidrobayo.shopledger.data.repository

import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.data.local.stock.LocationDao
import io.github.yesidrobayo.shopledger.data.local.stock.LocationEntity
import io.github.yesidrobayo.shopledger.data.local.stock.StockBalanceDao
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
 * Las ubicaciones sobre `locations`. El stock de cada una no se escribe aquí: lo suma el libro a
 * `stock_balances`, en la transacción que agrega cada fila.
 */
internal class RoomLocationRepository(
    private val locations: LocationDao,
    private val writes: RepositoryWrites,
) : LocationRepository {
    constructor(database: AppDatabase) : this(database.locationDao(), RepositoryWrites(database))

    override fun observe(filter: ArchiveFilter): Flow<List<Location>> = when (filter) {
        ArchiveFilter.ACTIVE -> locations.observeActive()
        ArchiveFilter.ARCHIVED -> locations.observeArchived()
    }.map { rows -> rows.map(::toLocation) }.distinctUntilChanged()

    override suspend fun findById(id: Long): Location? = locations.findById(id)?.let(::toLocation)

    override suspend fun findActiveByNormalizedName(normalizedName: String): Location? =
        locations.findActiveByNormalizedName(normalizedName)?.let(::toLocation)

    override suspend fun insert(location: NewLocation): Long = writes.inTransaction {
        val at = location.createdAt.toStoredMillis()
        locations.insert(
            LocationEntity(
                systemKey = null,
                name = location.details.name,
                normalizedName = location.details.normalizedName,
                kind = location.details.kind.name,
                archivedAt = null,
                createdAt = at,
                updatedAt = at,
            ),
        )
    }

    override suspend fun update(id: Long, details: LocationDetails, updatedAt: Instant) =
        writes.inTransaction {
            requireOneRow(
                locations.update(
                    id = id,
                    name = details.name,
                    normalizedName = details.normalizedName,
                    kind = details.kind.name,
                    updatedAt = updatedAt.toStoredMillis(),
                ),
            )
        }

    override suspend fun setArchivedAt(id: Long, archivedAt: Instant?, updatedAt: Instant) =
        writes.inTransaction {
            // La inicial queda fuera de la sentencia y devuelve 0: pedirlo es un error del caso de
            // uso, que la rechaza antes.
            requireOneRow(
                locations.setArchivedAt(
                    id = id,
                    archivedAt = archivedAt?.toStoredMillis(),
                    updatedAt = updatedAt.toStoredMillis(),
                ),
            )
        }
}

/** El stock por ubicación, leído de `stock_balances`, que el libro mantiene en cada escritura. */
internal class RoomLocationStockSource(private val balances: StockBalanceDao) :
    LocationStockSource {
    constructor(database: AppDatabase) : this(database.stockBalanceDao())

    override fun observeProduct(productId: Long): Flow<Map<Long, QuantityMilli>> =
        balances.observeProductByLocation(productId)
            .map { rows -> rows.associate { it.locationId to QuantityMilli(it.onHandMilli) } }
            .distinctUntilChanged()

    override fun observeStockedProductCounts(): Flow<Map<Long, Int>> =
        balances.observeStockedProductCounts()
            .map { rows -> rows.associate { it.locationId to Math.toIntExact(it.products) } }
            .distinctUntilChanged()

    override suspend fun hasStock(locationId: Long): Boolean = balances.hasNonZeroAt(locationId)
}

/** La ubicación guardada en [row]. Una clase que su guarda no admite es una fila rota. */
internal fun toLocation(row: LocationEntity): Location {
    if (row.name == null && row.systemKey == null) brokenRow()
    return Location(
        id = row.id,
        systemKey = row.systemKey,
        details = LocationDetails(
            name = row.name,
            normalizedName = row.normalizedName,
            kind = storedEnum<LocationKind>(row.kind),
        ),
        archivedAt = row.archivedAt?.toInstant(),
        createdAt = row.createdAt.toInstant(),
        updatedAt = row.updatedAt.toInstant(),
    )
}
