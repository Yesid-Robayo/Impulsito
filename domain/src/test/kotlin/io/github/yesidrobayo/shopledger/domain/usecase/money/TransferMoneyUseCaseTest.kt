package io.github.yesidrobayo.shopledger.domain.usecase.money

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccountDraft
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccountKind
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyMovementDraft
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyMovementType
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyOrigin
import io.github.yesidrobayo.shopledger.domain.model.money.TransferInputError
import io.github.yesidrobayo.shopledger.domain.model.money.TransferRequest
import io.github.yesidrobayo.shopledger.domain.model.region.RecordTime
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.rule.money.MoneyLedger
import io.github.yesidrobayo.shopledger.domain.testing.InjectedFailure
import io.github.yesidrobayo.shopledger.domain.testing.seedCashAccount
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Transferencias entre cuentas y su anulación: dos partes con un grupo, en una sola transacción, y
 * anularla anula las dos o ninguna. Montos inventados, con valores a mano.
 */
class TransferMoneyUseCaseTest {
    private val world = MoneyWorld()
    private var nextGroup = 0

    private fun transfer() = TransferMoneyUseCase(
        world.transactions,
        world.settings,
        world.accounts,
        world.writer,
        world.clock,
    ) { "00000000-0000-4000-8000-%012d".format(++nextGroup) }

    private suspend fun account(name: String, opening: Long, foreign: Boolean = false): Long = (
        world.save()(
            null,
            MoneyAccountDraft(
                name,
                MoneyAccountKind.BANK,
                if (foreign) MoneyWorld.OTHER else null,
                Money(opening),
            ),
        ) as SaveMoneyAccountResult.Saved
        ).accountId

    private fun balance(id: Long) = world.database.tables.value.moneyAccounts.getValue(id).balance

    /** Deja la cuenta con [amount] de saldo inicial, sembrado con el escritor. */
    private suspend fun seed(account: Long, amount: Long) {
        world.transactions.write {
            TxResult.Commit(
                world.writer.append(
                    MoneyMovementDraft(
                        account,
                        MoneyMovementType.OPENING_BALANCE,
                        Money(amount),
                        MoneyOrigin.None,
                    ),
                    RecordTime(MoneyWorld.NOW, requireNotNull(BusinessDate.parse("2026-03-15"))),
                    MoneyWorld.NOW,
                ),
            )
        }
    }

    /** Corre [block] y exige que termine con la falla inyectada a propósito. */
    private suspend fun injectedFailure(block: suspend () -> Unit) {
        try {
            block()
            fail("La falla inyectada no llegó")
        } catch (_: InjectedFailure) {
            // Lo esperado: la transacción se deshizo.
        }
    }

    private fun ledgerIsSound() {
        val tables = world.database.tables.value
        assertTrue(
            MoneyLedger.brokenAccounts(
                tables.moneyAccounts.mapValues { it.value.balance },
                tables.moneyMovements.values,
            ).isEmpty(),
        )
        assertTrue(MoneyLedger.brokenTransfers(tables.moneyMovements.values).isEmpty())
    }

    @Test
    fun `30000 from the bank to cash are two parts of one group`() = runTest {
        val bank = account("Banco", 50_000)

        val result = transfer()(TransferRequest(bank, world.cashId, Money(30_000), note = " Caja "))

        val done = result as TransferMoneyResult.Transferred
        val out = requireNotNull(world.ledger.findById(done.outId))
        val inbound = requireNotNull(world.ledger.findById(done.inId))
        assertEquals(MoneyMovementType.TRANSFER_OUT, out.type)
        assertEquals(Money(-30_000), out.amount)
        assertEquals(MoneyMovementType.TRANSFER_IN, inbound.type)
        assertEquals(Money(30_000), inbound.amount)
        assertEquals(MoneyOrigin.Transfer(done.groupId, null), out.draft.origin)
        assertEquals(out.draft.origin, inbound.draft.origin)
        assertEquals("Caja", inbound.draft.note)
        assertEquals(36, done.groupId.length)
        assertEquals(listOf(out, inbound), world.ledger.findByTransferGroup(done.groupId))
        assertEquals(Money(20_000), balance(bank))
        assertEquals(Money(30_000), balance(world.cashId))
        ledgerIsSound()
    }

    @Test
    fun `10000 from two decimals at 4150 enter as 415000 in another currency`() = runTest {
        val bank = account("Banco", 10_000)
        val foreign = account("Divisas", 0, foreign = true)

        val done = transfer()(
            TransferRequest(bank, foreign, Money(10_000), fxRateMicro = 4_150_000_000),
        ) as TransferMoneyResult.Transferred

        assertEquals(Money(0), balance(bank))
        assertEquals(Money(415_000), balance(foreign))
        assertEquals(
            MoneyOrigin.Transfer(done.groupId, 4_150_000_000),
            world.ledger.findById(done.inId)?.draft?.origin,
        )
        ledgerIsSound()
    }

    @Test
    fun `input errors, missing or archived accounts write nothing`() = runTest {
        val bank = account("Banco", 100)
        val foreign = account("Divisas", 0, foreign = true)
        val before = world.database.tables.value

        assertEquals(
            TransferMoneyResult.Invalid(setOf(TransferInputError.SAME_ACCOUNT)),
            transfer()(TransferRequest(bank, bank, Money(10))),
        )
        assertEquals(
            TransferMoneyResult.Invalid(setOf(TransferInputError.RATE_REQUIRED)),
            transfer()(TransferRequest(bank, foreign, Money(10))),
        )
        assertEquals(
            TransferMoneyResult.AccountNotFound,
            transfer()(TransferRequest(bank, 99, Money(10))),
        )
        assertEquals(
            TransferMoneyResult.AccountNotFound,
            transfer()(TransferRequest(99, bank, Money(10))),
        )
        world.archive()(foreign)
        assertEquals(
            TransferMoneyResult.AccountArchived,
            transfer()(TransferRequest(bank, foreign, Money(10_000), fxRateMicro = 1_000_000)),
        )
        assertEquals(before.moneyMovements, world.database.tables.value.moneyMovements)
    }

    @Test
    fun `a balance that would overflow on either side leaves no part written`() = runTest {
        val full = account("Llena", 0)
        val empty = account("Vacía", 0)
        seed(full, Long.MAX_VALUE)
        seed(empty, -Long.MAX_VALUE)
        val before = world.database.tables.value

        // Sale bien de efectivo, pero no cabe en la llena: tampoco queda la parte que salió.
        assertEquals(
            TransferMoneyResult.Overflow,
            transfer()(TransferRequest(world.cashId, full, Money(2))),
        )
        assertEquals(
            TransferMoneyResult.Overflow,
            transfer()(TransferRequest(empty, world.cashId, Money(2))),
        )
        assertEquals(before, world.database.tables.value)
    }

    @Test
    fun `a failure on the second part rolls back the first`() = runTest {
        val bank = account("Banco", 500)
        val before = world.database.tables.value
        world.database.failAtWrite = 3

        injectedFailure { transfer()(TransferRequest(bank, world.cashId, Money(100))) }

        world.database.failAtWrite = null
        assertEquals(before, world.database.tables.value)
    }

    @Test
    fun `voiding a transfer voids its two parts once, or none on a failure`() = runTest {
        val bank = account("Banco", 50_000)
        val done = transfer()(
            TransferRequest(bank, world.cashId, Money(30_000)),
        ) as TransferMoneyResult.Transferred
        val before = world.database.tables.value

        world.database.failAtWrite = 3
        injectedFailure { world.void()(done.inId) }
        world.database.failAtWrite = null
        assertEquals(before, world.database.tables.value)

        val voided = world.void()(done.inId) as VoidMoneyMovementResult.Voided
        assertEquals(Money(50_000), balance(bank))
        assertEquals(Money.ZERO, balance(world.cashId))
        assertEquals(
            MoneyOrigin.Void(done.inId),
            world.ledger.findById(voided.voidMovementId)?.draft?.origin,
        )
        assertTrue(world.ledger.isCompensated(done.outId))
        assertEquals(VoidMoneyMovementResult.AlreadyVoided, world.void()(done.outId))
        assertEquals(VoidMoneyMovementResult.FromDocument, world.void()(voided.voidMovementId))
        ledgerIsSound()
    }

    @Test
    fun `an unconfirmed currency, an unknown zone or a storage failure write nothing`() = runTest {
        val unconfirmed = MoneyWorld(currencyConfirmed = false)
        val second = unconfirmed.database.seedCashAccount(MoneyWorld.BOOK, MoneyWorld.NOW)
        assertEquals(
            TransferMoneyResult.CurrencyNotConfirmed,
            TransferMoneyUseCase(
                unconfirmed.transactions,
                unconfirmed.settings,
                unconfirmed.accounts,
                unconfirmed.writer,
                unconfirmed.clock,
            )(TransferRequest(unconfirmed.cashId, second, Money(1))),
        )
        val lost = MoneyWorld(zoneId = "Nowhere/Unknown")
        val other = lost.database.seedCashAccount(MoneyWorld.BOOK, MoneyWorld.NOW)
        assertEquals(
            TransferMoneyResult.UnknownTimeZone("Nowhere/Unknown"),
            TransferMoneyUseCase(
                lost.transactions,
                lost.settings,
                lost.accounts,
                lost.writer,
                lost.clock,
            )(
                TransferRequest(lost.cashId, other, Money(1)),
            ),
        )
        world.transactions.restoreInProgress = true
        assertEquals(
            TransferMoneyResult.StorageFailed(StorageFailure.RESTORE_IN_PROGRESS),
            transfer()(TransferRequest(world.cashId, 2, Money(1))),
        )
    }
}
