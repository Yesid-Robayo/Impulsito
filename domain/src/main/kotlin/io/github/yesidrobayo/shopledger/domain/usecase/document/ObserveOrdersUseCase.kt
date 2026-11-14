package io.github.yesidrobayo.shopledger.domain.usecase.document

import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.RecipeComponent
import io.github.yesidrobayo.shopledger.domain.model.document.DocumentLine
import io.github.yesidrobayo.shopledger.domain.model.document.Order
import io.github.yesidrobayo.shopledger.domain.model.document.OrderStatus
import io.github.yesidrobayo.shopledger.domain.port.CustomerRepository
import io.github.yesidrobayo.shopledger.domain.port.OrderRepository
import io.github.yesidrobayo.shopledger.domain.port.ProductComponentRepository
import io.github.yesidrobayo.shopledger.domain.port.ProductRepository
import io.github.yesidrobayo.shopledger.domain.rule.document.OrderReservations
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** Un pedido en su lista, con el nombre de su cliente, o `null` sin cliente o anonimizado. */
data class OrderRow(val order: Order, val customerName: String?)

/**
 * Una línea de un pedido con lo que reserva y, si el stock no alcanza para todo lo reservado, lo
 * que le falta: [short] es cero si alcanza. Un producto sin libro no reserva ni falta.
 */
data class OrderLineView(
    val line: DocumentLine,
    val reserved: QuantityMilli,
    val short: QuantityMilli,
)

/** Un pedido con sus líneas y el nombre de su cliente. */
data class OrderView(val order: Order, val lines: List<OrderLineView>, val customerName: String?)

/**
 * Lo que leen la lista, el detalle y el stock disponible de los pedidos. Solo lee: lo reservado
 * sale de las líneas de los pedidos sin entregar, y nunca toca el libro.
 */
class ObserveOrdersUseCase(
    private val orders: OrderRepository,
    private val customers: CustomerRepository,
    private val products: ProductRepository,
    private val recipes: ProductComponentRepository? = null,
) {
    /** Los de [status], en el orden de su lista. */
    fun list(status: OrderStatus): Flow<List<OrderRow>> =
        orders.observeByStatus(status).map { rows ->
            val names = HashMap<Long, String?>()
            rows.map { order ->
                val name = order.customerId?.let { id -> names.getOrPut(id) { nameOf(id) } }
                OrderRow(order, name)
            }
        }

    /**
     * Lo reservado por producto con libro, con los combos en sus hojas, para descontarlo de lo que
     * hay. Solo vienen los productos que reservan algo.
     */
    fun reserved(): Flow<Map<Long, QuantityMilli>> = combine(
        orders.observeReservedLines(),
        allProducts(),
        recipes?.observeAll() ?: flowOf(emptyList()),
    ) { lines, all, components -> reservedOf(lines, all, components) }

    /**
     * El pedido [orderId] con sus líneas, lo que cada una reserva y lo que le falta, o `null` si
     * no existe. Un pedido entregado o cancelado ya no reserva.
     */
    fun detail(orderId: Long): Flow<OrderView?> = combine(
        orders.observeById(orderId),
        orders.observeReservedLines(),
        allProducts(),
        recipes?.observeAll() ?: flowOf(emptyList()),
    ) { order, lines, all, components ->
        order?.let { found ->
            val reserved = reservedOf(lines, all, components)
            val byId = all.associateBy { it.id }
            val views = orders.findLines(found.id).map { line ->
                lineView(line, found.status.isOpen, byId[line.content.productId], reserved)
            }
            OrderView(found, views, found.customerId?.let { nameOf(it) })
        }
    }

    private fun reservedOf(
        lines: Map<Long, QuantityMilli>,
        all: List<Product>,
        components: List<RecipeComponent>,
    ): Map<Long, QuantityMilli> = OrderReservations.byProduct(
        lines = lines,
        modes = all.associate { it.id to it.details.stockMode },
        recipes = components.groupBy { it.parentProductId },
    )

    /**
     * Lo que reserva [line] y lo que le falta. Lo que falta es lo que el stock no cubre de todo lo
     * reservado del producto, sin pasar de lo que pide esta línea.
     */
    private fun lineView(
        line: DocumentLine,
        open: Boolean,
        product: Product?,
        reserved: Map<Long, QuantityMilli>,
    ): OrderLineView {
        val total = reserved[line.content.productId]
        if (!open || product == null || total == null) {
            return OrderLineView(line, QuantityMilli.ZERO, QuantityMilli.ZERO)
        }
        val available = OrderReservations.available(product.onHand, total)
        val missing = if (available.milli < 0) -available.milli else 0L
        return OrderLineView(
            line = line,
            reserved = line.content.quantity,
            short = QuantityMilli(minOf(missing, line.content.quantity.milli)),
        )
    }

    /** Todos los productos, vigentes y archivados: un pedido viejo puede tener uno archivado. */
    private fun allProducts(): Flow<List<Product>> = combine(
        products.observe(ArchiveFilter.ACTIVE),
        products.observe(ArchiveFilter.ARCHIVED),
    ) { active, archived -> active + archived }

    private suspend fun nameOf(customerId: Long): String? =
        customers.findById(customerId)?.contact?.name
}
