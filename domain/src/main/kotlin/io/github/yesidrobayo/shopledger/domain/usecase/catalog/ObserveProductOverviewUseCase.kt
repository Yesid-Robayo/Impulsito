package io.github.yesidrobayo.shopledger.domain.usecase.catalog

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.catalog.MovementDocument
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductMovement
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductOverview
import io.github.yesidrobayo.shopledger.domain.model.purchase.SupplierLastCost
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementRecord
import io.github.yesidrobayo.shopledger.domain.port.MovementDocumentSource
import io.github.yesidrobayo.shopledger.domain.port.PriceHistoryRepository
import io.github.yesidrobayo.shopledger.domain.port.ProductRepository
import io.github.yesidrobayo.shopledger.domain.port.PurchaseRepository
import io.github.yesidrobayo.shopledger.domain.port.StockLedgerRepository
import io.github.yesidrobayo.shopledger.domain.port.UnitRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

/**
 * El detalle de un producto: el producto, su libro y su historial de precios. Emite `null` si el
 * producto no existe, por ejemplo porque se acaba de eliminar.
 *
 * El libro se lee en su orden, por id, y se muestra al revés: lo más reciente arriba. Cada fila
 * lleva el costo de antes, que es el que dejó la fila anterior o el costo inicial en la primera, y
 * la fila que la anula, si ya la anularon.
 */
class ObserveProductOverviewUseCase(
    private val products: ProductRepository,
    private val units: UnitRepository,
    private val ledger: StockLedgerRepository,
    private val prices: PriceHistoryRepository,
    private val documents: MovementDocumentSource,
    private val purchases: PurchaseRepository? = null,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(productId: Long): Flow<ProductOverview?> =
        products.observeById(productId).flatMapLatest { product ->
            if (product == null) {
                flowOf(null)
            } else {
                // Lo último pagado a cada proveedor: sin compras conectadas, nada que mostrar.
                val lastCosts =
                    purchases?.observeLastCosts(productId) ?: flowOf(emptyList<SupplierLastCost>())
                combine(
                    units.observeAll(),
                    ledger.observeByProduct(productId),
                    prices.observeByProduct(productId),
                    documents.observeDocuments(productId),
                    lastCosts,
                ) { allUnits, rows, changes, links, costs: List<SupplierLastCost> ->
                    val unitsById: Map<Long, MeasureUnit> = allUnits.associateBy { it.id }
                    ProductOverview(
                        product = product,
                        unit = unitsById[product.details.baseUnitId],
                        movements = movementsOf(product, rows, links),
                        prices = changes.sortedByDescending { it.id },
                        lastCosts = costs,
                        purchaseUnits = costs.mapNotNull { cost ->
                            unitsById[cost.unitId]?.let { cost.unitId to it }
                        }.toMap(),
                    )
                }
            }
        }.distinctUntilChanged()

    companion object {
        /**
         * Las filas de [rows], que llegan en el orden del libro, de la más reciente a la más
         * antigua, con el costo de antes y la fila que anula a cada una.
         */
        internal fun movementsOf(
            product: Product,
            rows: List<StockMovementRecord>,
            links: Map<Long, MovementDocument>,
        ): List<ProductMovement> {
            val compensatedBy = rows
                .mapNotNull { row -> row.movement.reversesMovementId?.let { it to row.id } }
                .toMap()
            var costBefore: Money = product.initialCost
            val entries = rows.sortedBy { it.id }.map { row ->
                val entry = ProductMovement(row, costBefore, compensatedBy[row.id], links[row.id])
                costBefore = row.averageCostAfter
                entry
            }
            return entries.asReversed()
        }
    }
}
