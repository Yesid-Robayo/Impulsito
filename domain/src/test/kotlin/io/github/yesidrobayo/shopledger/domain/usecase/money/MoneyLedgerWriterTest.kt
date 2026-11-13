package io.github.yesidrobayo.shopledger.domain.usecase.money

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccountDraft
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccountKind
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyMovementDraft
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyMovementType
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyOrigin
import io.github.yesidrobayo.shopledger.domain.model.region.RecordTime
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.rule.money.MoneyLedger
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El escritor del libro de dinero y la anulación manual: el ejemplo de la regla del libro, la cadena
 * de saldos y lo que no se anula desde la cuenta. Montos inventados, con valores a mano.
 */
class MoneyLedgerWriterTest {
    private val world = MoneyWorld()
    private val time = RecordTime(MoneyWorld.NOW, requireNotNull(BusinessDate.parse("2026-03-15")))

    private suspend fun append(draft: MoneyMovementDraft): MoneyAppendResult =
        world.transactions.write {
            TxResult.Commit(world.writer.append(draft, time, MoneyWorld.NOW))
        }

    private suspend fun appendedId(draft: MoneyMovementDraft): Long =
        (append(draft) as MoneyAppendResult.Appended).movementId

    private fun draft(
        type: MoneyMovementType,
        amount: Long,
        origin: MoneyOrigin = MoneyOrigin.None,
    ) = MoneyMovementDraft(world.cashId, type, Money(amount), origin)

    @Test
    fun `the example of the money ledger leaves 49000 with its chain`() = runTest {
        world.settings.lockCurrency(MoneyWorld.NOW)
        appendedId(draft(MoneyMovementType.OPENING_BALANCE, 50_000))
        appendedId(draft(MoneyMovementType.SALE_RECEIPT, 12_000, MoneyOrigin.SalePayment(1)))
        appendedId(draft(MoneyMovementType.EXPENSE_PAYMENT, -3_000, MoneyOrigin.Expense(1)))
        appendedId(draft(MoneyMovementType.OWNER_DRAW, -10_000))

        val movements = world.ledger.findByAccount(world.cashId)
        assertEquals(
            listOf(50_000L, 62_000L, 59_000L, 49_000L),
            movements.map {
                it.balanceAfter.minor
            },
        )
        val tables = world.database.tables.value
        assertEquals(Money(49_000), tables.moneyAccounts.getValue(world.cashId).balance)
        assertTrue(
            MoneyLedger.brokenAccounts(
                tables.moneyAccounts.mapValues { it.value.balance },
                tables.moneyMovements.values,
            ).isEmpty(),
        )
    }

    @Test
    fun `a missing account or a balance out of range writes nothing`() = runTest {
        assertEquals(
            MoneyAppendResult.AccountNotFound,
            append(
                MoneyMovementDraft(99, MoneyMovementType.OTHER_INCOME, Money(1), MoneyOrigin.None),
            ),
        )
        appendedId(draft(MoneyMovementType.OPENING_BALANCE, Long.MAX_VALUE))

        assertEquals(MoneyAppendResult.Overflow, append(draft(MoneyMovementType.OTHER_INCOME, 1)))
        assertEquals(Money(Long.MAX_VALUE), world.accounts.findById(world.cashId)?.balance)
    }

    @Test
    fun `voiding a manual movement compensates it once, today, and keeps the original`() = runTest {
        val id = (
            world.save()(
                null,
                MoneyAccountDraft("Banco", MoneyAccountKind.BANK, null, Money(50_000)),
            ) as SaveMoneyAccountResult.Saved
            ).accountId
        val opening = world.ledger.findByAccount(id).single()

        val voided = world.void()(opening.id, "Error de captura")

        val voidId = (voided as VoidMoneyMovementResult.Voided).voidMovementId
        val void = requireNotNull(world.ledger.findById(voidId))
        assertEquals(MoneyMovementType.MOVEMENT_VOID, void.type)
        assertEquals(Money(-50_000), void.amount)
        assertEquals(MoneyOrigin.Void(opening.id), void.draft.origin)
        assertEquals("Error de captura", void.draft.note)
        assertEquals(Money.ZERO, world.accounts.findById(id)?.balance)
        assertEquals(VoidMoneyMovementResult.AlreadyVoided, world.void()(opening.id))
        assertEquals(VoidMoneyMovementResult.FromDocument, world.void()(voidId))
        assertEquals(VoidMoneyMovementResult.NotFound, world.void()(99))
    }

    @Test
    fun `movements from a document are not voided from the account`() = runTest {
        world.settings.lockCurrency(MoneyWorld.NOW)
        val sale =
            appendedId(draft(MoneyMovementType.SALE_RECEIPT, 500, MoneyOrigin.SalePayment(7)))

        assertEquals(VoidMoneyMovementResult.FromDocument, world.void()(sale))
    }

    @Test
    fun `a void that would overflow, an unknown zone or a storage failure write nothing`() =
        runTest {
            val low = appendedId(draft(MoneyMovementType.OWNER_DRAW, -Long.MAX_VALUE))
            appendedId(draft(MoneyMovementType.OWNER_CONTRIBUTION, Long.MAX_VALUE))
            appendedId(draft(MoneyMovementType.OWNER_CONTRIBUTION, Long.MAX_VALUE))

            assertEquals(VoidMoneyMovementResult.Overflow, world.void()(low))

            val lost = MoneyWorld(zoneId = "Nowhere/Unknown")
            val opening = lost.transactions.write {
                TxResult.Commit(
                    lost.writer.append(
                        MoneyMovementDraft(
                            lost.cashId,
                            MoneyMovementType.OPENING_BALANCE,
                            Money(5),
                            MoneyOrigin.None,
                        ),
                        time,
                        MoneyWorld.NOW,
                    ),
                )
            } as MoneyAppendResult.Appended
            assertEquals(
                VoidMoneyMovementResult.UnknownTimeZone("Nowhere/Unknown"),
                lost.void()(opening.movementId),
            )
            lost.transactions.restoreInProgress = true
            assertEquals(
                VoidMoneyMovementResult.StorageFailed(StorageFailure.RESTORE_IN_PROGRESS),
                lost.void()(opening.movementId),
            )
        }
}
