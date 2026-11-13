package io.github.yesidrobayo.shopledger.domain.rule.money

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyMovement
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyMovementDraft
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyMovementType
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyOrigin
import io.github.yesidrobayo.shopledger.domain.model.region.RecordTime
import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** El extracto de una cuenta: días, orden del libro, filas anuladas y qué se anula desde la cuenta. */
class MoneyStatementTest {
    private val at = Instant.parse("2026-03-15T15:00:00Z")
    private val day1 = requireNotNull(BusinessDate.parse("2026-03-14"))
    private val day2 = requireNotNull(BusinessDate.parse("2026-03-15"))

    private fun row(
        id: Long,
        type: MoneyMovementType,
        amount: Long,
        after: Long,
        day: BusinessDate,
        origin: MoneyOrigin = MoneyOrigin.None,
    ) = MoneyMovement(
        id,
        MoneyMovementDraft(1, type, Money(amount), origin),
        Money(after),
        RecordTime(at, day),
        at,
    )

    @Test
    fun `days go newest first and each day follows the ledger backwards, not the clock`() {
        // El libro manda: la fila 3 se escribió después aunque su día sea el anterior.
        val movements = listOf(
            row(1, MoneyMovementType.OPENING_BALANCE, 50_000, 50_000, day1),
            row(2, MoneyMovementType.OWNER_DRAW, -10_000, 40_000, day2),
            row(
                3,
                MoneyMovementType.SALE_RECEIPT,
                12_000,
                52_000,
                day1,
                MoneyOrigin.SalePayment(1),
            ),
            row(4, MoneyMovementType.OTHER_INCOME, 1_000, 53_000, day2),
        )

        val days = MoneyStatement.byDay(movements.shuffled())

        assertEquals(listOf(day2, day1), days.map { it.date })
        assertEquals(listOf(4L, 2L), days[0].lines.map { it.movement.id })
        assertEquals(listOf(3L, 1L), days[1].lines.map { it.movement.id })
    }

    @Test
    fun `a compensated row stays and says so, and only manual rows still in force are voidable`() {
        val movements = listOf(
            row(1, MoneyMovementType.OPENING_BALANCE, 50_000, 50_000, day1),
            row(2, MoneyMovementType.OWNER_DRAW, -10_000, 40_000, day1),
            row(3, MoneyMovementType.MOVEMENT_VOID, 10_000, 50_000, day2, MoneyOrigin.Void(2)),
            row(
                4,
                MoneyMovementType.SALE_RECEIPT,
                12_000,
                62_000,
                day2,
                MoneyOrigin.SalePayment(1),
            ),
        )

        val lines = MoneyStatement.byDay(movements).flatMap { it.lines }.associateBy {
            it.movement.id
        }

        assertTrue(requireNotNull(lines[2]).voided)
        assertFalse(requireNotNull(lines[2]).voidable)
        assertTrue(requireNotNull(lines[1]).voidable)
        // La anulación no se anula, y el cobro de una venta se anula desde la venta.
        assertFalse(requireNotNull(lines[3]).voidable)
        assertFalse(requireNotNull(lines[4]).voidable)
        assertFalse(requireNotNull(lines[4]).voided)
    }

    @Test
    fun `an account without movements has an empty statement`() {
        assertTrue(MoneyStatement.byDay(emptyList()).isEmpty())
    }
}
