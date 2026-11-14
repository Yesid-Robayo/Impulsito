package io.github.yesidrobayo.shopledger.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.transaction.RestoreLock
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.document.OrderStatus
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.usecase.document.CancelOrderUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.document.ObserveOrdersUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.document.OrderChangeResult
import io.github.yesidrobayo.shopledger.domain.usecase.document.OrderRequest
import io.github.yesidrobayo.shopledger.domain.usecase.document.SaveOrderResult
import io.github.yesidrobayo.shopledger.domain.usecase.document.SaveOrderUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.document.SetOrderPreparationUseCase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Los pedidos sobre Room, con sus guardas y el runner de la app: guardar reserva sin escribir en el
 * libro de stock, lo reservado sale de los pendientes y listos, y cancelar deja de reservar y cierra
 * sus líneas. Productos y montos inventados.
 */
@RunWith(AndroidJUnit4::class)
internal class OrdersOnDatabaseTest : RepositoryTest() {
    private val products by lazy { RoomProductRepository(database) }
    private val orders by lazy { RoomOrderRepository(database) }
    private val customers by lazy { RoomCustomerRepository(database) }
    private val clock = Clock { AT }

    private val save by lazy {
        SaveOrderUseCase(
            transactions = runner,
            settings = RoomAppSettingsRepository(database, RestoreLock { false }),
            orders = orders,
            customers = customers,
            clock = clock,
            products = products,
            units = RoomUnitRepository(database),
            taxRates = RoomTaxRateRepository(database),
            systemRows = RoomSystemRowRepository(database),
        )
    }
    private val observe by lazy {
        ObserveOrdersUseCase(orders, customers, products, RoomProductComponentRepository(database))
    }

    private suspend fun stocked(name: String): Long = commit {
        val id = products.insert(newProduct(name))
        RoomStockLedgerRepository(database).append(openingRow(id, units = 10))
        id
    }

    private suspend fun ordered(request: OrderRequest): Long =
        (save(request) as SaveOrderResult.Saved).orderId

    @Test
    fun anOrderReservesWithoutTouchingTheLedger() = runBlocking {
        val soap = stocked("Jabón inventado")
        val movements = count("SELECT COUNT(*) FROM stock_movements")
        val before = changeSeq()

        val id = ordered(
            OrderRequest(
                listOf(SaleLineRequest(soap, QuantityMilli(4_000))),
                dueDate = BusinessDate(LocalDate(2026, 11, 20)),
                deliveryAddress = "Calle inventada 8",
                shippingAmount = Money(1_500),
            ),
        )

        val order = requireNotNull(orders.findById(id))
        assertEquals(OrderStatus.PENDING, order.status)
        // 4 × 2500 = 10000, más 1500 de envío.
        assertEquals(Money(11_500), order.total)
        assertEquals("Calle inventada 8", order.deliveryAddress)
        assertEquals(mapOf(soap to QuantityMilli(4_000)), observe.reserved().first())
        assertEquals(movements, count("SELECT COUNT(*) FROM stock_movements"))
        assertEquals(10_000L, count("SELECT on_hand_milli FROM products WHERE id = $soap"))
        assertEquals(before + 1, changeSeq())
    }

    @Test
    fun readyKeepsReservingAndCancellingStopsAndClosesTheLines() = runBlocking {
        val soap = stocked("Jabón inventado")
        val first = ordered(OrderRequest(listOf(SaleLineRequest(soap, QuantityMilli(4_000)))))
        val second = ordered(OrderRequest(listOf(SaleLineRequest(soap, QuantityMilli(3_000)))))
        val prepare = SetOrderPreparationUseCase(runner, orders, clock)
        val cancel = CancelOrderUseCase(runner, orders, clock)

        assertEquals(OrderChangeResult.Changed, prepare(first, ready = true))
        assertEquals(mapOf(soap to QuantityMilli(7_000)), observe.reserved().first())
        assertEquals(OrderChangeResult.Changed, cancel(second))

        assertEquals(mapOf(soap to QuantityMilli(4_000)), observe.reserved().first())
        assertEquals(OrderChangeResult.NotOpen, cancel(second))
        // Cancelado, la guarda no deja borrar sus líneas ni editarlo.
        val failure = storageFailureOf { commit { database.orderDao().deleteLinesOf(second) } }
        assertEquals(StorageFailure.INVARIANT_VIOLATION, failure)
        val one = listOf(SaleLineRequest(soap, QuantityMilli(1_000)))
        val edit = OrderRequest(one, orderId = second)
        assertEquals(SaveOrderResult.OrderNotOpen, save(edit))
    }

    @Test
    fun theDetailSaysWhatEachLineReservesAndWhatIsShort() = runBlocking {
        val soap = stocked("Jabón inventado")
        ordered(OrderRequest(listOf(SaleLineRequest(soap, QuantityMilli(8_000)))))
        val id = ordered(OrderRequest(listOf(SaleLineRequest(soap, QuantityMilli(3_000)))))

        val view = requireNotNull(observe.detail(id).first())

        assertEquals(QuantityMilli(3_000), view.lines.single().reserved)
        assertEquals(QuantityMilli(1_000), view.lines.single().short)
        assertNull(observe.detail(99).first())
    }
}
