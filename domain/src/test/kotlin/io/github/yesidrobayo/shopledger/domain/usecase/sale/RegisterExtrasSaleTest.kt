package io.github.yesidrobayo.shopledger.domain.usecase.sale

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.VoidReason
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleRequest
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Vender con extras: cada extra es una línea normal enlazada a su línea principal, que paga,
 * cuesta y mueve stock como cualquier otra. Un mismo producto se repite como extra de varias
 * líneas y también suelto. Un enlace que no apunta a una principal anterior se rechaza sin leer
 * la base, y anular compensa todas las líneas. Datos inventados.
 *
 * Una arepa vale 4000; el queso, 1000 la porción, con costo 400; la salsa es un servicio de 200
 * sin stock.
 */
class RegisterExtrasSaleTest {
    private val world = newWorld()

    private fun newWorld(): SaleWorld = SaleWorld().apply {
        addMethod(method())
        addProduct(stocked(AREPA, price = 4_000, units = 10, cost = 1_500))
        addProduct(stocked(CHEESE, price = 1_000, units = 20, cost = 400))
        addProduct(
            product(
                SAUCE,
                salePrice = 200,
                stockMode = ProductStockMode.UNTRACKED,
                fixedUnitCost = 50,
            ),
        )
        addProduct(stocked(TEA, price = 2_000, units = 5, cost = 700))
    }

    /** Un producto con [units] unidades en stock, a [cost] cada una. */
    private fun stocked(id: Long, price: Long, units: Long, cost: Long) = product(
        id,
        salePrice = price,
        onHandMilli = units * 1_000,
        averageCost = cost,
        stockValue = units * cost,
    )

    private fun line(productId: Long, milli: Long, parentIndex: Int? = null) =
        SaleLineRequest(productId, QuantityMilli(milli), parentIndex = parentIndex)

    /** Dos arepas con queso y salsa, un té con queso, y una porción de queso suelta. */
    private val request = SaleRequest(
        listOf(
            line(AREPA, 2_000),
            line(CHEESE, 2_000, parentIndex = 0),
            line(SAUCE, 2_000, parentIndex = 0),
            line(TEA, 1_000),
            line(CHEESE, 1_000, parentIndex = 3),
            line(CHEESE, 1_000),
        ),
        SaleWorld.CASH,
    )

    @Test
    fun anExtraRepeatsAProductAndLinksToItsMainLine() = runTest {
        val result = world.registerSale()(request)

        val saleId = (result as RegisterSaleResult.Registered).saleId
        val lines = world.tables.lines.values.sortedBy { it.id }
        assertEquals(
            listOf(AREPA, CHEESE, SAUCE, TEA, CHEESE, CHEESE),
            lines.map { it.content.productId },
        )
        val ids = lines.map { it.id }
        assertEquals(
            listOf(null, ids[0], ids[0], null, ids[3], null),
            lines.map { it.content.parentLineId },
        )
        // 8000 + 2000 + 400 + 2000 + 1000 + 1000: cada extra cobra como una línea.
        assertEquals(Money(14_400), world.tables.sales.getValue(saleId).content.total)
        // El queso sale del libro en sus tres líneas, cada una con su fila: 2 + 1 + 1.
        val cheese = world.tables.movements.values
            .filter { it.movement.key.productId == CHEESE }
            .sortedBy { it.id }
        assertEquals(listOf(-2_000L, -1_000L, -1_000L), cheese.map { it.movement.quantity.milli })
        assertEquals(listOf(ids[1], ids[4], ids[5]), cheese.map { it.source.saleLineId })
        assertEquals(QuantityMilli(16_000), world.tables.products.getValue(CHEESE).onHand)
        // El queso costó 400 por porción; la salsa, su costo fijo de 50.
        assertEquals(Money(800), lines[1].content.lineCost)
        assertEquals(Money(100), lines[2].content.lineCost)
        assertEquals(1, world.commits)
    }

    @Test
    fun anExtraBeforeItsMainLineIsRejectedWithoutWriting() = runTest {
        val result = world.registerSale()(
            SaleRequest(listOf(line(CHEESE, 1_000, parentIndex = 1), line(AREPA, 1_000)), CASH),
        )

        assertEquals(RegisterSaleResult.LineRejected(0, LineRejection.INVALID_EXTRA), result)
        assertTrue(world.tables.sales.isEmpty())
        assertEquals(0, world.commits)
    }

    @Test
    fun anExtraOfAnExtraIsRejected() = runTest {
        val result = world.registerSale()(
            SaleRequest(
                listOf(
                    line(AREPA, 1_000),
                    line(CHEESE, 1_000, parentIndex = 0),
                    line(SAUCE, 1_000, parentIndex = 1),
                ),
                CASH,
            ),
        )

        assertEquals(RegisterSaleResult.LineRejected(2, LineRejection.INVALID_EXTRA), result)
        assertTrue(world.tables.sales.isEmpty())
    }

    @Test
    fun anExtraPointingToItselfOrOutsideTheSaleIsRejected() = runTest {
        val itself = world.registerSale()(
            SaleRequest(listOf(line(AREPA, 1_000), line(CHEESE, 1_000, parentIndex = 1)), CASH),
        )
        val outside = world.registerSale()(
            SaleRequest(listOf(line(AREPA, 1_000), line(CHEESE, 1_000, parentIndex = -1)), CASH),
        )

        assertEquals(RegisterSaleResult.LineRejected(1, LineRejection.INVALID_EXTRA), itself)
        assertEquals(RegisterSaleResult.LineRejected(1, LineRejection.INVALID_EXTRA), outside)
    }

    @Test
    fun voidingASaleWithExtrasCompensatesEveryLine() = runTest {
        val saleId = (world.registerSale()(request) as RegisterSaleResult.Registered).saleId

        val result = world.voidSale()(saleId, VoidReason.ENTRY_ERROR, null)

        assertTrue(result is VoidSaleResult.Voided)
        val sold = world.tables.movements.values.filter {
            it.movement.type == StockMovementType.SALE
        }
        val voids = world.tables.movements.values.filter {
            it.movement.type == StockMovementType.SALE_VOID
        }
        // Arepa, tres de queso y té: la salsa no lleva stock.
        assertEquals(5, sold.size)
        assertEquals(
            sold.map { it.id }.toSet(),
            voids.mapNotNull { it.movement.reversesMovementId }.toSet(),
        )
        assertEquals(QuantityMilli(20_000), world.tables.products.getValue(CHEESE).onHand)
        assertEquals(QuantityMilli(10_000), world.tables.products.getValue(AREPA).onHand)
    }

    @Test
    fun aFailureAtEachWriteLeavesNothing() = runTest {
        var failAt = 1
        while (true) {
            val fresh = newWorld()
            fresh.failAtWrite = failAt
            val failed = runCatching { fresh.registerSale()(request) }.exceptionOrNull()
            if (failed == null) {
                assertEquals(6, fresh.tables.lines.size)
                break
            }
            assertTrue("Escritura $failAt", failed is InjectedSaleFailure)
            assertTrue(fresh.tables.sales.isEmpty())
            assertTrue(fresh.tables.lines.isEmpty())
            assertTrue(fresh.tables.movements.isEmpty())
            assertEquals(QuantityMilli(20_000), fresh.tables.products.getValue(CHEESE).onHand)
            failAt++
        }
        // Cabecera, seis líneas, cinco filas con su producto y el pago.
        assertTrue("Escrituras: ${failAt - 1}", failAt > 13)
    }

    private companion object {
        const val AREPA = 10L
        const val CHEESE = 11L
        const val SAUCE = 12L
        const val TEA = 13L
        const val CASH = SaleWorld.CASH
    }
}
