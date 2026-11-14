package io.github.yesidrobayo.shopledger.domain.usecase.document

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.catalog.RecipeComponent
import io.github.yesidrobayo.shopledger.domain.model.document.OrderStatus
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException
import io.github.yesidrobayo.shopledger.domain.rule.document.OrderReservations
import io.github.yesidrobayo.shopledger.domain.testing.FixedClock
import io.github.yesidrobayo.shopledger.domain.usecase.sale.InjectedSaleFailure
import io.github.yesidrobayo.shopledger.domain.usecase.sale.SaleWorld
import io.github.yesidrobayo.shopledger.domain.usecase.sale.product
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Los pedidos: guardarlos reserva sin tocar el libro, la reserva sale de los pendientes y listos, y
 * cambiar de estado, editar y cancelar respetan lo entregado. Valores escritos a mano. Nombres y
 * montos inventados.
 */
class OrderUseCasesTest {
    private val world = SaleWorld().apply {
        addProduct(product(1, salePrice = 2_500, onHandMilli = 10_000, averageCost = 900))
        addProduct(product(2, salePrice = 500, stockMode = ProductStockMode.UNTRACKED))
        addCustomer(CUSTOMER)
    }

    private fun line(productId: Long, milli: Long) =
        SaleLineRequest(productId, QuantityMilli(milli))

    private suspend fun save(request: OrderRequest) = world.saveOrder()(request)

    private suspend fun saved(request: OrderRequest) =
        (save(request) as SaveOrderResult.Saved).orderId

    private fun observe() = ObserveOrdersUseCase(
        world.orderRepository,
        world.customerRepository,
        world.products,
        world.recipeRepository,
    )

    private fun prepare() = SetOrderPreparationUseCase(
        world.transactions,
        world.orderRepository,
        world.clock,
    )

    private fun cancel() =
        CancelOrderUseCase(world.transactions, world.orderRepository, world.clock)

    private suspend fun reserved(productId: Long): QuantityMilli? =
        observe().reserved().first()[productId]

    @Test
    fun `an order of four of ten reserves four, leaves six available and the ledger alone`() =
        runTest {
            val id = saved(OrderRequest(listOf(line(1, 4_000)), customerId = CUSTOMER))

            assertEquals(QuantityMilli(4_000), reserved(1))
            val onHand = world.tables.products.getValue(1).onHand
            assertEquals(QuantityMilli(10_000), onHand)
            val available = OrderReservations.available(onHand, QuantityMilli(4_000))
            assertEquals(QuantityMilli(6_000), available)
            assertTrue(world.tables.movements.isEmpty())
            assertTrue(world.tables.sales.isEmpty())
            assertEquals(OrderStatus.PENDING, world.tables.orders.getValue(id).status)
            assertEquals(1L, world.tables.changeSeq)
        }

    @Test
    fun `the total adds the shipping charge to the lines`() = runTest {
        val id = saved(
            OrderRequest(
                listOf(line(1, 8_000)),
                customerId = CUSTOMER,
                dueDate = DUE,
                deliveryAddress = " Calle 8, local 2 ",
                deliveryNote = "  ",
                shippingAmount = Money(1_500),
            ),
        )

        // 8 × 2500 = 20000 en productos, más 1500 de envío.
        val order = world.tables.orders.getValue(id)
        assertEquals(Money(21_500), order.total)
        assertEquals(Money(20_000), order.products)
        assertEquals(Money(1_500), order.shippingAmount)
        assertEquals(DUE, order.dueDate)
        assertEquals("Calle 8, local 2", order.deliveryAddress)
        assertNull(order.deliveryNote)
    }

    @Test
    fun `ready still reserves, and delivered or cancelled stop reserving`() = runTest {
        val first = saved(OrderRequest(listOf(line(1, 4_000))))
        val second = saved(OrderRequest(listOf(line(1, 3_000))))
        assertEquals(QuantityMilli(7_000), reserved(1))

        assertEquals(OrderChangeResult.Changed, prepare()(first, ready = true))
        assertEquals(OrderStatus.READY, world.tables.orders.getValue(first).status)
        assertEquals(QuantityMilli(7_000), reserved(1))

        assertEquals(OrderChangeResult.Changed, prepare()(first, ready = false))
        assertEquals(OrderStatus.PENDING, world.tables.orders.getValue(first).status)

        assertEquals(OrderChangeResult.Changed, cancel()(second))
        assertEquals(QuantityMilli(4_000), reserved(1))
        assertEquals(OrderChangeResult.NotOpen, cancel()(second))
        assertEquals(OrderChangeResult.NotOpen, prepare()(second, ready = true))
        assertEquals(OrderStatus.CANCELLED, world.tables.orders.getValue(second).status)
    }

    @Test
    fun `a service does not reserve and a combo reserves its leaves`() = runTest {
        world.withCombos = true
        world.addProduct(product(3, salePrice = 6_000, stockMode = ProductStockMode.COMPONENTS))
        world.addRecipe(RecipeComponent(1, 3L, 1L, QuantityMilli(2_000)))

        saved(OrderRequest(listOf(line(2, 5_000), line(3, 1_000))))

        assertNull(reserved(2))
        assertEquals(QuantityMilli(2_000), reserved(1))
    }

    @Test
    fun `the detail tells each line what it reserves and what is short`() = runTest {
        saved(OrderRequest(listOf(line(1, 8_000))))
        val id = saved(OrderRequest(listOf(line(1, 3_000), line(2, 1_000))))

        val view = requireNotNull(observe().detail(id).first())

        // Hay 10 y se reservan 11: falta 1, que cae en esta línea.
        val (stocked, service) = view.lines
        assertEquals(QuantityMilli(3_000), stocked.reserved)
        assertEquals(QuantityMilli(1_000), stocked.short)
        assertEquals(QuantityMilli.ZERO, service.reserved)
        assertEquals(QuantityMilli.ZERO, service.short)
        assertNull(view.customerName)
    }

    @Test
    fun `the list carries the customer name`() = runTest {
        saved(OrderRequest(listOf(line(1, 1_000)), customerId = CUSTOMER))

        val rows = observe().list(OrderStatus.PENDING).first()

        assertEquals("Customer $CUSTOMER", rows.single().customerName)
        assertTrue(observe().list(OrderStatus.READY).first().isEmpty())
    }

    @Test
    fun `editing an open order rewrites its lines and keeps when it was ordered`() = runTest {
        val id = saved(OrderRequest(listOf(line(1, 1_000))))
        val first = world.tables.orders.getValue(id)
        world.clock = FixedClock.atEpochMillis(SaleWorld.NOW_MILLIS + DAY_MILLIS)

        val result = save(OrderRequest(listOf(line(1, 2_000)), orderId = id))

        assertEquals(SaveOrderResult.Saved(id), result)
        assertEquals(first.businessDate, world.tables.orders.getValue(id).businessDate)
        assertEquals(QuantityMilli(2_000), reserved(1))
        cancel()(id)
        assertEquals(
            SaveOrderResult.OrderNotOpen,
            save(OrderRequest(listOf(line(1, 5_000)), orderId = id)),
        )
    }

    @Test
    fun `what cannot be saved is rejected with its reason and writes nothing`() = runTest {
        world.addCustomer(ARCHIVED, archived = true)
        val ok = listOf(line(1, 1_000))

        assertEquals(SaveOrderResult.EmptyOrder, save(OrderRequest(emptyList())))
        assertEquals(SaveOrderResult.TooManyLines, save(OrderRequest(List(201) { line(1, 1) })))
        assertEquals(
            SaveOrderResult.TextTooLong,
            save(OrderRequest(ok, deliveryAddress = "a".repeat(201))),
        )
        assertEquals(
            SaveOrderResult.TextTooLong,
            save(OrderRequest(ok, deliveryNote = "n".repeat(201))),
        )
        assertEquals(
            SaveOrderResult.InvalidShipping,
            save(OrderRequest(ok, shippingAmount = Money(-1))),
        )
        assertEquals(
            SaveOrderResult.CustomerUnavailable,
            save(OrderRequest(ok, customerId = ARCHIVED)),
        )
        assertEquals(
            SaveOrderResult.LineRejected(0, DocumentLineRejection.EXTRA_NOT_SUPPORTED),
            save(OrderRequest(listOf(line(1, 1_000).copy(parentIndex = 0)))),
        )
        assertEquals(SaveOrderResult.OrderNotOpen, save(OrderRequest(ok, orderId = 9)))
        assertEquals(
            SaveOrderResult.TotalTooLarge,
            save(OrderRequest(ok, shippingAmount = Money(999_999_999_999_000))),
        )
        assertTrue(world.tables.orders.isEmpty())
        assertEquals(0L, world.tables.changeSeq)
    }

    @Test
    fun `an unconfirmed currency and a failure in any write leave nothing`() = runTest {
        val unconfirmed = SaleWorld(currencyConfirmed = false)
        unconfirmed.addProduct(product(1, salePrice = 100))
        assertEquals(
            SaveOrderResult.CurrencyNotConfirmed,
            unconfirmed.saveOrder()(OrderRequest(listOf(line(1, 1_000)))),
        )

        val request = OrderRequest(listOf(line(1, 1_000), line(2, 1_000)))
        for (failAt in 1..6) {
            world.failAtWrite = failAt
            val failed = runCatching { save(request) }
            assertTrue("Escritura $failAt", failed.exceptionOrNull() is InjectedSaleFailure)
            assertTrue(world.tables.orders.isEmpty())
            assertTrue(world.tables.orderLines.isEmpty())
        }
        world.failAtWrite = null
        val id = saved(request)
        world.failAtWrite = 1
        world.failure = { StorageFailureException(StorageFailure.DISK_FULL) }
        assertEquals(SaveOrderResult.StorageFailed(StorageFailure.DISK_FULL), save(request))
        assertEquals(OrderChangeResult.StorageFailed(StorageFailure.DISK_FULL), cancel()(id))
        assertEquals(OrderStatus.PENDING, world.tables.orders.getValue(id).status)
    }

    private companion object {
        const val CUSTOMER = 30L
        const val ARCHIVED = 31L
        const val DAY_MILLIS = 86_400_000L
        val DUE = BusinessDate(LocalDate(2026, 11, 20))
    }
}
