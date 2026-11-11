package io.github.yesidrobayo.shopledger.data.repository

import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.data.local.catalog.UnitEntity
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.catalog.UnitDetails
import io.github.yesidrobayo.shopledger.domain.model.catalog.UnitKind
import io.github.yesidrobayo.shopledger.domain.port.UnitCatalogRepository
import io.github.yesidrobayo.shopledger.domain.port.UnitRepository
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Las unidades sobre `units`: las lecturas de todos los días y lo que escribe su pantalla.
 *
 * La de clave `UNIT` nunca se archiva ni se borra: el DAO la excluye y la guarda la protege, así que
 * pedirlo deja una escritura sin filas, que aquí es un error de programación.
 */
internal class RoomUnitRepository(database: AppDatabase) :
    UnitRepository,
    UnitCatalogRepository {
    private val units = database.unitDao()
    private val writes = RepositoryWrites(database)

    // Room vuelve a emitir con cualquier cambio de la tabla: una lista igual no se repite.
    override fun observeActive(): Flow<List<MeasureUnit>> =
        units.observeActive().map { rows -> rows.map(::toUnit) }.distinctUntilChanged()

    override fun observeAll(): Flow<List<MeasureUnit>> =
        combine(units.observeActive(), units.observeArchived()) { active, archived ->
            (active + archived).sortedBy { it.id }.map(::toUnit)
        }.distinctUntilChanged()

    override fun observeInUse(): Flow<Set<Long>> =
        units.observeInUseIds().map { it.toSet() }.distinctUntilChanged()

    override suspend fun findById(id: Long): MeasureUnit? = units.findById(id)?.let(::toUnit)

    override suspend fun findActiveByNormalizedName(normalizedName: String): MeasureUnit? =
        units.findActiveByNormalizedName(normalizedName)?.let(::toUnit)

    override suspend fun isInUse(id: Long): Boolean = units.isInUse(id)

    override suspend fun insert(details: UnitDetails, createdAt: Instant): Long =
        writes.inTransaction {
            val at = createdAt.toStoredMillis()
            units.insert(
                UnitEntity(
                    systemKey = null,
                    name = details.name,
                    normalizedName = details.normalizedName,
                    symbol = details.symbol,
                    kind = details.kind.name,
                    allowsFraction = details.allowsFraction.toStoredFlag(),
                    archivedAt = null,
                    createdAt = at,
                    updatedAt = at,
                ),
            )
        }

    override suspend fun update(id: Long, details: UnitDetails, updatedAt: Instant) {
        writes.inTransaction {
            requireOneRow(
                units.update(
                    id = id,
                    name = details.name,
                    normalizedName = details.normalizedName,
                    symbol = details.symbol,
                    kind = details.kind.name,
                    allowsFraction = details.allowsFraction.toStoredFlag(),
                    updatedAt = updatedAt.toStoredMillis(),
                ),
            )
        }
    }

    override suspend fun setArchivedAt(id: Long, archivedAt: Instant?, updatedAt: Instant) {
        writes.inTransaction {
            requireOneRow(
                units.setArchivedAt(
                    id = id,
                    archivedAt = archivedAt?.toStoredMillis(),
                    updatedAt = updatedAt.toStoredMillis(),
                ),
            )
        }
    }

    override suspend fun delete(id: Long) {
        writes.inTransaction { requireOneRow(units.delete(id)) }
    }

    private fun toUnit(row: UnitEntity): MeasureUnit = MeasureUnit(
        id = row.id,
        systemKey = row.systemKey,
        name = row.name,
        symbol = row.symbol,
        kind = storedEnum<UnitKind>(row.kind),
        allowsFraction = row.allowsFraction.toFlag(),
        archivedAt = row.archivedAt?.toInstant(),
    )
}
