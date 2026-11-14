package io.github.yesidrobayo.shopledger.data.repository

import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.data.local.document.OrderEntity
import io.github.yesidrobayo.shopledger.data.local.document.OrderLineEntity
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.document.DocumentLine
import io.github.yesidrobayo.shopledger.domain.model.document.NewDocumentLine
import io.github.yesidrobayo.shopledger.domain.model.document.NewOrder
import io.github.yesidrobayo.shopledger.domain.model.document.Order
import io.github.yesidrobayo.shopledger.domain.model.document.OrderStatus
import io.github.yesidrobayo.shopledger.domain.port.OrderRepository
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Los pedidos sobre `orders` y `order_lines`.
 *
 * Editar uno sin entregar borra sus líneas y escribe las de ahora, en el orden en que se armaron.
 * Entregado o cancelado, sus guardas no dejan cambiar nada salvo `updated_at` y vaciar la entrega,
 * así que entregar, cancelar y cambiar la preparación comprueban el estado en la misma sentencia.
 */
internal class RoomOrderRepository(database: AppDatabase) : OrderRepository {
    private val orders = database.orderDao()
    private val writes = RepositoryWrites(database)

    override fun observeByStatus(status: OrderStatus): Flow<List<Order>> {
        val rows = if (status.isOpen) {
            orders.observeOpenByStatus(status.name)
        } else {
            orders.observeClosedByStatus(status.name)
        }
        return rows.map { list -> list.map(::toOrder) }.distinctUntilChanged()
    }

    override fun observeById(id: Long): Flow<Order?> =
        orders.observeById(id).map { it?.let(::toOrder) }.distinctUntilChanged()

    override fun observeReservedLines(): Flow<Map<Long, QuantityMilli>> = orders
        .observeReservations()
        .map { rows -> rows.associate { it.productId to QuantityMilli(it.reservedMilli) } }
        .distinctUntilChanged()

    override suspend fun findById(id: Long): Order? = orders.findById(id)?.let(::toOrder)

    override suspend fun findLines(orderId: Long): List<DocumentLine> =
        orders.findLines(orderId).map(::toLine)

    override suspend fun insert(order: NewOrder): Long = writes.inTransaction {
        val at = order.at.toStoredMillis()
        val id = orders.insert(
            OrderEntity(
                customerId = order.customerId,
                status = OrderStatus.PENDING.name,
                deliveryAddress = order.deliveryAddress,
                deliveryNote = order.deliveryNote,
                shippingAmount = order.shippingAmount.minor,
                dueDate = order.dueDate?.toStored(),
                deliveredSaleId = null,
                total = order.total.minor,
                occurredAt = order.occurredAt.toStoredMillis(),
                businessDate = order.businessDate.toStored(),
                createdAt = at,
                updatedAt = at,
            ),
        )
        insertLines(id, order.lines)
        id
    }

    override suspend fun update(id: Long, order: NewOrder): Boolean = writes.inTransaction {
        val current = orders.findById(id)
        if (current == null || !storedEnum<OrderStatus>(current.status).isOpen) {
            return@inTransaction false
        }
        orders.deleteLinesOf(id)
        insertLines(id, order.lines)
        requireOneRow(
            orders.update(
                id = id,
                customerId = order.customerId,
                deliveryAddress = order.deliveryAddress,
                deliveryNote = order.deliveryNote,
                shippingAmount = order.shippingAmount.minor,
                dueDate = order.dueDate?.toStored(),
                total = order.total.minor,
                updatedAt = order.at.toStoredMillis(),
            ),
        )
        true
    }

    override suspend fun setPreparation(id: Long, status: OrderStatus, at: Instant): Boolean {
        require(status.isOpen) { "La preparación solo va entre pendiente y listo" }
        return writes.inTransaction {
            orders.setPreparation(id, status.name, at.toStoredMillis()) == 1
        }
    }

    override suspend fun markDelivered(id: Long, saleId: Long, at: Instant): Boolean =
        writes.inTransaction { orders.markDelivered(id, saleId, at.toStoredMillis()) == 1 }

    override suspend fun markCancelled(id: Long, at: Instant): Boolean =
        writes.inTransaction { orders.markCancelled(id, at.toStoredMillis()) == 1 }

    private suspend fun insertLines(orderId: Long, lines: List<NewDocumentLine>) {
        for (line in lines) {
            orders.insertLine(
                OrderLineEntity(
                    orderId = orderId,
                    productId = line.productId,
                    unitId = line.unitId,
                    unitFactorMilli = line.unitFactor.milli,
                    enteredQuantityMilli = line.enteredQuantity.milli,
                    quantityMilli = line.quantity.milli,
                    unitPrice = line.unitPrice.minor,
                    lineDiscount = line.lineDiscount.minor,
                    netAmount = line.netAmount.minor,
                    taxRateBp = line.taxRate.bp,
                    taxNameSnapshot = line.taxNameSnapshot,
                    taxAmount = line.taxAmount.minor,
                    lineTotal = line.lineTotal.minor,
                    productNameSnapshot = line.productNameSnapshot,
                ),
            )
        }
    }

    private fun toOrder(row: OrderEntity): Order {
        val status = storedEnum<OrderStatus>(row.status)
        // La guarda exige la venta solo con el pedido entregado: otra cosa es una fila rota.
        if ((status == OrderStatus.DELIVERED) != (row.deliveredSaleId != null)) brokenRow()
        return Order(
            id = row.id,
            customerId = row.customerId,
            status = status,
            deliveryAddress = row.deliveryAddress,
            deliveryNote = row.deliveryNote,
            shippingAmount = Money(row.shippingAmount),
            dueDate = row.dueDate?.let(::storedDate),
            deliveredSaleId = row.deliveredSaleId,
            total = Money(row.total),
            occurredAt = row.occurredAt.toInstant(),
            businessDate = storedDate(row.businessDate),
            createdAt = row.createdAt.toInstant(),
            updatedAt = row.updatedAt.toInstant(),
        )
    }

    private fun toLine(row: OrderLineEntity): DocumentLine = DocumentLine(
        id = row.id,
        documentId = row.orderId,
        content = NewDocumentLine(
            productId = row.productId,
            unitId = row.unitId,
            unitFactor = QuantityMilli(row.unitFactorMilli),
            enteredQuantity = QuantityMilli(row.enteredQuantityMilli),
            quantity = QuantityMilli(row.quantityMilli),
            unitPrice = Money(row.unitPrice),
            lineDiscount = Money(row.lineDiscount),
            netAmount = Money(row.netAmount),
            taxRate = BasisPoints(row.taxRateBp),
            taxNameSnapshot = row.taxNameSnapshot,
            taxAmount = Money(row.taxAmount),
            lineTotal = Money(row.lineTotal),
            productNameSnapshot = row.productNameSnapshot,
        ),
    )
}
