package io.github.yesidrobayo.shopledger.data.local.document

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import io.github.yesidrobayo.shopledger.data.local.customer.CustomerEntity
import io.github.yesidrobayo.shopledger.data.local.sale.SaleEntity

/**
 * Un pedido por entregar. Reserva sus productos sin tocar el libro de stock: el stock solo se mueve
 * con la venta que sale al entregarlo.
 *
 * - `status` es el nombre guardado de `OrderStatus`. Nace `PENDING`, puede ir y volver de `READY`,
 *   y pasa una sola vez a `DELIVERED`, con su venta, o a `CANCELLED`. Lo exige la guarda.
 * - Dinero en unidad menor. `shipping_amount` entra en el ajuste de la venta al entregar.
 * - Un pedido entregado no se borra: la venta lo apunta con `order_id`, con `RESTRICT`. Su propia
 *   llave hacia la venta usa `SET NULL`, porque dos llaves `RESTRICT` en ciclo rompen la
 *   compilación de Room. Una venta nunca se borra, así que nunca llega a ponerla en nulo.
 * - El cliente usa `RESTRICT`: un cliente con pedidos no se borra, se archiva.
 */
@Entity(
    tableName = "orders",
    foreignKeys = [
        ForeignKey(
            entity = CustomerEntity::class,
            parentColumns = ["id"],
            childColumns = ["customer_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = SaleEntity::class,
            parentColumns = ["id"],
            childColumns = ["delivered_sale_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["customer_id"]),
        Index(value = ["status", "business_date"]),
        Index(value = ["delivered_sale_id"]),
    ],
)
internal data class OrderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "customer_id") val customerId: Long?,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "delivery_address") val deliveryAddress: String?,
    @ColumnInfo(name = "delivery_note") val deliveryNote: String?,
    @ColumnInfo(name = "shipping_amount") val shippingAmount: Long,
    @ColumnInfo(name = "due_date") val dueDate: String?,
    @ColumnInfo(name = "delivered_sale_id") val deliveredSaleId: Long?,
    @ColumnInfo(name = "total") val total: Long,
    @ColumnInfo(name = "occurred_at") val occurredAt: Long,
    @ColumnInfo(name = "business_date") val businessDate: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
