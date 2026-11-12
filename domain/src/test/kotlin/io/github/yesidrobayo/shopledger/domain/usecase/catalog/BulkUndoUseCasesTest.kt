package io.github.yesidrobayo.shopledger.domain.usecase.catalog

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.catalog.BulkPriceAdjustment
import io.github.yesidrobayo.shopledger.domain.model.catalog.BulkPriceRule
import io.github.yesidrobayo.shopledger.domain.model.catalog.BulkUndoRevert
import io.github.yesidrobayo.shopledger.domain.model.catalog.NewPriceChange
import io.github.yesidrobayo.shopledger.domain.model.catalog.PriceChangeSource
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.domain.usecase.region.CurrencyLock
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Deshacer la última actualización masiva: qué vuelve, qué se deja y que no se deshace dos veces. */
class BulkUndoUseCasesTest {
    private val fixture = CatalogFixture().apply { seedThree() }

    @Test
    fun `undoing brings back each price still as the batch left it`() = runTest {
        fixture.applyBulk(listOf(1L, 2L, 3L), 800L, step = 100L, batch = FIRST)
        // Después del lote, el café se cambia a mano y el pan se archiva.
        fixture.write { fixture.products.updateSalePrice(2L, Money(11_000L), LATER) }
        fixture.write { fixture.products.setArchivedAt(3L, LATER, LATER) }

        val result = fixture.undo(FIRST)

        assertEquals(UndoBulkPriceResult.Undone(reverted = 1, kept = 2), result)
        assertEquals(Money(12_350L), fixture.product(1L).salePrice)
        assertEquals(Money(11_000L), fixture.product(2L).salePrice)
        assertEquals(Money(100L), fixture.product(3L).salePrice)
        val undoRows = fixture.tables.priceChanges.values.map { it.content }
            .filter { it.source == PriceChangeSource.BULK_UNDO }
        assertEquals(1, undoRows.size)
        assertEquals(UNDO, undoRows.single().batchId)
        assertEquals(Money(13_400L), undoRows.single().oldPrice)
        assertEquals(Money(12_350L), undoRows.single().newPrice)
    }

    @Test
    fun `an undone batch cannot be undone again, nor an older one`() = runTest {
        fixture.applyBulk(listOf(1L), 800L, batch = FIRST)
        fixture.applyBulk(listOf(2L), 800L, batch = SECOND)

        assertEquals(UndoBulkPriceResult.NotUndoable, fixture.undo(FIRST))
        assertEquals(UndoBulkPriceResult.Undone(1, 0), fixture.undo(SECOND))
        val before = fixture.tables
        assertEquals(UndoBulkPriceResult.NotUndoable, fixture.undo(SECOND))
        assertEquals(UndoBulkPriceResult.NotUndoable, fixture.undo(UNDO))
        assertEquals(before, fixture.tables)
    }

    @Test
    fun `with every price changed later there is nothing to revert`() = runTest {
        fixture.applyBulk(listOf(1L), 800L, batch = FIRST)
        fixture.write { fixture.products.updateSalePrice(1L, Money(15_000L), LATER) }
        val before = fixture.tables

        assertEquals(UndoBulkPriceResult.NothingToRevert(kept = 1), fixture.undo(FIRST))
        assertEquals(before, fixture.tables)
    }

    @Test
    fun `an unknown zone and a restore write nothing`() = runTest {
        // Sin zona no se aplica: el lote se escribe a mano, como lo dejaría aplicar.
        val noZone = CatalogFixture(zoneId = "Nowhere/Unknown").apply { seedThree() }
        noZone.write { noZone.products.updateSalePrice(1L, Money(13_400L), LATER) }
        noZone.write {
            noZone.priceHistory.append(
                NewPriceChange(
                    productId = 1L,
                    priceListId = 1L,
                    oldPrice = Money(12_350L),
                    newPrice = Money(13_400L),
                    source = PriceChangeSource.BULK,
                    batchId = FIRST,
                    bulkRule = BulkPriceRule(
                        BulkPriceAdjustment.Percent(BasisPoints(800L)),
                        Money(100L),
                    ),
                    occurredAt = LATER,
                    businessDate = BusinessDate(LocalDate(2026, 11, 12)),
                ),
            )
        }
        assertEquals(UndoBulkPriceResult.UnknownTimeZone, noZone.undo(FIRST))

        fixture.applyBulk(listOf(1L), 800L, batch = FIRST)
        fixture.transactions.restoreInProgress = true
        assertEquals(
            UndoBulkPriceResult.StorageFailed(StorageFailure.RESTORE_IN_PROGRESS),
            fixture.undo(FIRST),
        )
    }

    @Test
    fun `each write that fails leaves nothing`() = runTest {
        CatalogFixture.assertEachWriteFailureLeavesNothing({
            seedThree()
            runBlocking { applyBulk(listOf(1L, 2L), 800L, batch = FIRST) }
        }) { undo(FIRST) }
    }

    @Test
    fun `the offer follows the last batch and counts what would go back`() = runTest {
        val offer = ObserveBulkUndoUseCase(fixture.priceHistory, fixture.products)
        assertNull(offer().first())

        fixture.applyBulk(listOf(1L, 2L), 800L, step = 100L, batch = FIRST)
        fixture.write { fixture.products.updateSalePrice(2L, Money(11_000L), LATER) }
        val plan = requireNotNull(offer().first())
        assertEquals(FIRST, plan.batchId)
        assertEquals(listOf(BulkUndoRevert(1L, Money(13_400L), Money(12_350L))), plan.reverts)
        assertEquals(1, plan.kept)
        assertTrue(plan.canUndo)

        fixture.undo(FIRST)
        assertNull(offer().first())
    }

    /** Una escritura directa, como la dejaría otra pantalla: también en su transacción. */
    private suspend fun CatalogFixture.write(block: suspend () -> Any) {
        transactions.write {
            block()
            TxResult.Commit(Unit)
        }
    }

    private fun CatalogFixture.seedThree() {
        seedProduct(1L, "Arroz", salePrice = Money(12_350L), initialCost = Money(8_000L))
        seedProduct(2L, "Café", salePrice = Money(10_000L), initialCost = Money(9_500L))
        seedProduct(3L, "Pan", salePrice = Money(80L))
    }

    private suspend fun CatalogFixture.applyBulk(
        ids: List<Long>,
        percent: Long,
        step: Long = 0L,
        batch: Uuid,
    ): ApplyBulkPriceResult = ApplyBulkPriceUseCase(
        transactions,
        products,
        systemRows,
        priceHistory,
        BusinessCalendarSource(settings),
        CurrencyLock(settings),
        clock,
    ) { batch }(ids, BasisPoints(percent), Money(step))

    private suspend fun CatalogFixture.undo(batch: Uuid): UndoBulkPriceResult =
        UndoBulkPriceUseCase(
            transactions,
            products,
            systemRows,
            priceHistory,
            BusinessCalendarSource(settings),
            clock,
        ) { UNDO }(batch)

    private companion object {
        val FIRST: Uuid = Uuid.parse("00000000-0000-4000-8000-000000000001")
        val SECOND: Uuid = Uuid.parse("00000000-0000-4000-8000-000000000002")
        val UNDO: Uuid = Uuid.parse("00000000-0000-4000-8000-000000000003")
        val LATER: Instant = Instant.fromEpochMilliseconds(1_800_000_000_000L)
    }
}
