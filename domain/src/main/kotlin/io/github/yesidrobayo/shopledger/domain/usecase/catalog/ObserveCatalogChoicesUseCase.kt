package io.github.yesidrobayo.shopledger.domain.usecase.catalog

import io.github.yesidrobayo.shopledger.domain.model.catalog.CatalogChoices
import io.github.yesidrobayo.shopledger.domain.port.CategoryRepository
import io.github.yesidrobayo.shopledger.domain.port.UnitRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Las categorías, por nombre normalizado, y las unidades vigentes, en el orden en que se crearon.
 * Vuelve a emitir con cada cambio confirmado, así que una categoría creada desde el formulario
 * aparece sola en la hoja. No escribe nada.
 */
class ObserveCatalogChoicesUseCase(
    private val categories: CategoryRepository,
    private val units: UnitRepository,
) {
    operator fun invoke(): Flow<CatalogChoices> =
        combine(categories.observeAll(), units.observeActive(), ::CatalogChoices)
}
