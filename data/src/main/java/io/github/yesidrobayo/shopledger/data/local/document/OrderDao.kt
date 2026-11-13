package io.github.yesidrobayo.shopledger.data.local.document

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

/** Lo que reservan los pedidos sin entregar de un producto, en milésimas de su unidad base. */
internal data class ProductReservation(
    @ColumnInfo(name = "product_id") val productId: Long,
    @ColumnInfo(name = "reserved_milli") val reservedMilli: Long,
)

/**
 * Lecturas y escrituras de `orders` y sus líneas. Las escrituras solo se llaman dentro de la
 * transacción del caso de uso.
 *
 * Un pedido y sus líneas solo cambian mientras está `PENDING` o `READY`: entregarlo y cancelarlo lo
 * comprueban en su condición, y devuelven 0 si ya no lo está, y la guarda rechaza cualquier otro
 * camino. Sin `REPLACE`: un `INSERT OR REPLACE` borraría el pedido que choca con sus líneas.
 */
@Dao
internal interface OrderDao {
    /** Los sin entregar, de la fecha de negocio más nueva a la más vieja, y luego por `id`. */
    @Query(
        "SELECT * FROM orders WHERE status IN ('PENDING', 'READY') " +
            "ORDER BY business_date DESC, id DESC",
    )
    fun observeOpen(): Flow<List<OrderEntity>>

    @Query("SELECT * FROM orders WHERE id = :id")
    suspend fun findById(id: Long): OrderEntity?

    /** Las líneas del pedido, en el orden en que se agregaron. */
    @Query("SELECT * FROM order_lines WHERE order_id = :orderId ORDER BY id")
    suspend fun findLines(orderId: Long): List<OrderLineEntity>

    /**
     * Lo reservado por producto: la suma de las líneas de los pedidos sin entregar, por
     * `product_id`. No toca el libro de stock: un pedido entregado o cancelado deja de reservar.
     */
    @Query(
        "SELECT l.product_id, SUM(l.quantity_milli) AS reserved_milli FROM order_lines l " +
            "JOIN orders o ON o.id = l.order_id WHERE o.status IN ('PENDING', 'READY') " +
            "GROUP BY l.product_id ORDER BY l.product_id",
    )
    fun observeReservations(): Flow<List<ProductReservation>>

    /** Devuelve el id nuevo. El pedido nace `PENDING`. */
    @Insert
    suspend fun insert(order: OrderEntity): Long

    /** Devuelve el id nuevo. Falla si el pedido ya se entregó o se canceló. */
    @Insert
    suspend fun insertLine(line: OrderLineEntity): Long

    /** Cambia lo que la persona escribe y el total, después de rehacer sus líneas. */
    @Query(
        "UPDATE orders SET customer_id = :customerId, delivery_address = :deliveryAddress, " +
            "delivery_note = :deliveryNote, shipping_amount = :shippingAmount, " +
            "due_date = :dueDate, total = :total, updated_at = :updatedAt WHERE id = :id",
    )
    suspend fun update(
        id: Long,
        customerId: Long?,
        deliveryAddress: String?,
        deliveryNote: String?,
        shippingAmount: Long,
        dueDate: String?,
        total: Long,
        updatedAt: Long,
    ): Int

    /**
     * Vacía la dirección y la nota de entrega de todos los pedidos de un cliente, también los
     * entregados o cancelados: son datos personales, y anonimizar al cliente los borra. Lo demás del
     * pedido no cambia. Devuelve cuántos pedidos tenían algo que vaciar.
     */
    @Query(
        "UPDATE orders SET delivery_address = NULL, delivery_note = NULL, " +
            "updated_at = :updatedAt WHERE customer_id = :customerId " +
            "AND (delivery_address IS NOT NULL OR delivery_note IS NOT NULL)",
    )
    suspend fun clearDeliveryOf(customerId: Long, updatedAt: Long): Int

    /** Quita una línea. Solo se llama mientras el pedido no se ha entregado ni cancelado. */
    @Query("DELETE FROM order_lines WHERE id = :lineId")
    suspend fun deleteLine(lineId: Long): Int

    /**
     * Lo marca listo, con `READY`, o lo devuelve a `PENDING`. Devuelve 0 si ya se entregó o se
     * canceló.
     */
    @Query(
        "UPDATE orders SET status = :status, updated_at = :updatedAt " +
            "WHERE id = :id AND status IN ('PENDING', 'READY') AND :status IN ('PENDING', 'READY')",
    )
    suspend fun setPreparation(id: Long, status: String, updatedAt: Long): Int

    /**
     * Lo marca entregado con la venta [saleId], en la misma transacción que crea esa venta.
     * Devuelve 0 si ya se había entregado o cancelado: un pedido se entrega una sola vez.
     */
    @Query(
        "UPDATE orders SET status = 'DELIVERED', delivered_sale_id = :saleId, " +
            "updated_at = :updatedAt WHERE id = :id AND status IN ('PENDING', 'READY')",
    )
    suspend fun markDelivered(id: Long, saleId: Long, updatedAt: Long): Int

    /** Lo cancela. Devuelve 0 si ya se había entregado o cancelado. */
    @Query(
        "UPDATE orders SET status = 'CANCELLED', updated_at = :updatedAt " +
            "WHERE id = :id AND status IN ('PENDING', 'READY')",
    )
    suspend fun markCancelled(id: Long, updatedAt: Long): Int

    /**
     * Borra un pedido sin entregar, con sus líneas. Uno entregado lo apunta su venta, y su llave
     * `RESTRICT` hace fallar la sentencia sin borrar nada.
     */
    @Query("DELETE FROM orders WHERE id = :id")
    suspend fun delete(id: Long): Int
}
