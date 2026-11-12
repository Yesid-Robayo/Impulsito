package io.github.yesidrobayo.shopledger.domain.rule.stock

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.stock.StockAdjustmentReason
import io.github.yesidrobayo.shopledger.domain.model.stock.StockKey
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementDraft
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementRecord
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementSource
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType
import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

/** La merma neta de un período, desde el libro, con sus anulaciones. */
class StockShrinkageTest {
    private val monday = requireNotNull(BusinessDate.parse("2026-11-02"))
    private val nextMonday = monday.plusDays(7)
    private val week = ReportPeriod.Week(monday)

    private fun row(
        id: Long,
        type: StockMovementType,
        quantity: Long,
        totalCost: Long,
        date: BusinessDate = monday,
        reverses: Long? = null,
    ) = StockMovementRecord(
        movement = StockMovementDraft(
            key = StockKey(1L, 1L, lotId = null),
            type = type,
            quantity = QuantityMilli(quantity),
            reversesMovementId = reverses,
        ).recordedAs(id),
        reason = if (type == StockMovementType.ADJUSTMENT) StockAdjustmentReason.EXPIRED else null,
        unitCost = Money(1_355L),
        totalCost = Money(totalCost),
        balanceAfter = QuantityMilli.ZERO,
        averageCostAfter = Money(1_355L),
        stockValueAfter = Money.ZERO,
        source = StockMovementSource.NONE,
        note = null,
        occurredAt = Instant.fromEpochMilliseconds(0L),
        businessDate = date,
        createdAt = Instant.fromEpochMilliseconds(0L),
    )

    @Test
    fun `the example of the rules accumulates 4065, 6775 and 5420`() {
        val opening = row(1L, StockMovementType.OPENING_BALANCE, 11_000L, 14_905L)
        val expired = row(2L, StockMovementType.ADJUSTMENT, -3_000L, -4_065L)
        val missing = row(3L, StockMovementType.ADJUSTMENT, -2_000L, -2_710L, monday.plusDays(1))
        val found = row(4L, StockMovementType.ADJUSTMENT, 1_000L, 1_355L, monday.plusDays(2))

        assertEquals(Money(4_065L), StockShrinkage.net(listOf(opening, expired), week))
        assertEquals(Money(6_775L), StockShrinkage.net(listOf(opening, expired, missing), week))
        assertEquals(
            Money(5_420L),
            StockShrinkage.net(listOf(opening, expired, missing, found), week),
        )
    }

    @Test
    fun `a surplus makes the shrinkage negative`() {
        val found = row(1L, StockMovementType.ADJUSTMENT, 2_000L, 2_710L)

        assertEquals(Money(-2_710L), StockShrinkage.net(listOf(found), week))
    }

    @Test
    fun `a voided adjustment stops counting on its own date, and its void never counts`() {
        val expired = row(1L, StockMovementType.ADJUSTMENT, -3_000L, -4_065L)
        val void = row(2L, StockMovementType.ADJUSTMENT_VOID, 3_000L, 4_065L, nextMonday, 1L)

        assertEquals(Money.ZERO, StockShrinkage.net(listOf(expired, void), week))
        assertEquals(
            Money.ZERO,
            StockShrinkage.net(listOf(expired, void), ReportPeriod.Week(nextMonday)),
        )
    }

    @Test
    fun `only adjustments inside the period count`() {
        val outside = row(1L, StockMovementType.ADJUSTMENT, -1_000L, -1_355L, nextMonday)
        val sale = row(2L, StockMovementType.SALE, -1_000L, -1_355L)

        assertEquals(Money.ZERO, StockShrinkage.net(listOf(outside, sale), week))
    }
}
