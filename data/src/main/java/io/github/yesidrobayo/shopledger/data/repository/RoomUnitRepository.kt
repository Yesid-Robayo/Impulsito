package io.github.yesidrobayo.shopledger.data.repository

import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.data.local.catalog.UnitEntity
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.catalog.UnitKind
import io.github.yesidrobayo.shopledger.domain.port.UnitRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Las unidades sobre `units`, solo para leer. */
internal class RoomUnitRepository(database: AppDatabase) : UnitRepository {
    private val units = database.unitDao()

    // Room vuelve a emitir con cualquier cambio de la tabla: una lista igual no se repite.
    override fun observeActive(): Flow<List<MeasureUnit>> =
        units.observeActive().map { rows -> rows.map(::toUnit) }.distinctUntilChanged()

    override fun observeAll(): Flow<List<MeasureUnit>> =
        combine(units.observeActive(), units.observeArchived()) { active, archived ->
            (active + archived).sortedBy { it.id }.map(::toUnit)
        }.distinctUntilChanged()

    override suspend fun findById(id: Long): MeasureUnit? = units.findById(id)?.let(::toUnit)

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
