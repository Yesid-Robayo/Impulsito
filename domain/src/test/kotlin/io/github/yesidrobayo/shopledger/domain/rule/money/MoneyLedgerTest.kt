package io.github.yesidrobayo.shopledger.domain.rule.money

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Checked
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyMovement
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyMovementDraft
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyMovementType
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyOrigin
import io.github.yesidrobayo.shopledger.domain.model.region.RecordTime
import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Las sumas del libro de dinero, con el ejemplo de su regla y cadenas rotas escritas a mano. */
class MoneyLedgerTest {
    private val at = Instant.parse("2026-03-15T15:00:00Z")
    private val time = RecordTime(at, requireNotNull(BusinessDate.parse("2026-03-15")))

    private fun row(
        id: Long,
        account: Long,
        type: MoneyMovementType,
        amount: Long,
        after: Long,
        origin: MoneyOrigin = MoneyOrigin.None,
    ) = MoneyMovement(
        id,
        MoneyMovementDraft(account, type, Money(amount), origin),
        Money(after),
        time,
        at,
    )

    /** El ejemplo de la regla del libro de dinero, en la cuenta 1. */
    private val example = listOf(
        row(1, 1, MoneyMovementType.OPENING_BALANCE, 50_000, 50_000),
        row(2, 1, MoneyMovementType.SALE_RECEIPT, 12_000, 62_000, MoneyOrigin.SalePayment(1)),
        row(3, 1, MoneyMovementType.EXPENSE_PAYMENT, -3_000, 59_000, MoneyOrigin.Expense(1)),
        row(4, 1, MoneyMovementType.OWNER_DRAW, -10_000, 49_000),
    )

    @Test
    fun `the example sums 49000 and follows its chain whatever the given order`() {
        val shuffled = example.reversed()

        assertEquals(mapOf(1L to Money(49_000)), MoneyLedger.balanceByAccount(shuffled))
        assertEquals(
            mapOf(
                1L to Money(50_000),
                2L to Money(62_000),
                3L to Money(59_000),
                4L to Money(49_000),
            ),
            MoneyLedger.balancesAfter(shuffled),
        )
        assertEquals(listOf(1L, 2L, 3L, 4L), MoneyLedger.inOrder(shuffled).map { it.id })
        assertTrue(MoneyLedger.brokenAccounts(mapOf(1L to Money(49_000)), shuffled).isEmpty())
    }

    @Test
    fun `each account keeps its own balance, never added to another`() {
        val rows = example + row(5, 2, MoneyMovementType.OPENING_BALANCE, 7, 7) +
            row(6, 1, MoneyMovementType.MOVEMENT_VOID, 10_000, 59_000, MoneyOrigin.Void(4))

        assertEquals(
            mapOf(1L to Money(59_000), 2L to Money(7)),
            MoneyLedger.balanceByAccount(rows),
        )
        assertEquals(Money(59_000), MoneyLedger.balancesAfter(rows)[6])
    }

    @Test
    fun `two rows with the same id are a broken ledger`() {
        assertThrows(IllegalArgumentException::class.java) {
            MoneyLedger.inOrder(example + row(4, 1, MoneyMovementType.OWNER_DRAW, -1, 48_999))
        }
    }

    @Test
    fun `the next balance adds, and an overflow is a typed rejection`() {
        assertEquals(Checked.Value(Money(62_000)), MoneyLedger.next(Money(50_000), Money(12_000)))
        assertEquals(Checked.Overflow, MoneyLedger.next(Money(Long.MAX_VALUE), Money(1)))
    }

    @Test
    fun `a changed header, a broken chain or a missing account are flagged`() {
        assertEquals(setOf(1L), MoneyLedger.brokenAccounts(mapOf(1L to Money(49_001)), example))
        val chain = example.dropLast(1) + row(4, 1, MoneyMovementType.OWNER_DRAW, -10_000, 50_000)
        assertEquals(setOf(1L), MoneyLedger.brokenAccounts(mapOf(1L to Money(49_000)), chain))
        assertEquals(setOf(1L), MoneyLedger.brokenAccounts(emptyMap(), example))
        // Una cuenta sin movimientos y con saldo cero está sana; con saldo, no.
        assertTrue(MoneyLedger.brokenAccounts(mapOf(9L to Money.ZERO), emptyList()).isEmpty())
        assertEquals(setOf(9L), MoneyLedger.brokenAccounts(mapOf(9L to Money(1)), emptyList()))
    }

    @Test
    fun `a transfer group needs exactly one part out and one part in`() {
        val complete = listOf(
            row(
                1,
                1,
                MoneyMovementType.TRANSFER_OUT,
                -30_000,
                -30_000,
                MoneyOrigin.Transfer("a", null),
            ),
            row(
                2,
                2,
                MoneyMovementType.TRANSFER_IN,
                30_000,
                30_000,
                MoneyOrigin.Transfer("a", null),
            ),
        )
        val lonely =
            row(3, 1, MoneyMovementType.TRANSFER_OUT, -5, -30_005, MoneyOrigin.Transfer("b", null))
        val doubled = listOf(
            row(4, 1, MoneyMovementType.TRANSFER_IN, 5, -30_000, MoneyOrigin.Transfer("c", null)),
            row(5, 2, MoneyMovementType.TRANSFER_IN, 5, 30_005, MoneyOrigin.Transfer("c", null)),
        )

        assertTrue(MoneyLedger.brokenTransfers(complete + example).isEmpty())
        assertEquals(setOf("b", "c"), MoneyLedger.brokenTransfers(complete + lonely + doubled))
    }
}
