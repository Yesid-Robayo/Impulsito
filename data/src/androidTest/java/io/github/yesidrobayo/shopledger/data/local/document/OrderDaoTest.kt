package io.github.yesidrobayo.shopledger.data.local.document

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.local.LedgerDatabaseTest
import io.github.yesidrobayo.shopledger.data.local.catalog.Emissions
import io.github.yesidrobayo.shopledger.data.local.catalog.FOREIGN_KEY
import io.github.yesidrobayo.shopledger.data.local.catalog.LATER
import io.github.yesidrobayo.shopledger.data.local.catalog.MUCH_LATER
import io.github.yesidrobayo.shopledger.data.local.catalog.constraintFailure
import io.github.yesidrobayo.shopledger.data.local.catalog.product
import io.github.yesidrobayo.shopledger.data.local.customer.customer
import io.github.yesidrobayo.shopledger.data.local.foreignKeysOf
import io.github.yesidrobayo.shopledger.data.local.ids
import io.github.yesidrobayo.shopledger.data.local.indexesOf
import io.github.yesidrobayo.shopledger.data.local.sale
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Los pedidos: cada columna en su lugar, los sin entregar de la fecha más nueva a la más vieja, lo
 * que reservan por producto sin tocar el libro, una sola entrega o cancelación, y llaves que
 * impiden borrar uno entregado. Un pedido sin entregar se borra con sus líneas.
 */
@RunWith(AndroidJUnit4::class)
internal class OrderDaoTest : LedgerDatabaseTest() {
    private val orders get() = database.orderDao()

    @Test
    fun everyColumnReadsBackFromItsOwnPlace() = runBlocking {
        // El cliente y la unidad existen: la unidad 3 es una de las iniciales.
        val customerId = database.customerDao().insert(customer("Ana"))
        val full =
            order(businessDate = "2026-01-15").copy(
                customerId = customerId,
                deliveryNote = "Tocar el timbre",
                dueDate = null,
                total = 41_000,
                createdAt = LATER,
                updatedAt = MUCH_LATER,
            )
        val id = orders.insert(full)
        val line = orderLine(id, productId).copy(unitId = 3, taxNameSnapshot = "Exento")
        val lineId = orders.insertLine(line)

        assertEquals(full.copy(id = id), orders.findById(id))
        assertEquals(listOf(line.copy(id = lineId)), orders.findLines(id))
        assertNull(orders.findById(MISSING_ID))

        assertEquals(1, orders.update(id, null, null, null, 0, "2026-01-10", 22_000, LATER))
        assertEquals(
            full.copy(
                id = id,
                customerId = null,
                deliveryAddress = null,
                deliveryNote = null,
                shippingAmount = 0,
                dueDate = "2026-01-10",
                total = 22_000,
                updatedAt = LATER,
            ),
            orders.findById(id),
        )
    }

    @Test
    fun openOrdersReserveTheirProductsUntilDeliveredOrCancelled() = runBlocking {
        val tea = database.productDao().insert(product("Té"))
        val first = orders.insert(order(businessDate = "2026-01-02"))
        val second = orders.insert(order(businessDate = "2026-01-03"))
        orders.insertLine(orderLine(first, productId, quantityMilli = 2_000))
        orders.insertLine(orderLine(first, tea, quantityMilli = 500))
        orders.insertLine(orderLine(second, productId, quantityMilli = 1_250))
        val saleId = database.saleDao().insert(sale(priceListId).copy(orderId = second))

        val open = Emissions(this, orders.observeOpen().ids { it.id })
        val reserved = Emissions(this, orders.observeReservations())
        try {
            open.awaitValue(listOf(second, first))
            reserved.awaitValue(
                listOf(ProductReservation(productId, 3_250), ProductReservation(tea, 500)),
            )

            // Listo sigue reservando; entregado deja de reservar.
            assertEquals(1, orders.setPreparation(second, "READY", updatedAt = LATER))
            reserved.awaitValue(
                listOf(ProductReservation(productId, 3_250), ProductReservation(tea, 500)),
            )
            assertEquals(1, orders.markDelivered(second, saleId, updatedAt = LATER))
            open.awaitValue(listOf(first))
            reserved.awaitValue(
                listOf(ProductReservation(productId, 2_000), ProductReservation(tea, 500)),
            )

            assertEquals(1, orders.markCancelled(first, updatedAt = LATER))
            open.awaitValue(emptyList())
            reserved.awaitValue(emptyList())
        } finally {
            open.stop()
            reserved.stop()
        }
        // El libro de stock no se tocó.
        assertEquals(0L, count("SELECT COUNT(*) FROM stock_movements"))
    }

    @Test
    fun anOrderIsDeliveredOrCancelledOnlyOnce() = runBlocking {
        val id = orders.insert(order())
        val saleId = database.saleDao().insert(sale(priceListId))

        assertEquals(1, orders.setPreparation(id, "READY", updatedAt = LATER))
        assertEquals(1, orders.setPreparation(id, "PENDING", updatedAt = LATER))
        // La preparación no entrega ni cancela: para eso están sus funciones.
        assertEquals(0, orders.setPreparation(id, "DELIVERED", updatedAt = LATER))
        assertEquals(0, orders.setPreparation(id, "CANCELLED", updatedAt = LATER))

        assertEquals(1, orders.markDelivered(id, saleId, updatedAt = MUCH_LATER))
        assertEquals(0, orders.markDelivered(id, saleId, updatedAt = MUCH_LATER))
        assertEquals(0, orders.markCancelled(id, updatedAt = MUCH_LATER))
        assertEquals(0, orders.setPreparation(id, "READY", updatedAt = MUCH_LATER))
        assertEquals("DELIVERED", orders.findById(id)?.status)
        assertEquals(saleId, orders.findById(id)?.deliveredSaleId)
    }

    @Test
    fun anOrderWithoutSaleIsDeletedWithItsLinesAndADeliveredOneIsNot() = runBlocking {
        val open = orders.insert(order())
        val lineId = orders.insertLine(orderLine(open, productId))
        assertEquals(1, orders.deleteLine(lineId))
        orders.insertLine(orderLine(open, productId))
        assertEquals(1, orders.delete(open))
        assertEquals(emptyList<OrderLineEntity>(), orders.findLines(open))

        val delivered = orders.insert(order())
        orders.insertLine(orderLine(delivered, productId))
        val saleId = database.saleDao().insert(sale(priceListId).copy(orderId = delivered))
        assertEquals(1, orders.markDelivered(delivered, saleId, updatedAt = LATER))
        constraintFailure(FOREIGN_KEY) { orders.delete(delivered) }
        constraintFailure(FOREIGN_KEY) { database.productDao().delete(productId) }

        // Una unidad pedida está en uso, y una línea no apunta a una unidad que no existe.
        val pack = requireNotNull(database.unitDao().findBySystemKey("PACK")).id
        val pending = orders.insert(order())
        orders.insertLine(orderLine(pending, productId).copy(unitId = pack))
        assertEquals(true, database.unitDao().isInUse(pack))
        constraintFailure(FOREIGN_KEY) {
            orders.insertLine(orderLine(pending, productId).copy(unitId = MISSING_ID))
        }

        // El cliente de un pedido existe, y con pedidos no se borra.
        val customerId = database.customerDao().insert(customer("Ana"))
        orders.insert(order().copy(customerId = customerId))
        constraintFailure(FOREIGN_KEY) { database.customerDao().delete(customerId) }
        constraintFailure(FOREIGN_KEY) { orders.insert(order().copy(customerId = MISSING_ID)) }

        // Una venta no apunta a un pedido que no existe.
        constraintFailure(FOREIGN_KEY) {
            database.saleDao().insert(sale(priceListId).copy(orderId = MISSING_ID))
        }
    }

    @Test
    fun keysAndIndexesAreTheOnesOfTheDataModel() = runBlocking {
        assertEquals(
            listOf(
                "customer_id -> customers RESTRICT",
                "delivered_sale_id -> sales SET NULL",
            ),
            database.foreignKeysOf("orders"),
        )
        assertEquals(
            listOf(
                "order_id -> orders CASCADE",
                "product_id -> products RESTRICT",
                "unit_id -> units RESTRICT",
            ),
            database.foreignKeysOf("order_lines"),
        )
        assertEquals(
            listOf(
                "index_orders_customer_id (customer_id)",
                "index_orders_delivered_sale_id (delivered_sale_id)",
                "index_orders_status_business_date (status, business_date)",
            ),
            database.indexesOf("orders"),
        )
        assertEquals(
            listOf(
                "index_order_lines_order_id (order_id)",
                "index_order_lines_product_id (product_id)",
                "index_order_lines_unit_id (unit_id)",
            ),
            database.indexesOf("order_lines"),
        )
    }

    private companion object {
        const val MISSING_ID = 999L
    }
}
