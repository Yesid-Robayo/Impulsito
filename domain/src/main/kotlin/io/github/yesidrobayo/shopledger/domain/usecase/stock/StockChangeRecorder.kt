package io.github.yesidrobayo.shopledger.domain.usecase.stock

import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.region.RecordTime
import io.github.yesidrobayo.shopledger.domain.model.stock.NewStockMovement
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementDraft
import io.github.yesidrobayo.shopledger.domain.port.LowStockAlertRepository
import io.github.yesidrobayo.shopledger.domain.port.ProductRepository
import io.github.yesidrobayo.shopledger.domain.port.StockLedgerRepository

/**
 * El libro de stock de un caso de uso que, además, anota lo que la transacción le hace: el saldo de
 * cada producto antes de su primera fila y cada fila que agrega. Con eso, al final, evalúa la
 * alerta de stock bajo una sola vez por producto, como si toda la transacción fuera un cambio.
 *
 * Se usa en lugar del libro dentro de una venta, una compra o sus anulaciones: el caso de uso llama
 * [start] al empezar cada transacción y [applyAlerts] justo antes de confirmarla. Las transacciones
 * de escritura nunca corren a la vez, así que lo anotado es siempre de la transacción en curso.
 */
internal class StockChangeRecorder(private val ledger: StockLedgerRepository) :
    StockLedgerRepository by ledger {
    private val before = LinkedHashMap<Long, QuantityMilli>()
    private val drafts = ArrayList<StockMovementDraft>()

    /** Olvida lo anotado: empieza una transacción nueva. */
    fun start() {
        before.clear()
        drafts.clear()
    }

    /**
     * Agrega la fila, y anota el saldo de su producto antes de la primera fila de esta transacción:
     * el que la fila deja menos lo que mueve.
     */
    override suspend fun append(movement: NewStockMovement): Long {
        before.getOrPut(movement.movement.key.productId) {
            movement.balanceAfter - movement.movement.quantity
        }
        drafts += movement.movement
        return ledger.append(movement)
    }

    /**
     * Crea o resuelve la alerta de cada producto que movió la transacción, en ella misma, con el
     * saldo antes de su primera fila y después de la última. Sin filas, no hace nada.
     */
    suspend fun applyAlerts(
        alerts: LowStockAlertRepository,
        products: ProductRepository,
        time: RecordTime,
    ) {
        if (drafts.isEmpty()) return
        val moved = products.findByIds(before.keys).associateBy { it.id }
        val changes = before.map { (productId, onHandBefore) ->
            val product = checkNotNull(moved[productId]) {
                "Una fila del libro siempre tiene su producto"
            }
            product to onHandBefore
        }
        alerts.applyTransaction(changes, drafts, time)
    }
}
