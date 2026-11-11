package io.github.yesidrobayo.shopledger.domain.usecase.sale

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.NewPriceChange
import io.github.yesidrobayo.shopledger.domain.model.catalog.PriceChangeSource
import io.github.yesidrobayo.shopledger.domain.model.sale.HeldSaleLine
import io.github.yesidrobayo.shopledger.domain.model.sale.LineDiscount
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.testing.FakeHeldSaleRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakePriceHistoryRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakeProductRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakeTransactionRunner
import io.github.yesidrobayo.shopledger.domain.testing.FixedClock
import io.github.yesidrobayo.shopledger.domain.testing.InMemoryDatabase
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Las ventas en espera sin base: guardar, retomar, renombrar y descartar no tocan stock, libro ni
 * dinero, y se confirman sin contar como cambio sin respaldar. Retomar respeta los precios escritos
 * a mano y cuenta los que cambiaron. Productos y montos inventados.
 */
class HeldSaleUseCasesTest {
    private val database = InMemoryDatabase()
    private val transactions = FakeTransactionRunner(database)
    private var clock = FixedClock.atEpochMillis(SaleWorld.NOW_MILLIS)
    private val products = FakeProductRepository(database)
    private val priceHistory = FakePriceHistoryRepository(database)
    private val heldSales = FakeHeldSaleRepository(database)

    private fun hold() = HoldSaleUseCase(transactions, heldSales, products) { clock.now() }

    private fun resume() = ResumeHeldSaleUseCase(transactions, heldSales, products, priceHistory)

    private fun rename() = RenameHeldSaleUseCase(transactions, heldSales) { clock.now() }

    private fun discard() = DiscardHeldSaleUseCase(transactions, heldSales)

    init {
        database.seed {
            it.copy(
                products = listOf(
                    product(1, salePrice = 12_345),
                    product(2, salePrice = 900, unitId = SaleWorld.KILOGRAM),
                    product(3, salePrice = 4_000, archived = true),
                ).associateBy { product -> product.id },
            )
        }
    }

    private fun line(productId: Long, milli: Long, price: Long? = null) =
        SaleLineRequest(productId, QuantityMilli(milli), price?.let(::Money))

    private suspend fun held(vararg lines: SaleLineRequest, label: String? = null): Long {
        val result = hold()(HoldSaleRequest(lines.toList(), label))
        return (result as HoldSaleResult.Held).heldSaleId
    }

    @Test
    fun holdingASaleTouchesNoStockNorMoneyAndIsNotAChange() = runTest {
        val before = database.tables.value

        val id = held(line(1, 2_000), line(2, 1_500, price = 1_000), label = "Mesa 4")

        val after = database.tables.value
        // Una espera no es un hecho del negocio: confirma sin contar.
        assertEquals(1, transactions.uncountedCommits)
        assertEquals(0, transactions.commits)
        assertEquals(before.changeSeq, after.changeSeq)
        assertEquals(before.movements, after.movements)
        assertEquals(before.products, after.products)
        val sale = after.heldSales.getValue(id)
        assertEquals("Mesa 4", sale.label)
        assertEquals(clock.now(), sale.createdAt)
        assertEquals(
            listOf(
                HeldSaleLine(1, SaleWorld.UNIT, QuantityMilli(2_000), null, Money.ZERO),
                HeldSaleLine(2, SaleWorld.KILOGRAM, QuantityMilli(1_500), Money(1_000), Money.ZERO),
            ),
            sale.lines,
        )
    }

    @Test
    fun discardingIsAlsoNotAChange() = runTest {
        val id = held(line(1, 1_000))
        val before = database.tables.value.changeSeq

        assertEquals(HeldSaleChangeResult.Changed, discard()(id))

        assertTrue(database.tables.value.heldSales.isEmpty())
        assertEquals(before, database.tables.value.changeSeq)
        assertEquals(2, transactions.uncountedCommits)
        assertEquals(HeldSaleChangeResult.NotFound, discard()(id))
    }

    @Test
    fun theLabelIsTrimmedBlankIsNoNameAndItHoldsUpToFortyCharacters() = runTest {
        assertNull(heldLabel(held(line(1, 1_000), label = "   ")))
        assertEquals("Mesa 4", heldLabel(held(line(1, 1_000), label = "  Mesa 4 ")))
        // Un emoji cuenta un carácter, como en la base.
        val forty = "🍽" + "x".repeat(39)
        assertEquals(forty, heldLabel(held(line(1, 1_000), label = forty)))
        val before = database.tables.value

        val tooLong = hold()(HoldSaleRequest(listOf(line(1, 1_000)), "x".repeat(41)))

        assertEquals(HoldSaleResult.LabelTooLong, tooLong)
        assertEquals(before, database.tables.value)
    }

    private fun heldLabel(id: Long): String? = database.tables.value.heldSales.getValue(id).label

    @Test
    fun aPercentDiscountIsKeptAsTheAmountItWasWorth() = runTest {
        // El 12,50 % de 12345 es 1543.
        val percent = line(1, 1_000).copy(discount = LineDiscount.Percent(BasisPoints(1_250)))
        // Un monto mayor que la línea se guarda igual: lo escrito no se pierde.
        val amount = line(2, 1_000).copy(discount = LineDiscount.Amount(Money(5_000)))

        val id = held(percent, amount)

        val lines = database.tables.value.heldSales.getValue(id).lines
        assertEquals(listOf(Money(1_543), Money(5_000)), lines.map { it.discount })
    }

    @Test
    fun whatCannotBeHeldIsRejectedWithoutWriting() = runTest {
        val before = database.tables.value
        val many = List(201) { line(1, 1_000) }

        assertEquals(HoldSaleResult.EmptySale, hold()(HoldSaleRequest(emptyList(), null)))
        assertEquals(HoldSaleResult.TooManyLines, hold()(HoldSaleRequest(many, null)))
        assertEquals(
            HoldSaleResult.LineRejected(1),
            hold()(HoldSaleRequest(listOf(line(1, 1_000), line(99, 1_000)), null)),
        )
        assertEquals(
            HoldSaleResult.LineRejected(0),
            hold()(HoldSaleRequest(listOf(line(1, 0)), null)),
        )
        assertEquals(
            HoldSaleResult.LineRejected(0),
            hold()(HoldSaleRequest(listOf(line(1, 1_000, price = -1)), null)),
        )

        assertEquals(before, database.tables.value)
    }

    @Test
    fun aResumedSaleIsHeldAgainOverItselfKeepingWhenItStarted() = runTest {
        val id = held(line(1, 1_000), label = "Mesa 4")
        val started = clock.now()
        clock = FixedClock(started + 5.minutes)

        val again = hold()(HoldSaleRequest(listOf(line(2, 250)), "Mesa 5", heldSaleId = id))

        assertEquals(HoldSaleResult.Held(id), again)
        val sale = database.tables.value.heldSales.getValue(id)
        assertEquals(1, database.tables.value.heldSales.size)
        assertEquals("Mesa 5", sale.label)
        assertEquals(started, sale.createdAt)
        assertEquals(clock.now(), sale.updatedAt)
        assertEquals(listOf(2L), sale.lines.map { it.productId })
    }

    @Test
    fun aResumedSaleDiscardedMeanwhileIsHeldAsANewOne() = runTest {
        val id = held(line(1, 1_000))
        discard()(id)

        val again = hold()(HoldSaleRequest(listOf(line(1, 2_000)), null, heldSaleId = id))

        val newId = (again as HoldSaleResult.Held).heldSaleId
        assertTrue(newId != id)
        assertEquals(setOf(newId), database.tables.value.heldSales.keys)
    }

    @Test
    fun resumingKeepsHandWrittenPricesAndCountsTheOnesThatChanged() = runTest {
        val writtenDiscount = line(1, 1_000).copy(discount = LineDiscount.Amount(Money(345)))
        val id = held(writtenDiscount, line(2, 1_500, price = 1_000), line(1, 2_000))
        clock = FixedClock(clock.now() + 10.minutes)
        // El precio del producto 1 cambió después de guardar; el del 2 se escribió a mano.
        changePrice(1, 12_345, 13_000)
        changePrice(2, 900, 950)
        val writes = transactions.writeCalls

        val result = resume()(id)

        val sale = (result as ResumeHeldSaleResult.Resumed).sale
        assertEquals(id, sale.heldSaleId)
        assertEquals(
            listOf(
                SaleLineRequest(1, QuantityMilli(1_000), null, LineDiscount.Amount(Money(345))),
                SaleLineRequest(2, QuantityMilli(1_500), Money(1_000), LineDiscount.None),
                SaleLineRequest(1, QuantityMilli(2_000), null, LineDiscount.None),
            ),
            sale.lines,
        )
        // Las dos líneas del producto 1 toman el precio nuevo; la escrita a mano, no.
        assertEquals(2, sale.changedPrices)
        assertEquals(0, sale.removedProducts)
        // Retomar solo lee: la espera sigue guardada hasta confirmar o descartar.
        assertEquals(setOf(id), database.tables.value.heldSales.keys)
        assertEquals(writes, transactions.writeCalls)
    }

    @Test
    fun aPriceChangedBeforeHoldingDoesNotCount() = runTest {
        changePrice(1, 12_000, 12_345)
        clock = FixedClock(clock.now() + 1.minutes)
        val id = held(line(1, 1_000))

        val sale = (resume()(id) as ResumeHeldSaleResult.Resumed).sale

        assertEquals(0, sale.changedPrices)
    }

    @Test
    fun anArchivedProductIsLeftOutAndCounted() = runTest {
        val id = held(line(1, 1_000), line(3, 1_000))

        val sale = (resume()(id) as ResumeHeldSaleResult.Resumed).sale

        assertEquals(listOf(1L), sale.lines.map { it.productId })
        assertEquals(1, sale.removedProducts)
    }

    @Test
    fun aMissingHeldSaleCannotBeResumed() = runTest {
        assertEquals(ResumeHeldSaleResult.NotFound, resume()(42))
    }

    @Test
    fun renamingChangesOnlyTheName() = runTest {
        val id = held(line(1, 1_000), label = "Mesa 4")
        val lines = database.tables.value.heldSales.getValue(id).lines
        val changes = database.tables.value.changeSeq

        assertEquals(HeldSaleChangeResult.Changed, rename()(id, " Mesa 7 "))
        assertEquals("Mesa 7", heldLabel(id))
        assertEquals(HeldSaleChangeResult.Changed, rename()(id, ""))
        assertNull(heldLabel(id))
        assertEquals(HeldSaleChangeResult.LabelTooLong, rename()(id, "x".repeat(41)))
        assertEquals(HeldSaleChangeResult.NotFound, rename()(99, "Mesa 1"))

        assertEquals(lines, database.tables.value.heldSales.getValue(id).lines)
        assertEquals(changes, database.tables.value.changeSeq)
    }

    @Test
    fun theListFollowsEveryChange() = runTest {
        val first = held(line(1, 1_000))
        val second = held(line(2, 500))
        discard()(first)

        val listed = ObserveHeldSalesUseCase(heldSales)().first()

        assertEquals(listOf(second), listed.map { it.id })
    }

    @Test
    fun aRestoreInProgressLeavesTheSaleWhereItWas() = runTest {
        transactions.restoreInProgress = true

        val result = hold()(HoldSaleRequest(listOf(line(1, 1_000)), null))

        assertEquals(HoldSaleResult.StorageFailed(StorageFailure.RESTORE_IN_PROGRESS), result)
        assertEquals(
            HeldSaleChangeResult.StorageFailed(StorageFailure.RESTORE_IN_PROGRESS),
            discard()(1),
        )
        assertEquals(
            HeldSaleChangeResult.StorageFailed(StorageFailure.RESTORE_IN_PROGRESS),
            rename()(1, null),
        )
    }

    private suspend fun changePrice(productId: Long, old: Long, new: Long) {
        transactions.write {
            priceHistory.append(
                NewPriceChange(
                    productId = productId,
                    priceListId = SaleWorld.DEFAULT_PRICE_LIST,
                    oldPrice = Money(old),
                    newPrice = Money(new),
                    source = PriceChangeSource.MANUAL,
                    batchId = null,
                    bulkRule = null,
                    occurredAt = clock.now(),
                    businessDate = BusinessDate(LocalDate(2026, 11, 2)),
                ),
            )
            TxResult.Commit(Unit)
        }
    }
}
