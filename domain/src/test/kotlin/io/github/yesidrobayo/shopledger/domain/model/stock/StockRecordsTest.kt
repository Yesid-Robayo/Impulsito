package io.github.yesidrobayo.shopledger.domain.model.stock

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StockRecordsTest {
    private val at = Instant.fromEpochMilliseconds(1_700_000_000_000L)
    private val date = BusinessDate(LocalDate(2026, 11, 4))

    @Test
    fun `the stored names never change`() {
        assertEquals(
            listOf(
                "COUNT_CORRECTION",
                "DAMAGED",
                "EXPIRED",
                "LOST_OR_STOLEN",
                "OWN_USE",
                "GIFT_OR_SAMPLE",
                "OTHER",
            ),
            StockAdjustmentReason.entries.map { it.name },
        )
        assertEquals(
            listOf("PENDING", "SHOWN", "SUPPRESSED", "BLOCKED"),
            LowStockNotificationStatus.entries.map { it.name },
        )
    }

    @Test
    fun `a recorded movement keeps every stored value`() {
        val draft = StockMovementDraft(
            key = StockKey(productId = 3L, locationId = 1L, lotId = null),
            type = StockMovementType.ADJUSTMENT,
            quantity = QuantityMilli(-2_000L),
            reversesMovementId = null,
        )
        val new = NewStockMovement(
            movement = draft,
            reason = StockAdjustmentReason.DAMAGED,
            unitCost = Money(400L),
            totalCost = Money(-800L),
            balanceAfter = QuantityMilli(8_000L),
            averageCostAfter = Money(400L),
            stockValueAfter = Money(3_200L),
            source = StockMovementSource.NONE,
            note = null,
            occurredAt = at,
            businessDate = date,
            createdAt = at,
        )

        val recorded = new.recordedAs(11L)

        assertEquals(11L, recorded.id)
        assertEquals(draft.recordedAs(11L), recorded.movement)
        assertEquals(
            new,
            NewStockMovement(
                movement = draft,
                reason = recorded.reason,
                unitCost = recorded.unitCost,
                totalCost = recorded.totalCost,
                balanceAfter = recorded.balanceAfter,
                averageCostAfter = recorded.averageCostAfter,
                stockValueAfter = recorded.stockValueAfter,
                source = recorded.source,
                note = recorded.note,
                occurredAt = recorded.occurredAt,
                businessDate = recorded.businessDate,
                createdAt = recorded.createdAt,
            ),
        )
    }

    @Test
    fun `an alert is active until it is resolved`() {
        val alert = LowStockAlert(
            id = 1L,
            productId = 3L,
            onHandAtTrigger = QuantityMilli(1_000L),
            minStockAtTrigger = QuantityMilli(2_000L),
            notificationStatus = LowStockNotificationStatus.PENDING,
            occurredAt = at,
            businessDate = date,
            resolvedAt = null,
        )
        assertTrue(alert.isActive)
        assertFalse(alert.copy(resolvedAt = at).isActive)
    }
}
