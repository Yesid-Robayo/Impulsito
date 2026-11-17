package io.github.yesidrobayo.shopledger.domain.usecase.production

import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.catalog.RecipeComponent
import io.github.yesidrobayo.shopledger.domain.model.production.ProductionBatch
import io.github.yesidrobayo.shopledger.domain.model.production.ProductionBatchLine
import io.github.yesidrobayo.shopledger.domain.model.stock.LotChoices
import io.github.yesidrobayo.shopledger.domain.model.stock.StockLot
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.ProductComponentRepository
import io.github.yesidrobayo.shopledger.domain.port.ProductRepository
import io.github.yesidrobayo.shopledger.domain.port.ProductionBatchRepository
import io.github.yesidrobayo.shopledger.domain.port.StockLotRepository
import io.github.yesidrobayo.shopledger.domain.port.UnitRepository
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.domain.usecase.stock.LoadLotChoicesUseCase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.datetime.DayOfWeek

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
     * Los productos que se pueden producir hoy, por nombre: con stock propio, vigentes y con
     * receta, también los que se controlan por lotes. Un combo no aparece: se arma al venderse,
     * sin libro propio.
     */
    val producible: List<Product>
        get() = products.values
            .filter { product ->
                product.details.stockMode == ProductStockMode.TRACKED &&
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

/**
 * Los id de lo que se puede producir hoy, con la misma regla que el formulario de producción, de
 * nuevo solo cuando cambian. Con ellos, reponer un producto con receta abre la producción y no una
 * compra: lo que el negocio hace no se compra. Lo leen el inventario, Inicio, la reposición
 * sugerida y el detalle del producto, para que todos ofrezcan lo mismo.
 */
class ObserveProducibleIdsUseCase(private val catalog: ObserveProductionCatalogUseCase) {
    operator fun invoke(): Flow<Set<Long>> = catalog()
        .map { read -> read.producible.mapTo(HashSet()) { it.id } }
        .distinctUntilChanged()
}

/**
 * Los lotes que necesita el formulario de una producción.
 *
 * [inputs] trae lo que se puede elegir de cada insumo con lotes, por producto, en la ubicación
 * principal, de donde salen. [outputLots] son los lotes con código de lo que se produce: un código
 * escrito que ya existe suma a ese lote, y la pantalla lo dice. [today] y [weekStartDay] sirven
 * para elegir el vencimiento de un lote nuevo; faltan si la zona del negocio no se reconoce.
 */
data class ProductionLots(
    val inputs: Map<Long, LotChoices> = emptyMap(),
    val outputLots: List<StockLot> = emptyList(),
    val today: BusinessDate? = null,
    val weekStartDay: DayOfWeek? = null,
)

/**
 * Lee los lotes de una producción: los de cada insumo con lotes y, si lo producido se controla por
 * lotes, los suyos con código. Quien no usa lotes no pide nada y recibe todo vacío.
 */
class LoadProductionLotsUseCase(
    private val lots: StockLotRepository,
    private val choices: LoadLotChoicesUseCase,
    private val calendar: BusinessCalendarSource,
    private val clock: Clock,
) {
    /** Los lotes de los insumos [inputIds] y, si [outputId] no es `null`, los de lo producido. */
    suspend operator fun invoke(outputId: Long?, inputIds: Set<Long>): ProductionLots {
        val ready = (calendar.current() as? BusinessCalendarResult.Ready)?.calendar
        val output = if (outputId == null) {
            emptyList()
        } else {
            lots.observeByProduct(outputId).first().filter { !it.isUnassigned }
        }
        return ProductionLots(
            inputs = inputIds.mapNotNull { id -> choices(id, null)?.let { id to it } }.toMap(),
            outputLots = output,
            today = ready?.businessDateOf(clock.now()),
            weekStartDay = ready?.weekStartDay,
        )
    }
}
