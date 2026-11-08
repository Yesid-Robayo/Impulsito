package io.github.yesidrobayo.shopledger.domain.usecase.catalog

import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductCatalog
import io.github.yesidrobayo.shopledger.domain.port.ProductRepository
import io.github.yesidrobayo.shopledger.domain.port.UnitRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * El catálogo que muestra una lista de productos: los productos de un filtro, en el orden de su
 * nombre normalizado, con todas las unidades. Vuelve a emitir con cada cambio confirmado del
 * catálogo o de las unidades, así que un producto guardado en otra pantalla aparece solo. No
 * escribe nada.
 */
class ObserveProductCatalogUseCase(
    private val products: ProductRepository,
    private val units: UnitRepository,
) {
    operator fun invoke(filter: ArchiveFilter = ArchiveFilter.ACTIVE): Flow<ProductCatalog> =
        combine(products.observe(filter), units.observeAll()) { list, allUnits ->
            ProductCatalog(list, allUnits.associateBy { it.id })
        }
}
