package io.github.yesidrobayo.shopledger.domain.usecase.stock

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.stock.StockAdjustmentReason
import io.github.yesidrobayo.shopledger.domain.model.stock.StockAdjustmentRequest
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException
import io.github.yesidrobayo.shopledger.domain.testing.InjectedFailure
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CatalogFixture
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Varios ajustes juntos, como un conteo dicho de varios productos: una sola transacción, un
 * movimiento nuevo por línea, y nada escrito si una línea falla.
 */
class AdjustStockAllTest {
    private val fixture = StockFixture()

    private fun count(id: Long, milli: Long) =
        StockAdjustmentRequest.Count(id, QuantityMilli(milli))

    private fun damaged(id: Long, milli: Long) =
        StockAdjustmentRequest.Change(id, StockAdjustmentReason.DAMAGED, QuantityMilli(milli))

    @Test
    fun `a count of three products leaves each one at what was counted`() = runTest {
        fixture.seedStock(id = CHEESE, units = 4_000L, unitCost = 1_000L)
        fixture.seedStock(id = SUGAR, units = 0L, unitCost = 500L)
        fixture.seedStock(id = FLOUR, units = 2_000L, unitCost = 800L)
        val writes = fixture.catalog.transactions.writeCalls

        val result = fixture.adjust.all(
            listOf(count(CHEESE, 1_000L), count(SUGAR, 1_000L), count(FLOUR, 1_000L)),
        )

        val rows = listOf(CHEESE, SUGAR, FLOUR).map { fixture.movementsOf(it).last() }
        assertEquals(StockAdjustmentsResult.Adjusted(rows.map { it.id }), result)
        assertEquals(writes + 1, fixture.catalog.transactions.writeCalls)
        for (row in rows) {
            assertEquals(StockMovementType.ADJUSTMENT, row.movement.type)
            assertEquals(StockAdjustmentReason.COUNT_CORRECTION, row.reason)
        }
        // 4 a 1 sale 3; 0 a 1 entra 1; 2 a 1 sale 1.
        assertEquals(
            listOf(QuantityMilli(-3_000L), QuantityMilli(1_000L), QuantityMilli(-1_000L)),
            rows.map { it.movement.quantity },
        )
        for (id in listOf(CHEESE, SUGAR, FLOUR)) {
            assertEquals(QuantityMilli(1_000L), fixture.product(id).onHand)
        }
        // 3 unidades a 1.000 que salen del queso valen 3.000.
        assertEquals(Money(-3_000L), rows[0].totalCost)
    }

    @Test
    fun `damaged goods of two products each go out with their reason`() = runTest {
        fixture.seedStock(id = CHEESE, units = 4_000L, unitCost = 1_000L)
        fixture.seedStock(id = FLOUR, units = 2_000L, unitCost = 800L)

        fixture.adjust.all(listOf(damaged(CHEESE, 1_000L), damaged(FLOUR, 2_000L)))

        assertEquals(QuantityMilli(3_000L), fixture.product(CHEESE).onHand)
        assertEquals(QuantityMilli(0L), fixture.product(FLOUR).onHand)
        assertEquals(StockAdjustmentReason.DAMAGED, fixture.movementsOf(FLOUR).last().reason)
    }

    @Test
    fun `the same product twice sees the first adjustment`() = runTest {
        fixture.seedStock(id = CHEESE, units = 4_000L, unitCost = 1_000L)

        fixture.adjust.all(listOf(damaged(CHEESE, 1_000L), damaged(CHEESE, 1_000L)))

        assertEquals(QuantityMilli(2_000L), fixture.product(CHEESE).onHand)
        assertEquals(
            listOf(QuantityMilli(3_000L), QuantityMilli(2_000L)),
            fixture.movementsOf(CHEESE).takeLast(2).map { it.balanceAfter },
        )
    }

    @Test
    fun `a count equal to the book writes nothing for that line`() = runTest {
        fixture.seedStock(id = CHEESE, units = 4_000L, unitCost = 1_000L)
        fixture.seedStock(id = FLOUR, units = 2_000L, unitCost = 800L)
        val before = fixture.movementsOf(CHEESE).size

        val result = fixture.adjust.all(listOf(count(CHEESE, 4_000L), count(FLOUR, 1_000L)))

        assertEquals(
            StockAdjustmentsResult.Adjusted(listOf(fixture.movementsOf(FLOUR).last().id)),
            result,
        )
        assertEquals(before, fixture.movementsOf(CHEESE).size)
    }

    @Test
    fun `when every count is the book there is nothing to correct`() = runTest {
        fixture.seedStock(id = CHEESE, units = 4_000L, unitCost = 1_000L)
        val before = fixture.catalog.tables

        val result = fixture.adjust.all(listOf(count(CHEESE, 4_000L)))

        assertEquals(StockAdjustmentsResult.NoDifference, result)
        assertEquals(before, fixture.catalog.tables)
    }

    @Test
    fun `a refused second line leaves the first one unwritten`() = runTest {
        fixture.seedStock(id = CHEESE, units = 4_000L, unitCost = 1_000L)
        val before = fixture.catalog.tables

        val result = fixture.adjust.all(listOf(count(CHEESE, 1_000L), count(MISSING, 1_000L)))

        assertEquals(StockAdjustmentsResult.Rejected(1, StockAdjustmentResult.NotFound), result)
        assertEquals(before, fixture.catalog.tables)
        assertEquals(QuantityMilli(4_000L), fixture.product(CHEESE).onHand)
    }

    @Test
    fun `a failure in the second line leaves the base as it was`() = runTest {
        fixture.seedStock(id = CHEESE, units = 4_000L, unitCost = 1_000L)
        fixture.seedStock(id = FLOUR, units = 2_000L, unitCost = 800L)
        // Cuántas escrituras hace la primera línea sola, para fallar en la primera de la segunda.
        val counting = StockFixture()
        counting.seedStock(id = CHEESE, units = 4_000L, unitCost = 1_000L)
        counting.adjust.all(listOf(count(CHEESE, 1_000L)))
        val firstLineWrites = counting.catalog.database.lastTransactionWrites
        val before = fixture.catalog.tables
        fixture.catalog.database.failAtWrite = firstLineWrites + 1

        val failure = try {
            fixture.adjust.all(listOf(count(CHEESE, 1_000L), count(FLOUR, 1_000L)))
            null
        } catch (expected: InjectedFailure) {
            expected
        }

        assertTrue("La segunda línea debía fallar", failure != null)
        assertEquals(before, fixture.catalog.tables)
        assertEquals(QuantityMilli(4_000L), fixture.product(CHEESE).onHand)
        assertEquals(QuantityMilli(2_000L), fixture.product(FLOUR).onHand)
    }

    @Test
    fun `each write that fails leaves nothing`() = runTest {
        CatalogFixture.assertEachWriteFailureLeavesNothing(
            prepare = {
                seedProduct(
                    CHEESE,
                    "Queso",
                    initialCost = Money(1_000L),
                    onHand = QuantityMilli(4_000L),
                )
                seedProduct(
                    FLOUR,
                    "Harina",
                    initialCost = Money(800L),
                    onHand = QuantityMilli(2_000L),
                )
            },
            run = {
                val lines = listOf(count(CHEESE, 1_000L), damaged(FLOUR, 1_000L))
                StockFixture(this).adjust.all(lines)
            },
        )
    }

    @Test
    fun `a storage failure comes back as a result`() = runTest {
        fixture.seedStock(id = CHEESE, units = 4_000L, unitCost = 1_000L)
        fixture.catalog.database.failAtWrite = 1
        fixture.catalog.database.failure = { StorageFailureException(StorageFailure.DISK_FULL) }

        val result = fixture.adjust.all(listOf(count(CHEESE, 1_000L)))

        assertEquals(StockAdjustmentsResult.StorageFailed(StorageFailure.DISK_FULL), result)
    }

    @Test
    fun `at least one adjustment is needed`() = runTest {
        val failure = try {
            fixture.adjust.all(emptyList())
            null
        } catch (expected: IllegalArgumentException) {
            expected
        }
        assertTrue("Una lista vacía no es un ajuste", failure != null)
    }

    private companion object {
        const val CHEESE = 1L
        const val SUGAR = 2L
        const val FLOUR = 3L
        const val MISSING = 999L
    }
}
