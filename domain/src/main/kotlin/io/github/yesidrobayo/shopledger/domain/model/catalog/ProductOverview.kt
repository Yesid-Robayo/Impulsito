package io.github.yesidrobayo.shopledger.domain.model.catalog

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.purchase.SupplierLastCost
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementRecord

/** El documento del que salió una fila del libro, para abrirlo desde el detalle del producto. */
sealed interface MovementDocument {
    data class Sale(val saleId: Long) : MovementDocument

    data class Purchase(val purchaseId: Long) : MovementDocument
}

/**
 * Una fila del libro de un producto, tal como la muestra su detalle.
 *
 * [averageCostBefore] es el costo promedio antes de la fila: con él, una corrección de costo dice
 * de cuánto a cuánto pasó. [compensatedById] es la fila que la anula, si ya la anularon, y
 * [document] la venta o la compra de la que salió, si salió de una.
 */
data class ProductMovement(
    val record: StockMovementRecord,
    val averageCostBefore: Money,
    val compensatedById: Long?,
    val document: MovementDocument?,
) {
    val id: Long get() = record.id
}

/**
 * Todo lo que muestra el detalle de un producto: el producto, su unidad base, su libro del más
 * reciente al más antiguo y su historial de precios, también del más reciente al más antiguo, con
 * el precio inicial al final. [lastCosts] es lo último que se pagó a cada proveedor, y
 * [purchaseUnits] las unidades en que se compró, por id, para decir por qué unidad va cada costo.
 */
data class ProductOverview(
    val product: Product,
    val unit: MeasureUnit?,
    val movements: List<ProductMovement>,
    val prices: List<PriceChange>,
    val lastCosts: List<SupplierLastCost> = emptyList(),
    val purchaseUnits: Map<Long, MeasureUnit> = emptyMap(),
)
