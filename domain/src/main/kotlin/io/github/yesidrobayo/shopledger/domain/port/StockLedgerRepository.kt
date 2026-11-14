package io.github.yesidrobayo.shopledger.domain.port

import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.stock.NewStockMovement
import io.github.yesidrobayo.shopledger.domain.model.stock.StockKey
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementRecord
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow

/**
 * El libro de stock: la verdad del stock. Solo crece: ninguna función edita ni borra una fila.
 *
 * Toda lista sale en el orden del libro, por id ascendente, nunca por fecha: el id es el orden en
 * que se registraron las filas, y ese orden no cambia aunque el reloj del teléfono sí.
 *
 * Las lecturas que devuelven [Flow] vuelven a emitir cada vez que se confirma una transacción que
 * cambia lo leído. [append] solo se llama dentro de [TransactionRunner.write].
 */
interface StockLedgerRepository {
    /** Las filas de un producto, en el orden del libro. */
    fun observeByProduct(productId: Long): Flow<List<StockMovementRecord>>

    suspend fun findById(id: Long): StockMovementRecord?

    /** Las filas de un producto, en el orden del libro: lo que reproduce su costo. */
    suspend fun findByProduct(productId: Long): List<StockMovementRecord>

    /** Las filas que salieron de las líneas de una venta, en el orden del libro. */
    suspend fun findBySale(saleId: Long): List<StockMovementRecord>

    /** Las filas que salieron de las líneas de una compra, en el orden del libro. */
    suspend fun findByPurchase(purchaseId: Long): List<StockMovementRecord>

    /** Las filas que salieron de las líneas de una devolución a proveedor, en el orden del libro. */
    suspend fun findByPurchaseReturn(purchaseReturnId: Long): List<StockMovementRecord>

    /** Las filas que entraron por las líneas de una devolución de venta, en el orden del libro. */
    suspend fun findBySaleReturn(saleReturnId: Long): List<StockMovementRecord>

    /** Las filas de una producción, sus insumos y lo producido, en el orden del libro. */
    suspend fun findByProductionBatch(productionBatchId: Long): List<StockMovementRecord>

    /** Si la fila [movementId] ya tiene su compensatorio: una fila se anula una sola vez. */
    suspend fun isCompensated(movementId: Long): Boolean

    /**
     * Agrega la fila al libro y devuelve su id, que fija su lugar en el orden.
     *
     * En la misma transacción deja al producto con el saldo, el costo promedio y el valor del
     * stock que la fila dice tener después, y suma su cantidad al saldo de su parte del stock:
     * producto, ubicación y lote. Así el saldo de cada producto sigue siendo la suma de sus filas.
     * El caso de uso calcula esos valores antes, con el producto que leyó dentro de la misma
     * transacción.
     */
    suspend fun append(movement: NewStockMovement): Long

    /** Cuánto hay en una parte del stock: producto, ubicación y lote. Cero si nunca se movió. */
    suspend fun onHandAt(key: StockKey): QuantityMilli

    /**
     * El id del lote sin asignar de [productId], que recibe las filas sin lote de un producto que
     * se controla por lotes. Si todavía no existe, lo crea con [createdAt], en la transacción en
     * curso: hay uno por producto como máximo. Solo se llama dentro de [TransactionRunner.write].
     */
    suspend fun unassignedLot(productId: Long, createdAt: Instant): Long
}
