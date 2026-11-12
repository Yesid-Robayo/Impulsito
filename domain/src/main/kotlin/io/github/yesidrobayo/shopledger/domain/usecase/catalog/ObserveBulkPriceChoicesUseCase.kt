package io.github.yesidrobayo.shopledger.domain.usecase.catalog

import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.catalog.Category
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.Supplier
import io.github.yesidrobayo.shopledger.domain.port.AppSettingsRepository
import io.github.yesidrobayo.shopledger.domain.port.CategoryRepository
import io.github.yesidrobayo.shopledger.domain.port.ProductRepository
import io.github.yesidrobayo.shopledger.domain.port.SupplierRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Lo que la actualización masiva lee para armar su vista previa: todo el catálogo, porque un
 * archivado del alcance se muestra excluido; las categorías y los proveedores vigentes que se pueden
 * elegir como alcance; y el paso de redondeo guardado. Cada lectura vuelve a emitir con cada cambio
 * confirmado.
 */
class ObserveBulkPriceChoicesUseCase(
    private val products: ProductRepository,
    private val categories: CategoryRepository,
    private val suppliers: SupplierRepository,
    private val settings: AppSettingsRepository,
) {
    fun catalog(): Flow<List<Product>> = combine(
        products.observe(ArchiveFilter.ACTIVE),
        products.observe(ArchiveFilter.ARCHIVED),
    ) { active, archived -> active + archived }

    fun categories(): Flow<List<Category>> = categories.observeAll()

    fun suppliers(): Flow<List<Supplier>> = suppliers.observe(ArchiveFilter.ACTIVE)

    /** El paso al que se redondean los precios por defecto; cero es sin redondeo. */
    fun defaultStep(): Flow<Money> =
        settings.observe().map { it.sales.defaultRoundingStep }.distinctUntilChanged()
}
