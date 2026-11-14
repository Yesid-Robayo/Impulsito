package io.github.yesidrobayo.shopledger.domain.model.document

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import kotlin.time.Instant

/**
 * En qué va un pedido. Nace [PENDING], puede ir y volver de [READY], y pasa una sola vez a
 * [DELIVERED], con la venta de la entrega, o a [CANCELLED]. Los nombres se guardan tal cual.
 */
enum class OrderStatus {
    PENDING,
    READY,
    DELIVERED,
    CANCELLED,
    ;

    /** Si el pedido sigue sin entregar ni cancelar: reserva, y se edita. */
    val isOpen: Boolean get() = this == PENDING || this == READY
}

/**
 * Un pedido como se guarda, sin sus líneas: lo que un cliente encargó, que reserva unidades sin
 * tocar el libro de stock hasta entregarse.
 *
 * - [products] es lo que suman sus líneas, neto más impuesto, y [shippingAmount] el cobro por
 *   envío, que es ingreso de la venta al entregar. [total] es los dos juntos: lo que se cobrará.
 * - [dueDate] es la fecha de entrega, si se eligió. Pasada, el pedido no cambia solo: se marca
 *   `Entrega vencida`.
 * - [deliveredSaleId] es la venta de la entrega, solo con [OrderStatus.DELIVERED]. Su número
 *   visible es [id].
 */
data class Order(
    val id: Long,
    val customerId: Long?,
    val status: OrderStatus,
    val deliveryAddress: String?,
    val deliveryNote: String?,
    val shippingAmount: Money,
    val dueDate: BusinessDate?,
    val deliveredSaleId: Long?,
    val total: Money,
    val occurredAt: Instant,
    val businessDate: BusinessDate,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    /** Lo que suman las líneas, sin el envío. */
    val products: Money get() = total - shippingAmount
}

/**
 * Lo que se guarda de un pedido, nuevo o editado: sus datos de entrega, sus líneas ya calculadas y
 * el total con el envío. [at] es el instante del cambio, y [occurredAt] con [businessDate] cuándo
 * se pidió: al editarlo se conservan los de la primera vez.
 */
data class NewOrder(
    val customerId: Long?,
    val deliveryAddress: String?,
    val deliveryNote: String?,
    val shippingAmount: Money,
    val dueDate: BusinessDate?,
    val lines: List<NewDocumentLine>,
    val total: Money,
    val occurredAt: Instant,
    val businessDate: BusinessDate,
    val at: Instant,
)
