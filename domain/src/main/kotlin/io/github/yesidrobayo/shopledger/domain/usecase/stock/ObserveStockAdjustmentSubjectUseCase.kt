package io.github.yesidrobayo.shopledger.domain.usecase.stock

import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.stock.CostState
import io.github.yesidrobayo.shopledger.domain.port.LocationStockSource
import io.github.yesidrobayo.shopledger.domain.port.ProductRepository
import io.github.yesidrobayo.shopledger.domain.port.StockLedgerRepository
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.UnitRepository
import io.github.yesidrobayo.shopledger.domain.rule.stock.StockLedger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * El producto que se ajusta, con lo que el formulario necesita para mostrar el resultado en vivo:
 * su unidad base, su stock y su costo.
 *
 * [acceptsOpeningBalance] dice si todavía se puede registrar el stock con que empezó: solo mientras
 * su libro efectivo esté vacío, sin filas o con todas anuladas. Después, lo que entra es una compra
 * o una corrección por conteo.
 *
 * [here] es lo que el libro dice de la ubicación que se ajusta: contra eso se compara un conteo. Con
 * una sola ubicación es el stock del producto. El costo y su valor siguen siendo uno solo.
 */
data class StockAdjustmentSubject(
    val productId: Long,
    val name: String,
    val unit: MeasureUnit,
    val stockMode: ProductStockMode,
    val isArchived: Boolean,
    val minStock: QuantityMilli,
    val cost: CostState,
    val acceptsOpeningBalance: Boolean,
    val here: QuantityMilli = cost.onHand,
)

/**
 * Lee el producto que se ajusta y vuelve a emitir con cada cambio confirmado: un ajuste, una venta
 * o una compra mueven su stock mientras el formulario está abierto. Emite `null` si el producto no
 * existe.
 */
class ObserveStockAdjustmentSubjectUseCase(
    private val transactions: TransactionRunner,
    private val products: ProductRepository,
    private val units: UnitRepository,
    private val ledger: StockLedgerRepository,
    private val locationStock: LocationStockSource? = null,
) {
    /** El producto, con [here] leído en [locationId], o el stock de todo con `null`. */
    operator fun invoke(productId: Long, locationId: Long? = null): Flow<StockAdjustmentSubject?> =
        products.observeById(productId).map { product ->
            product ?: return@map null
            transactions.read {
                val unit = checkNotNull(units.findById(product.details.baseUnitId)) {
                    "Falta la unidad base"
                }
                val movements = ledger.findByProduct(productId).map { it.movement }
                StockAdjustmentSubject(
                    productId = product.id,
                    name = product.details.name,
                    unit = unit,
                    stockMode = product.details.stockMode,
                    isArchived = product.isArchived,
                    minStock = product.details.minStock,
                    cost = CostState(product.onHand, product.averageCost, product.stockValue),
                    acceptsOpeningBalance = StockLedger.effective(movements).isEmpty(),
                    here = locationId?.let { locationStock?.onHandAt(productId, it) }
                        ?: product.onHand,
                )
            }
        }
}
