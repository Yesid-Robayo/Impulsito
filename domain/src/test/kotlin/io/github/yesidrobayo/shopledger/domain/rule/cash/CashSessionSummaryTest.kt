package io.github.yesidrobayo.shopledger.domain.rule.cash

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.cash.CashSession
import io.github.yesidrobayo.shopledger.domain.model.cash.NewCashSession
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyMovement
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyMovementDraft
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyMovementType
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyOrigin
import io.github.yesidrobayo.shopledger.domain.model.region.RecordTime
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Las cifras de una caja abierta, con el ejemplo de lo esperado escrito a mano: apertura de 20000,
 * ventas en efectivo de 35000, devolución de 2000, gasto de 3000 y retiro de 10000 dan 40000.
 */
class CashSessionSummaryTest {
    private val session = CashSession(
        id = SESSION,
        opening = NewCashSession(ACCOUNT, Money(20_000), AT, DATE),
        closing = null,
    )

    @Test
    fun `the expected example splits into sales, money in and money out`() {
        val figures = CashSessionSummary.of(
            session,
            listOf(
                movement(1, MoneyMovementType.SALE_RECEIPT, 35_000, MoneyOrigin.SalePayment(1)),
                movement(2, MoneyMovementType.REFUND, -2_000, MoneyOrigin.SaleReturn(1)),
                movement(3, MoneyMovementType.EXPENSE_PAYMENT, -3_000, MoneyOrigin.Expense(1)),
                movement(4, MoneyMovementType.OWNER_DRAW, -10_000, MoneyOrigin.None),
            ),
        )

        assertEquals(
            CashSessionFigures(
                opening = Money(20_000),
                sales = Money(35_000),
                cashIn = Money.ZERO,
                cashOut = Money(-15_000),
                expected = Money(40_000),
            ),
            figures,
        )
    }

    @Test
    fun `a voided sale lowers the sales, other sessions and differences do not count`() {
        val figures = requireNotNull(
            CashSessionSummary.of(
                session,
                listOf(
                    // Antes de abrir: sin sesión.
                    movement(
                        1,
                        MoneyMovementType.SALE_RECEIPT,
                        9_000,
                        MoneyOrigin.SalePayment(1),
                        null,
                    ),
                    movement(2, MoneyMovementType.SALE_RECEIPT, 5_000, MoneyOrigin.SalePayment(2)),
                    movement(3, MoneyMovementType.MOVEMENT_VOID, -5_000, MoneyOrigin.Void(2)),
                    movement(4, MoneyMovementType.OWNER_CONTRIBUTION, 4_000, MoneyOrigin.None),
                    movement(5, MoneyMovementType.CASH_DIFFERENCE, -700, MoneyOrigin.None),
                    // Un retiro anulado: vuelve a entrar.
                    movement(6, MoneyMovementType.MOVEMENT_VOID, 1_000, MoneyOrigin.Void(99)),
                    movement(
                        7,
                        MoneyMovementType.SALE_RECEIPT,
                        3_000,
                        MoneyOrigin.SalePayment(3),
                        OTHER,
                    ),
                ),
            ),
        )

        assertEquals(Money.ZERO, figures.sales)
        assertEquals(Money(5_000), figures.cashIn)
        assertEquals(Money.ZERO, figures.cashOut)
        assertEquals(Money(25_000), figures.expected)
    }

    @Test
    fun `sums that do not fit give nothing`() {
        val huge = CashSession(
            SESSION,
            NewCashSession(ACCOUNT, Money(Long.MAX_VALUE), AT, DATE),
            closing = null,
        )

        assertNull(
            CashSessionSummary.of(
                huge,
                listOf(movement(1, MoneyMovementType.OWNER_CONTRIBUTION, 1, MoneyOrigin.None)),
            ),
        )
    }

    private fun movement(
        id: Long,
        type: MoneyMovementType,
        amount: Long,
        origin: MoneyOrigin,
        sessionId: Long? = SESSION,
    ) = MoneyMovement(
        id = id,
        draft = MoneyMovementDraft(ACCOUNT, type, Money(amount), origin, cashSessionId = sessionId),
        balanceAfter = Money(amount),
        time = RecordTime(AT, DATE),
        createdAt = AT,
    )

    private companion object {
        const val SESSION = 7L
        const val OTHER = 8L
        const val ACCOUNT = 1L
        val AT: Instant = Instant.fromEpochMilliseconds(0)
        val DATE = BusinessDate(LocalDate(2026, 11, 13))
    }
}
