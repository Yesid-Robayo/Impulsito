package io.github.yesidrobayo.shopledger.domain.usecase.stock

import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.port.ProductRepository
import io.github.yesidrobayo.shopledger.domain.port.UnitRepository
import io.github.yesidrobayo.shopledger.domain.rule.stock.InventoryRules
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

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
)

/**
 * Lee el inventario y vuelve a emitir con cada cambio confirmado de los productos o las unidades:
 * una venta, una compra o un ajuste lo mueven sin reabrir la pantalla. Funciona sin permisos de
 * notificaciones, porque no lee las alertas. No escribe nada.
 */
class ObserveInventoryUseCase(
    private val products: ProductRepository,
    private val units: UnitRepository,
) {
    operator fun invoke(): Flow<InventoryOverview> = combine(
        products.observe(ArchiveFilter.ACTIVE),
        products.observe(ArchiveFilter.ARCHIVED),
        units.observeAll(),
    ) { active, archived, allUnits ->
        val tracked = active
            .filter { it.details.stockMode == ProductStockMode.TRACKED }
            .sortedWith(compareBy({ it.details.normalizedName }, { it.id }))
        val archivedValue = InventoryRules.value(archived)?.takeIf { it > Money.ZERO }
        InventoryOverview(
            hasProducts = active.isNotEmpty(),
            tracked = tracked,
            lowStock = InventoryRules.lowStock(tracked),
            negative = InventoryRules.negative(tracked),
            value = InventoryRules.value(active + archived),
            archivedValue = archivedValue,
            hasStockWithoutCost = InventoryRules.hasStockWithoutCost(tracked),
            units = allUnits.associateBy { it.id },
        )
    }
}
