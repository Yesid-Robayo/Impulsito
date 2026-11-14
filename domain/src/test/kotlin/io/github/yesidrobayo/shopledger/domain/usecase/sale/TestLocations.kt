package io.github.yesidrobayo.shopledger.domain.usecase.sale

import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.model.stock.Location
import io.github.yesidrobayo.shopledger.domain.model.stock.LocationDetails
import io.github.yesidrobayo.shopledger.domain.model.stock.LocationKind
import io.github.yesidrobayo.shopledger.domain.model.stock.NewLocation
import io.github.yesidrobayo.shopledger.domain.port.LocationRepository
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow

/** Las ubicaciones de una prueba de venta o de compra: los casos de uso solo las buscan por id. */
internal class TestLocations(rows: List<Location>) : LocationRepository {
    private val byId = rows.associateBy { it.id }

    override fun observe(filter: ArchiveFilter): Flow<List<Location>> = unused()

    override suspend fun findById(id: Long): Location? = byId[id]

    override suspend fun findActiveByNormalizedName(normalizedName: String): Location? = unused()

    override suspend fun insert(location: NewLocation): Long = unused()

    override suspend fun update(id: Long, details: LocationDetails, updatedAt: Instant) = unused()

    override suspend fun setArchivedAt(id: Long, archivedAt: Instant?, updatedAt: Instant) =
        unused()

    override fun observeLastSaleLocationId(): Flow<Long?> = unused()

    private fun unused(): Nothing = error("Estas pruebas no usan esto")
}

/** Una ubicación inventada, vigente o archivada. */
internal fun testLocation(id: Long, archived: Boolean) = Location(
    id = id,
    systemKey = null,
    details = LocationDetails("Lugar $id", "lugar $id", LocationKind.STALL),
    archivedAt = if (archived) SaleWorld.EPOCH else null,
    createdAt = SaleWorld.EPOCH,
    updatedAt = SaleWorld.EPOCH,
)
