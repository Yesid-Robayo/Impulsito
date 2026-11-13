package io.github.yesidrobayo.shopledger.domain.usecase.money

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.money.ManualMoneyInputError
import io.github.yesidrobayo.shopledger.domain.model.money.ManualMoneyKind
import io.github.yesidrobayo.shopledger.domain.model.money.ManualMoneyRequest
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccountDraft
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccountKind
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyMovementDraft
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyMovementType
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyOrigin
import io.github.yesidrobayo.shopledger.domain.model.region.RecordTime
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Retiros y aportes de la persona dueña: mueven la cuenta en una sola transacción y nada más. Montos
 * y notas inventados, con valores a mano.
 */
class RecordManualMoneyUseCaseTest {
    private val world = MoneyWorld()

    private fun record() = RecordManualMoneyUseCase(
        world.transactions,
        world.settings,
        world.accounts,
        world.writer,
        world.clock,
    )

    private suspend fun bank(opening: Long): Long = (
        world.save()(
            null,
            MoneyAccountDraft("Banco", MoneyAccountKind.BANK, null, Money(opening)),
        ) as SaveMoneyAccountResult.Saved
        ).accountId

    @Test
    fun `a draw of 300000 lowers the account and nothing else`() = runTest {
        val id = bank(805_000)

        val result =
            record()(ManualMoneyRequest(id, ManualMoneyKind.OWNER_DRAW, Money(300_000), " Casa "))

        val recorded = result as RecordManualMoneyResult.Recorded
        assertEquals(Money(505_000), recorded.balanceAfter)
        val movement = requireNotNull(world.ledger.findById(recorded.movementId))
        assertEquals(MoneyMovementType.OWNER_DRAW, movement.type)
        assertEquals(Money(-300_000), movement.amount)
        assertEquals("Casa", movement.draft.note)
        assertEquals(MoneyWorld.NOW, movement.time.occurredAt)
        // Solo la cuenta y su libro cambian: ningún gasto ni venta nace de un retiro.
        assertTrue(world.database.tables.value.expenses.isEmpty())
        assertEquals(2, world.transactions.commits)
    }

    @Test
    fun `a contribution raises the account, and a blank note is not kept`() = runTest {
        val id = bank(0)

        val recorded = record()(
            ManualMoneyRequest(id, ManualMoneyKind.OWNER_CONTRIBUTION, Money(40_000), "  "),
        ) as RecordManualMoneyResult.Recorded

        assertEquals(Money(40_000), recorded.balanceAfter)
        assertNull(world.ledger.findById(recorded.movementId)?.draft?.note)
    }

    @Test
    fun `an insufficient balance is allowed and leaves the account negative`() = runTest {
        val recorded = record()(
            ManualMoneyRequest(world.cashId, ManualMoneyKind.OWNER_DRAW, Money(1_000)),
        ) as RecordManualMoneyResult.Recorded

        assertEquals(Money(-1_000), recorded.balanceAfter)
        assertEquals(listOf(MoneyWorld.NOW), world.settings.lockCalls)
    }

    @Test
    fun `invalid input, a missing or archived account and an overflow write nothing`() = runTest {
        val id = bank(0)
        assertEquals(
            RecordManualMoneyResult.Invalid(
                setOf(
                    ManualMoneyInputError.AMOUNT_NOT_POSITIVE,
                    ManualMoneyInputError.NOTE_TOO_LONG,
                ),
            ),
            record()(ManualMoneyRequest(id, ManualMoneyKind.OWNER_DRAW, Money(0), "x".repeat(201))),
        )
        assertEquals(
            RecordManualMoneyResult.Invalid(setOf(ManualMoneyInputError.AMOUNT_TOO_LARGE)),
            record()(
                ManualMoneyRequest(id, ManualMoneyKind.OWNER_DRAW, Money(1_000_000_000_000_001)),
            ),
        )
        assertEquals(
            RecordManualMoneyResult.AccountNotFound,
            record()(ManualMoneyRequest(99, ManualMoneyKind.OWNER_DRAW, Money(1))),
        )
        world.archive()(id)
        assertEquals(
            RecordManualMoneyResult.AccountArchived,
            record()(ManualMoneyRequest(id, ManualMoneyKind.OWNER_DRAW, Money(1))),
        )
        // Un saldo al borde de `Long`, sembrado con el escritor: el aporte ya no cabe.
        world.transactions.write {
            TxResult.Commit(
                world.writer.append(
                    MoneyMovementDraft(
                        world.cashId,
                        MoneyMovementType.OPENING_BALANCE,
                        Money(Long.MAX_VALUE - 5),
                        MoneyOrigin.None,
                    ),
                    RecordTime(MoneyWorld.NOW, requireNotNull(BusinessDate.parse("2026-03-15"))),
                    MoneyWorld.NOW,
                ),
            )
        }
        val before = world.database.tables.value
        assertEquals(
            RecordManualMoneyResult.Overflow,
            record()(
                ManualMoneyRequest(world.cashId, ManualMoneyKind.OWNER_CONTRIBUTION, Money(6)),
            ),
        )
        assertEquals(before, world.database.tables.value)
    }

    @Test
    fun `an unconfirmed currency, an unknown zone or a storage failure write nothing`() = runTest {
        val unconfirmed = MoneyWorld(currencyConfirmed = false)
        assertEquals(
            RecordManualMoneyResult.CurrencyNotConfirmed,
            RecordManualMoneyUseCase(
                unconfirmed.transactions,
                unconfirmed.settings,
                unconfirmed.accounts,
                unconfirmed.writer,
                unconfirmed.clock,
            )(ManualMoneyRequest(unconfirmed.cashId, ManualMoneyKind.OWNER_DRAW, Money(1))),
        )
        val lost = MoneyWorld(zoneId = "Nowhere/Unknown")
        assertEquals(
            RecordManualMoneyResult.UnknownTimeZone("Nowhere/Unknown"),
            RecordManualMoneyUseCase(
                lost.transactions,
                lost.settings,
                lost.accounts,
                lost.writer,
                lost.clock,
            )(
                ManualMoneyRequest(lost.cashId, ManualMoneyKind.OWNER_DRAW, Money(1)),
            ),
        )
        world.transactions.restoreInProgress = true
        assertEquals(
            RecordManualMoneyResult.StorageFailed(StorageFailure.RESTORE_IN_PROGRESS),
            record()(ManualMoneyRequest(world.cashId, ManualMoneyKind.OWNER_DRAW, Money(1))),
        )
        assertTrue(unconfirmed.database.tables.value.moneyMovements.isEmpty())
    }
}
