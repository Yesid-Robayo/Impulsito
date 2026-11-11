package io.github.yesidrobayo.shopledger.domain.usecase.catalog

import io.github.yesidrobayo.shopledger.domain.model.catalog.UnitsOverview
import io.github.yesidrobayo.shopledger.domain.port.UnitCatalogRepository
import io.github.yesidrobayo.shopledger.domain.port.UnitRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Las unidades de su pantalla: vigentes, archivadas y cuáles tienen uso, en el orden en que se
 * crearon. Vuelve a emitir con cada cambio confirmado de unidades, productos, conversiones o líneas.
 */
class ObserveUnitsUseCase(
    private val units: UnitRepository,
    private val catalog: UnitCatalogRepository,
) {
    operator fun invoke(): Flow<UnitsOverview> =
        combine(units.observeAll(), catalog.observeInUse()) { all, inUse ->
            UnitsOverview(
                active = all.filterNot { it.isArchived },
                archived = all.filter { it.isArchived },
                inUse = inUse,
            )
        }
}
