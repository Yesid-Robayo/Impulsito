package io.github.yesidrobayo.shopledger.domain.usecase.stock

import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.port.LocationStockSource
import io.github.yesidrobayo.shopledger.domain.port.ProductRepository
import io.github.yesidrobayo.shopledger.domain.port.UnitRepository
import io.github.yesidrobayo.shopledger.domain.rule.stock.InventoryRules
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf

/**
 * El inventario de un vistazo, ya ordenado.
 *
 * [hasProducts] dice si hay algún producto sin archivar, aunque no lleve stock: sin él, la pantalla
 * invita a crear el primero. [tracked] son los que llevan libro, por nombre. [lowStock] sigue el
 * orden de lo urgente y [negative] solo trae el stock negativo.
 *
 * [value] es el valor de todo el inventario, archivados incluidos, o `null` si no cabe en `Long`.
 * [archivedValue] es la parte de los archivados, o `null` si no tienen stock con valor.
 * [hasStockWithoutCost] avisa que el valor real puede ser mayor.
 */
data class InventoryOverview(
    val hasProducts: Boolean,
    val tracked: List<Product>,
    val lowStock: List<Product>,
    val negative: List<Product>,
    val value: Money?,
    val archivedValue: Money?,
    val hasStockWithoutCost: Boolean,
    val units: Map<Long, MeasureUnit>,
    val families: List<VariantFamily> = emptyList(),
)

/**
 * Un producto con variantes en el inventario: no tiene stock propio, y su stock es la suma de las
 * suyas. [variants] son las vigentes con stock propio, en el orden de su nombre.
 */
data class VariantFamily(
    val parent: Product,
    val variants: List<Product>,
    val onHand: QuantityMilli,
)

/**
 * Lee el inventario y vuelve a emitir con cada cambio confirmado de los productos o las unidades:
 * una venta, una compra o un ajuste lo mueven sin reabrir la pantalla. Funciona sin permisos de
 * notificaciones, porque no lee las alertas. No escribe nada.
 *
 * Con una ubicación elegida, cada producto muestra lo que hay en ella, y lo que se acaba o queda en
 * negativo se mide allí, contra el mismo mínimo del producto: quien repone un puesto mira lo que
 * falta en el puesto. El valor del inventario sigue siendo el de todo el stock, porque el costo es
 * uno solo por producto, no por ubicación.
 */
class ObserveInventoryUseCase(
    private val products: ProductRepository,
    private val units: UnitRepository,
    private val locationStock: LocationStockSource? = null,
) {
    /** El inventario de todas las ubicaciones con [locationId] nulo, o el de esa ubicación. */
    operator fun invoke(locationId: Long? = null): Flow<InventoryOverview> = combine(
        products.observe(ArchiveFilter.ACTIVE),
        products.observe(ArchiveFilter.ARCHIVED),
        units.observeAll(),
        stockAt(locationId),
    ) { all, archived, allUnits, here ->
        val active = if (here == null) {
            all
        } else {
            all.map { it.copy(onHand = here[it.id] ?: QuantityMilli.ZERO) }
        }
        // El principal de unas variantes no tiene stock propio: sale como familia, no como fila.
        val parentIds = active.mapNotNullTo(mutableSetOf()) { it.details.parentProductId }
        val tracked = active
            .filter { it.details.stockMode == ProductStockMode.TRACKED && it.id !in parentIds }
            .sortedWith(compareBy({ it.details.normalizedName }, { it.id }))
        val families = active.filter { it.id in parentIds }
            .sortedWith(compareBy({ it.details.normalizedName }, { it.id }))
            .map { parent ->
                val variants = tracked.filter { it.details.parentProductId == parent.id }
                VariantFamily(parent, variants, QuantityMilli(variants.sumOf { it.onHand.milli }))
            }
        val archivedValue = InventoryRules.value(archived)?.takeIf { it > Money.ZERO }
        InventoryOverview(
            hasProducts = active.isNotEmpty(),
            tracked = tracked,
            lowStock = InventoryRules.lowStock(tracked),
            negative = InventoryRules.negative(tracked),
            value = InventoryRules.value(all + archived),
            archivedValue = archivedValue,
            hasStockWithoutCost = InventoryRules.hasStockWithoutCost(tracked),
            units = allUnits.associateBy { it.id },
            families = families,
        )
    }

    /** Cuánto hay de cada producto en [locationId], o `null` para mirar todo el stock. */
    private fun stockAt(locationId: Long?): Flow<Map<Long, QuantityMilli>?> {
        val source = locationStock
        if (locationId == null || source == null) return flowOf(null)
        return source.observeLocation(locationId)
    }
}
