package io.github.yesidrobayo.shopledger.domain.usecase.cash

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccountDetails
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccountKind
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyMovementDraft
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyMovementType
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyOrigin
import io.github.yesidrobayo.shopledger.domain.model.money.NewMoneyAccount
import io.github.yesidrobayo.shopledger.domain.model.region.RecordTime
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.testing.FakeCashSessionRepository
import io.github.yesidrobayo.shopledger.domain.testing.InjectedFailure
import io.github.yesidrobayo.shopledger.domain.usecase.money.MoneyAppendResult
import io.github.yesidrobayo.shopledger.domain.usecase.money.MoneyWorld
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Abrir caja, que es opcional: la apertura nace del saldo de la cuenta, una diferencia al contar la
 * lleva a lo contado con una `CASH_DIFFERENCE` de la sesión, hay una sola abierta por cuenta, y desde
 * que abre cada movimiento de la cuenta cae en la sesión. Montos en unidad menor de una moneda
 * reservada para pruebas, con dos decimales.
 */
class OpenCashSessionUseCaseTest {
    private val world = MoneyWorld()
    private val sessions = FakeCashSessionRepository(world.database)

    private fun open() = OpenCashSessionUseCase(
        world.transactions,
        world.settings,
        world.accounts,
        world.ledger,
        sessions,
        world.clock,
    )

    private fun tables() = world.database.tables.value

    /** Deja la caja con [amount] por un saldo inicial, como lo haría su caso de uso. */
    private suspend fun fund(amount: Long) {
        world.transactions.write {
            world.writer.append(
                MoneyMovementDraft(
                    world.cashId,
                    MoneyMovementType.OPENING_BALANCE,
                    Money(amount),
                    MoneyOrigin.None,
                ),
                RecordTime(world.clock.now(), TODAY),
                world.clock.now(),
            )
            TxResult.Commit(Unit)
        }
    }

    @Test
    fun `the opening amount comes from the account balance`() = runTest {
        fund(20_000)

        val result = open()(world.cashId)

        val id = (result as OpenCashSessionResult.Opened).sessionId
        val session = tables().cashSessions.getValue(id)
        assertEquals(Money(20_000), session.opening.openingAmount)
        assertEquals(world.cashId, session.opening.accountId)
        assertEquals(TODAY, session.opening.businessDate)
        assertNull(session.closing)
        // Sin diferencia, ningún movimiento nuevo.
        assertEquals(1, tables().moneyMovements.size)
    }

    @Test
    fun `a different count brings the account to what was counted`() = runTest {
        fund(20_000)

        val id = (
            open()(
                world.cashId,
                counted = Money(19_500),
            ) as OpenCashSessionResult.Opened
            ).sessionId

        val difference = tables().moneyMovements.values.maxBy { it.id }
        assertEquals(MoneyMovementType.CASH_DIFFERENCE, difference.type)
        assertEquals(Money(-500), difference.amount)
        assertEquals(id, difference.draft.cashSessionId)
        assertEquals(Money(19_500), tables().moneyAccounts.getValue(world.cashId).balance)
        assertEquals(Money(19_500), tables().cashSessions.getValue(id).opening.openingAmount)
        // La diferencia de la apertura no cuenta en lo esperado.
        assertEquals(Money.ZERO, sessions.movementsTotal(id))
    }

    @Test
    fun `a second session on the same account is refused`() = runTest {
        open()(world.cashId)
        val before = tables()

        assertEquals(OpenCashSessionResult.AlreadyOpen, open()(world.cashId))
        assertEquals(before, tables())
    }

    @Test
    fun `only a current cash account can be opened`() = runTest {
        val bank = createAccount(MoneyAccountKind.BANK)
        val archived = createAccount(MoneyAccountKind.CASH)
        world.archive()(archived)
        val before = tables()

        assertEquals(OpenCashSessionResult.NotCashAccount, open()(bank))
        assertEquals(OpenCashSessionResult.AccountArchived, open()(archived))
        assertEquals(OpenCashSessionResult.AccountNotFound, open()(99))
        assertEquals(before, tables())
    }

    @Test
    fun `a negative or huge count is refused`() = runTest {
        assertEquals(OpenCashSessionResult.CountedOutOfRange, open()(world.cashId, Money(-1)))
        assertEquals(
            OpenCashSessionResult.CountedOutOfRange,
            open()(world.cashId, Money(1_000_000_000_000_001)),
        )
        assertTrue(tables().cashSessions.isEmpty())
    }

    @Test
    fun `a count of zero on an empty account opens without a difference`() = runTest {
        assertTrue(open()(world.cashId, Money.ZERO) is OpenCashSessionResult.Opened)
        assertTrue(tables().moneyMovements.isEmpty())
    }

    @Test
    fun `a difference needs the business currency`() = runTest {
        val world = MoneyWorld(currencyConfirmed = false)
        val sessions = FakeCashSessionRepository(world.database)
        val open = OpenCashSessionUseCase(
            world.transactions,
            world.settings,
            world.accounts,
            world.ledger,
            sessions,
            world.clock,
        )

        assertEquals(OpenCashSessionResult.CurrencyNotConfirmed, open(world.cashId, Money(100)))
        assertTrue(world.database.tables.value.cashSessions.isEmpty())
        assertTrue(open(world.cashId) is OpenCashSessionResult.Opened)
    }

    @Test
    fun `an unknown time zone cannot date the opening`() = runTest {
        val world = MoneyWorld(zoneId = "Invented/Zone")
        val result = OpenCashSessionUseCase(
            world.transactions,
            world.settings,
            world.accounts,
            world.ledger,
            FakeCashSessionRepository(world.database),
            world.clock,
        )(world.cashId)

        assertEquals(OpenCashSessionResult.UnknownTimeZone("Invented/Zone"), result)
    }

    @Test
    fun `a difference that does not fit is refused`() = runTest {
        world.database.seed { tables ->
            val cash = tables.moneyAccounts.getValue(world.cashId)
            val drained = cash.copy(balance = Money(Long.MIN_VALUE))
            tables.copy(moneyAccounts = tables.moneyAccounts + (world.cashId to drained))
        }
        val before = tables()

        assertEquals(OpenCashSessionResult.BalanceTooLarge, open()(world.cashId, Money(1)))
        assertEquals(before, tables())
    }

    @Test
    fun `a failure at any write leaves no session and no difference`() = runTest {
        fund(20_000)
        val before = tables()
        // La sesión con su id, y la diferencia con el saldo de su cuenta.
        for (failAt in 1..4) {
            world.database.failAtWrite = failAt
            val failed = runCatching { open()(world.cashId, Money(19_500)) }
            assertTrue("Escritura $failAt", failed.exceptionOrNull() is InjectedFailure)
            assertEquals(before, tables())
        }
        world.database.failAtWrite = null
        assertTrue(open()(world.cashId, Money(19_500)) is OpenCashSessionResult.Opened)
    }

    @Test
    fun `a storage failure comes back as a result`() = runTest {
        world.database.failAtWrite = 1
        world.database.failure = { StorageFailureException(StorageFailure.DISK_FULL) }

        assertEquals(
            OpenCashSessionResult.StorageFailed(StorageFailure.DISK_FULL),
            open()(world.cashId),
        )
    }

    @Test
    fun `while open every movement of the account falls in the session`() = runTest {
        val id = (open()(world.cashId) as OpenCashSessionResult.Opened).sessionId
        val bank = createAccount(MoneyAccountKind.BANK)

        val inCash = append(world.cashId, sessionId = null)
        val inBank = append(bank, sessionId = null)
        val explicit = append(world.cashId, sessionId = 77)

        assertEquals(id, tables().moneyMovements.getValue(inCash).draft.cashSessionId)
        assertNull(tables().moneyMovements.getValue(inBank).draft.cashSessionId)
        // Una sesión escrita en el borrador manda sobre la abierta.
        assertEquals(77L, tables().moneyMovements.getValue(explicit).draft.cashSessionId)
        // Solo el de la caja cae en la sesión: el del banco y el de otra sesión, no.
        assertEquals(Money(-100), sessions.movementsTotal(id))
    }

    @Test
    fun `without an open session no movement carries one`() = runTest {
        val movement = append(world.cashId, sessionId = null)

        assertNull(tables().moneyMovements.getValue(movement).draft.cashSessionId)
    }

    private suspend fun append(accountId: Long, sessionId: Long?): Long = world.transactions.write {
        val result = world.writer.append(
            MoneyMovementDraft(
                accountId,
                MoneyMovementType.OWNER_DRAW,
                Money(-100),
                MoneyOrigin.None,
                cashSessionId = sessionId,
            ),
            RecordTime(world.clock.now(), TODAY),
            world.clock.now(),
        )
        TxResult.Commit((result as MoneyAppendResult.Appended).movementId)
    }

    private suspend fun createAccount(kind: MoneyAccountKind): Long = world.transactions.write {
        val id = world.accounts.insert(
            NewMoneyAccount(
                MoneyAccountDetails("Account $kind", "account ${kind.name.lowercase()}", kind),
                MoneyWorld.BOOK,
                world.clock.now(),
            ),
        )
        TxResult.Commit(id)
    }

    private companion object {
        /** El 15 de marzo de 2026 en la zona de prueba. */
        val TODAY = requireNotNull(BusinessDate.parse("2026-03-15"))
    }
}
