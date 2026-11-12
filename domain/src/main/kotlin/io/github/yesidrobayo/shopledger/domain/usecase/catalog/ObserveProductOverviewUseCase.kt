package io.github.yesidrobayo.shopledger.domain.usecase.catalog

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.catalog.MovementDocument
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductMovement
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductOverview
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementRecord
import io.github.yesidrobayo.shopledger.domain.port.MovementDocumentSource
import io.github.yesidrobayo.shopledger.domain.port.PriceHistoryRepository
import io.github.yesidrobayo.shopledger.domain.port.ProductRepository
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
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(productId: Long): Flow<ProductOverview?> =
        products.observeById(productId).flatMapLatest { product ->
            if (product == null) {
                flowOf(null)
            } else {
                combine(
                    units.observeAll(),
                    ledger.observeByProduct(productId),
                    prices.observeByProduct(productId),
                    documents.observeDocuments(productId),
                ) { allUnits, rows, changes, links ->
                    ProductOverview(
                        product = product,
                        unit = allUnits.firstOrNull { it.id == product.details.baseUnitId },
                        movements = movementsOf(product, rows, links),
                        prices = changes.sortedByDescending { it.id },
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
