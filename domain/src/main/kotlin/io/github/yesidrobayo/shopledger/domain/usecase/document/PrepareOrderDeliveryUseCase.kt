package io.github.yesidrobayo.shopledger.domain.usecase.document

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.document.OrderStatus
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.port.CustomerRepository
import io.github.yesidrobayo.shopledger.domain.port.OrderRepository
import io.github.yesidrobayo.shopledger.domain.port.ProductRepository
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner

/**
 * Lo que necesita la venta para entregar el pedido [orderId]: sus líneas con el precio congelado,
 * el cliente con su lista, el cobro por envío, que entra en el ajuste de la venta, y el saldo a
 * favor del cliente, que la venta consume a crédito.
 *
 * - [archivedLines] cuenta las líneas de productos archivados, que quedan por fuera.
 * - [credit] es lo que el cliente tiene a favor, cero si no tiene: con anticipo, es lo que pagó.
 */
data class OrderDelivery(
    val orderId: Long,
    val customerId: Long?,
    val priceListId: Long?,
    val lines: List<SaleLineRequest>,
    val shippingAmount: Money,
    val archivedLines: Int,
    val credit: Money,
)

/** Resultado de [PrepareOrderDeliveryUseCase]. */
sealed interface OrderDeliveryResult {
    data class Ready(val delivery: OrderDelivery) : OrderDeliveryResult

    data object NotFound : OrderDeliveryResult

    /** Ya se entregó o se canceló: un pedido se entrega una sola vez. */
    data class NotOpen(val status: OrderStatus) : OrderDeliveryResult

    /** Todas sus líneas son de productos archivados. */
    data object NothingToDeliver : OrderDeliveryResult
}

/**
 * Prepara la entrega de un pedido, sin escribir nada. La venta se registra después con su
 * `orderId`, y es esa transacción la que lo marca entregado y saca el stock: salir sin vender lo
 * deja como estaba.
 *
 * Las líneas van con el precio congelado del pedido, que es lo que se acordó. Un cliente archivado o
 * anonimizado se conserva: su saldo a favor sigue siendo suyo, y la venta decide si lo acepta.
 */
class PrepareOrderDeliveryUseCase(
    private val transactions: TransactionRunner,
    private val orders: OrderRepository,
    private val products: ProductRepository,
    private val customers: CustomerRepository,
) {
    suspend operator fun invoke(orderId: Long): OrderDeliveryResult =
        transactions.read { prepare(orderId) }

    private suspend fun prepare(orderId: Long): OrderDeliveryResult {
        val order = orders.findById(orderId) ?: return OrderDeliveryResult.NotFound
        if (!order.status.isOpen) return OrderDeliveryResult.NotOpen(order.status)
        val lines = orders.findLines(orderId)
        val byId = products.findByIds(lines.map { it.content.productId }.toSet())
            .associateBy { it.id }
        val kept = lines.filter { byId[it.content.productId]?.isArchived == false }
        if (kept.isEmpty()) return OrderDeliveryResult.NothingToDeliver
        val customer = order.customerId?.let { customers.findById(it) }
        val balance = customer?.balance ?: Money.ZERO
        return OrderDeliveryResult.Ready(
            OrderDelivery(
                orderId = order.id,
                customerId = order.customerId,
                priceListId = customer?.priceListId,
                lines = kept.map { DocumentLines.requestOf(it) },
                shippingAmount = order.shippingAmount,
                archivedLines = lines.size - kept.size,
                credit = if (balance < Money.ZERO) -balance else Money.ZERO,
            ),
        )
    }
}
