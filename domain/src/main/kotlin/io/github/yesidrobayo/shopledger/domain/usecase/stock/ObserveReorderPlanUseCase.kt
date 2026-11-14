package io.github.yesidrobayo.shopledger.domain.usecase.stock

import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.Category
import io.github.yesidrobayo.shopledger.domain.model.catalog.ConversionDetails
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.Supplier
import io.github.yesidrobayo.shopledger.domain.port.CategoryRepository
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.LocationStockSource
import io.github.yesidrobayo.shopledger.domain.port.ProductRepository
import io.github.yesidrobayo.shopledger.domain.port.ProductUnitRepository
import io.github.yesidrobayo.shopledger.domain.port.SaleOutflowSource
import io.github.yesidrobayo.shopledger.domain.port.SupplierRepository
import io.github.yesidrobayo.shopledger.domain.port.UnitRepository
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import io.github.yesidrobayo.shopledger.domain.rule.stock.ReorderPlan
import io.github.yesidrobayo.shopledger.domain.rule.stock.ReorderPlanner
import io.github.yesidrobayo.shopledger.domain.rule.stock.ReorderQuery
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

/**
 * La reposición sugerida con lo que la pantalla necesita para mostrarla: la lista, las unidades
 * para nombrar cantidades, y los proveedores y categorías vigentes que ofrecen sus filtros.
 */
data class ReorderOverview(
    val plan: ReorderPlan,
    val units: Map<Long, MeasureUnit>,
    val suppliers: List<Supplier>,
    val categories: List<Category>,
)

/**
 * Lee la reposición sugerida y vuelve a emitir con cada venta, compra o ajuste confirmado, y con
 * cada cambio del catálogo. Solo lee: no crea ninguna compra.
 *
 * La ventana termina hoy, en la fecha de negocio, y cuenta [ReorderQuery.windowDays] días con hoy
 * incluido. Si la zona del negocio no existe en este teléfono, no hay fecha de hoy: la lista sale
 * sin ventas, y solo trae lo que está bajo su mínimo. Con [locationId], las salidas y lo que hay
 * se miden en esa ubicación, contra el mismo mínimo del producto, como el inventario.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ObserveReorderPlanUseCase(
    private val products: ProductRepository,
    private val suppliers: SupplierRepository,
    private val categories: CategoryRepository,
    private val units: UnitRepository,
    private val productUnits: ProductUnitRepository,
    private val outflow: SaleOutflowSource,
    private val locationStock: LocationStockSource,
    private val calendar: BusinessCalendarSource,
    private val clock: Clock,
) {
    operator fun invoke(query: ReorderQuery, locationId: Long? = null): Flow<ReorderOverview> {
        val catalog = combine(
            products.observe(ArchiveFilter.ACTIVE),
            suppliers.observe(ArchiveFilter.ACTIVE),
            categories.observeAll(),
            units.observeAll(),
            productUnits.observeAll(),
        ) { list, vendors, groups, allUnits, conversions ->
            Catalog(
                products = list,
                suppliers = vendors,
                categories = groups.sortedWith(
                    compareBy({ it.details.normalizedName }, { it.id }),
                ),
                units = allUnits.associateBy { it.id },
                conversions = conversions.groupBy({ it.productId }, { it.details }),
            )
        }
        val here: Flow<Map<Long, QuantityMilli>?> =
            if (locationId == null) flowOf(null) else locationStock.observeLocation(locationId)
        return combine(catalog, unitsOut(query, locationId), here) { read, sold, onHandAt ->
            ReorderOverview(
                plan = ReorderPlanner.plan(
                    query = query,
                    products = read.products,
                    suppliers = read.suppliers,
                    unitsOut = sold,
                    onHandAt = onHandAt,
                    conversions = read.conversions,
                ),
                units = read.units,
                suppliers = read.suppliers.sortedWith(
                    compareBy({ it.details.normalizedName }, { it.id }),
                ),
                categories = read.categories,
            )
        }
    }

    /** Lo que salió por ventas en la ventana que termina hoy, o nada si no hay fecha de hoy. */
    private fun unitsOut(query: ReorderQuery, locationId: Long?): Flow<Map<Long, QuantityMilli>> =
        calendar.observe().flatMapLatest { result ->
            when (result) {
                is BusinessCalendarResult.Ready -> {
                    val today = result.calendar.businessDateOf(clock.now())
                    val since = today.plusDays(1 - query.windowDays.toInt())
                    outflow.observeUnitsOut(since, locationId)
                }

                is BusinessCalendarResult.UnknownZone -> flowOf(emptyMap())
            }
        }

    private class Catalog(
        val products: List<Product>,
        val suppliers: List<Supplier>,
        val categories: List<Category>,
        val units: Map<Long, MeasureUnit>,
        val conversions: Map<Long, List<ConversionDetails>>,
    )
}
