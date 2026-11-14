package io.github.yesidrobayo.shopledger.domain.usecase.sale

import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.testing.FakeHeldSaleRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakePriceHistoryRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakeProductRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakeTransactionRunner
import io.github.yesidrobayo.shopledger.domain.testing.FixedClock
import io.github.yesidrobayo.shopledger.domain.testing.InMemoryDatabase
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Los extras en la espera: viajan con el enlace a su línea, un enlace que no vale no se guarda, y
 * al retomar, los extras de una línea que ya no se vende se van con ella y se cuentan. Productos
 * inventados.
 */
class HeldSaleExtrasTest {
    private val database = InMemoryDatabase()
    private val transactions = FakeTransactionRunner(database)
    private val clock = FixedClock.atEpochMillis(SaleWorld.NOW_MILLIS)
    private val products = FakeProductRepository(database)
    private val heldSales = FakeHeldSaleRepository(database)
    private val priceHistory = FakePriceHistoryRepository(database)

    private fun hold() = HoldSaleUseCase(transactions, heldSales, products, { clock.now() })

    private fun resume() = ResumeHeldSaleUseCase(transactions, heldSales, products, priceHistory)

    init {
        database.seed {
            it.copy(
                products = listOf(
                    product(AREPA, salePrice = 4_000),
                    product(CHEESE, salePrice = 1_000),
                    product(TEA, salePrice = 2_000),
                ).associateBy { product -> product.id },
            )
        }
    }

    private fun line(productId: Long, milli: Long = 1_000, parentIndex: Int? = null) =
        SaleLineRequest(productId, QuantityMilli(milli), parentIndex = parentIndex)

    private val withExtras = listOf(
        line(AREPA, 2_000),
        line(CHEESE, 2_000, parentIndex = 0),
        line(TEA),
        line(CHEESE, parentIndex = 2),
    )

    private suspend fun held(lines: List<SaleLineRequest>): Long =
        (hold()(HoldSaleRequest(lines, label = null)) as HoldSaleResult.Held).heldSaleId

    @Test
    fun extrasTravelWithTheirLinkAndComeBackTheSame() = runTest {
        val id = held(withExtras)

        val stored = database.tables.value.heldSales.getValue(id).lines
        assertEquals(listOf(null, 0, null, 2), stored.map { it.parentIndex })

        val resumed = (resume()(id) as ResumeHeldSaleResult.Resumed).sale
        assertEquals(withExtras, resumed.lines)
        assertEquals(0, resumed.removedProducts)
    }

    @Test
    fun anExtraWithoutAnEarlierMainLineIsNotHeld() = runTest {
        val result = hold()(HoldSaleRequest(listOf(line(CHEESE, parentIndex = 0)), label = null))

        assertEquals(HoldSaleResult.LineRejected(0), result)
        assertTrue(database.tables.value.heldSales.isEmpty())
    }

    @Test
    fun anArchivedMainLineLeavesWithItsExtrasAndBothAreCounted() = runTest {
        val id = held(withExtras)
        archive(AREPA)

        val resumed = (resume()(id) as ResumeHeldSaleResult.Resumed).sale

        assertEquals(listOf(line(TEA), line(CHEESE, parentIndex = 0)), resumed.lines)
        assertEquals(2, resumed.removedProducts)
    }

    @Test
    fun anArchivedExtraLeavesAloneAndItsLineStays() = runTest {
        val id = held(withExtras)
        archive(CHEESE)

        val resumed = (resume()(id) as ResumeHeldSaleResult.Resumed).sale

        assertEquals(listOf(line(AREPA, 2_000), line(TEA)), resumed.lines)
        assertEquals(2, resumed.removedProducts)
    }

    private fun archive(productId: Long) = database.seed { tables ->
        val archived = tables.products.getValue(productId).copy(archivedAt = SaleWorld.EPOCH)
        tables.copy(products = tables.products + (productId to archived))
    }

    private companion object {
        const val AREPA = 1L
        const val CHEESE = 2L
        const val TEA = 3L
    }
}
