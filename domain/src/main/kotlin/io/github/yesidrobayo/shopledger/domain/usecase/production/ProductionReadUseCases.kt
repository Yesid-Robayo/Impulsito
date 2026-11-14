package io.github.yesidrobayo.shopledger.domain.usecase.production

import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.catalog.RecipeComponent
import io.github.yesidrobayo.shopledger.domain.model.production.ProductionBatch
import io.github.yesidrobayo.shopledger.domain.model.production.ProductionBatchLine
import io.github.yesidrobayo.shopledger.domain.port.ProductComponentRepository
import io.github.yesidrobayo.shopledger.domain.port.ProductRepository
import io.github.yesidrobayo.shopledger.domain.port.ProductionBatchRepository
import io.github.yesidrobayo.shopledger.domain.port.UnitRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Lo que necesitan las pantallas de producción: todos los productos, vigentes y archivados, todas
 * las recetas y todas las unidades, por id. De aquí salen los productos que se pueden producir,
 * los insumos de cada uno con su stock y su costo de este momento, y los nombres de lo ya
 * producido.
 */
data class ProductionCatalog(
    val products: Map<Long, Product>,
    val recipes: Map<Long, List<RecipeComponent>>,
    val units: Map<Long, MeasureUnit>,
) {
    /**
     * Los productos que se pueden producir hoy, por nombre: con stock propio, sin lotes, vigentes
     * y con receta. Un combo no aparece: se arma al venderse, sin libro propio.
     */
    val producible: List<Product>
        get() = products.values
            .filter { product ->
                product.details.stockMode == ProductStockMode.TRACKED &&
                    !product.details.tracksLots &&
                    !product.isArchived &&
                    recipes[product.id].orEmpty().isNotEmpty()
            }
            .sortedWith(compareBy<Product> { it.details.normalizedName }.thenBy { it.id })

    /** Si algún producto tiene receta, aunque hoy no se pueda producir. */
    val hasRecipes: Boolean get() = recipes.values.any { it.isNotEmpty() }

    /** El modo de stock de cada producto, como lo pide la expansión de la receta. */
    val modes: Map<Long, ProductStockMode>
        get() = products.mapValues { it.value.details.stockMode }
}

/** Una producción con sus insumos, para el detalle. */
data class ProductionDetail(val batch: ProductionBatch, val lines: List<ProductionBatchLine>)

/** El detalle de la producción [batchId], de nuevo cuando se anula. `null` si no existe. */
class ObserveProductionDetailUseCase(private val batches: ProductionBatchRepository) {
    operator fun invoke(batchId: Long): Flow<ProductionDetail?> = combine(
        batches.observeById(batchId),
        batches.observeLines(batchId),
    ) { batch, lines -> batch?.let { ProductionDetail(it, lines) } }
}

/**
 * Los productos, las recetas y las unidades que necesita producir, de nuevo con cada cambio: una
 * compra de un insumo cambia lo que hay y lo que cuesta.
 */
class ObserveProductionCatalogUseCase(
    private val products: ProductRepository,
    private val components: ProductComponentRepository,
    private val units: UnitRepository,
) {
    operator fun invoke(): Flow<ProductionCatalog> = combine(
        products.observe(ArchiveFilter.ACTIVE),
        products.observe(ArchiveFilter.ARCHIVED),
        components.observeAll(),
        units.observeAll(),
    ) { active, archived, stored, allUnits ->
        ProductionCatalog(
            products = (active + archived).associateBy { it.id },
            recipes = stored.groupBy { it.parentProductId },
            units = allUnits.associateBy { it.id },
        )
    }
}
