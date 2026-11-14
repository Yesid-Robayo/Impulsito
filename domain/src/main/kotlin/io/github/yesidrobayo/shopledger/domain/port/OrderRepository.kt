package io.github.yesidrobayo.shopledger.domain.port

import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.document.DocumentLine
import io.github.yesidrobayo.shopledger.domain.model.document.NewOrder
import io.github.yesidrobayo.shopledger.domain.model.document.Order
import io.github.yesidrobayo.shopledger.domain.model.document.OrderStatus
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow

/**
 * Los pedidos y sus líneas. No son un libro: mientras están sin entregar ni cancelar se editan, y
 * sus líneas se rehacen. Entregados o cancelados, ya no cambian.
 *
 * Las escrituras solo se llaman dentro de [TransactionRunner.write]. Las lecturas que devuelven
 * [Flow] vuelven a emitir con cada transacción que cambia lo leído.
 */
interface OrderRepository {
    /**
     * Los de [status]: los sin entregar por fecha de entrega, la más cercana primero y sin fecha
     * al final; los demás, del más nuevo al más viejo.
     */
    fun observeByStatus(status: OrderStatus): Flow<List<Order>>

    /** El pedido [id], o `null` si no existe. */
    fun observeById(id: Long): Flow<Order?>

    /**
     * Lo que suman las líneas de los pedidos sin entregar ni cancelar, por producto, en milésimas
     * de su unidad base. Un pedido entregado o cancelado deja de contar.
     */
    fun observeReservedLines(): Flow<Map<Long, QuantityMilli>>

    suspend fun findById(id: Long): Order?

    /** Sus líneas, en el orden en que se agregaron. */
    suspend fun findLines(orderId: Long): List<DocumentLine>

    /** Guarda un pedido pendiente con sus líneas y devuelve su id. */
    suspend fun insert(order: NewOrder): Long

    /**
     * Cambia el pedido sin entregar [id]: cliente, entrega, envío, total y todas sus líneas. Lo que
     * dice cuándo se pidió no cambia. Devuelve `false` si ya se entregó o canceló.
     */
    suspend fun update(id: Long, order: NewOrder): Boolean

    /**
     * Lo pasa a [status], `READY` o `PENDING`. Devuelve `false` si ya se entregó o canceló.
     */
    suspend fun setPreparation(id: Long, status: OrderStatus, at: Instant): Boolean

    /**
     * Lo marca entregado con la venta [saleId], que se escribe en la misma transacción. Devuelve
     * `false` si ya se había entregado o cancelado: un pedido se entrega una sola vez.
     */
    suspend fun markDelivered(id: Long, saleId: Long, at: Instant): Boolean

    /** Lo cancela. Devuelve `false` si ya se había entregado o cancelado. */
    suspend fun markCancelled(id: Long, at: Instant): Boolean
}
