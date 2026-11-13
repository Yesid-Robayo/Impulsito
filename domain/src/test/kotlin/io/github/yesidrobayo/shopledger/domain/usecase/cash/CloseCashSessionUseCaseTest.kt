package io.github.yesidrobayo.shopledger.domain.usecase.cash

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.cash.CashSession
import io.github.yesidrobayo.shopledger.domain.model.cash.CashSessionStatus
import io.github.yesidrobayo.shopledger.domain.model.cash.NewCashSession
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
import io.github.yesidrobayo.shopledger.domain.rule.cash.CashCount
import io.github.yesidrobayo.shopledger.domain.testing.FakeCashSessionRepository
import io.github.yesidrobayo.shopledger.domain.testing.FixedClock
import io.github.yesidrobayo.shopledger.domain.testing.InjectedFailure
import io.github.yesidrobayo.shopledger.domain.usecase.money.MoneyAppendResult
import io.github.yesidrobayo.shopledger.domain.usecase.money.MoneyWorld
import java.util.UUID
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cerrar caja: lo esperado frente a lo contado, con el ejemplo de la regla escrito a mano. Apertura
 * 20000, ventas en efectivo +35000, devolución −2000, gasto −3000 y retiro −10000 esperan 40000; con
 * 39500 contados faltan 500, que una `CASH_DIFFERENCE` lleva a la cuenta. Una transferencia también
 * cuenta, y nada de otra cuenta ni de fuera de la sesión. Montos en unidad menor de una moneda
 * reservada para pruebas, con dos decimales.
 */
class CloseCashSessionUseCaseTest {
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

    private fun close(clock: FixedClock = world.clock) = CloseCashSessionUseCase(
        world.transactions,
        world.settings,
        world.accounts,
        world.ledger,
        sessions,
        clock,
    )

    private fun tables() = world.database.tables.value

    /** El ejemplo de la regla: abre con 20000 y mueve la caja como dice, y devuelve la sesión. */
    private suspend fun sessionOfTheRule(): Long {
        move(world.cashId, MoneyMovementType.OPENING_BALANCE, 20_000, MoneyOrigin.None)
        val id = (open()(world.cashId) as OpenCashSessionResult.Opened).sessionId
        move(world.cashId, MoneyMovementType.SALE_RECEIPT, 35_000, MoneyOrigin.SalePayment(1))
        move(world.cashId, MoneyMovementType.REFUND, -2_000, MoneyOrigin.SaleReturn(1))
        move(world.cashId, MoneyMovementType.EXPENSE_PAYMENT, -3_000, MoneyOrigin.Expense(1))
        move(world.cashId, MoneyMovementType.OWNER_DRAW, -10_000, MoneyOrigin.None)
        return id
    }

    @Test
    fun `the example of the rule expects 40000 and 39500 counted are 500 short`() = runTest {
        val id = sessionOfTheRule()

        val result = close()(id, Money(39_500), note = "  Conteo de la noche  ")

        val closing = (result as CloseCashSessionResult.Closed).closing
        assertEquals(Money(40_000), closing.expected)
        assertEquals(Money(39_500), closing.counted)
        assertEquals(Money(-500), closing.difference)
        assertEquals("Conteo de la noche", closing.note)
        val difference = tables().moneyMovements.values.maxBy { it.id }
        assertEquals(MoneyMovementType.CASH_DIFFERENCE, difference.type)
        assertEquals(Money(-500), difference.amount)
        assertEquals(id, difference.draft.cashSessionId)
        assertEquals(Money(39_500), tables().moneyAccounts.getValue(world.cashId).balance)
        val session = tables().cashSessions.getValue(id)
        assertEquals(CashSessionStatus.CLOSED, session.status)
        assertEquals(closing, session.closing)
    }

    @Test
    fun `counting exactly what was expected writes no difference`() = runTest {
        val id = sessionOfTheRule()
        val movements = tables().moneyMovements.size

        val closing = (close()(id, Money(40_000)) as CloseCashSessionResult.Closed).closing

        assertEquals(Money.ZERO, closing.difference)
        assertEquals(movements, tables().moneyMovements.size)
    }

    @Test
    fun `a surplus is a positive difference`() = runTest {
        val id = sessionOfTheRule()

        val closing = (close()(id, Money(41_000)) as CloseCashSessionResult.Closed).closing

        assertEquals(Money(1_000), closing.difference)
        assertEquals(Money(41_000), tables().moneyAccounts.getValue(world.cashId).balance)
    }

    @Test
    fun `a transfer and a void count, other accounts and earlier movements do not`() = runTest {
        val bank = createAccount(MoneyAccountKind.BANK)
        move(world.cashId, MoneyMovementType.OPENING_BALANCE, 5_000, MoneyOrigin.None)
        val id = (open()(world.cashId) as OpenCashSessionResult.Opened).sessionId
        val group = UUID(0L, 7L).toString()
        move(world.cashId, MoneyMovementType.TRANSFER_IN, 30_000, MoneyOrigin.Transfer(group, null))
        move(bank, MoneyMovementType.TRANSFER_OUT, -30_000, MoneyOrigin.Transfer(group, null))
        val draw = move(world.cashId, MoneyMovementType.OWNER_DRAW, -4_000, MoneyOrigin.None)
        world.transactions.write {
            world.writer.void(checkNotNull(world.ledger.findById(draw)), time(), world.clock.now())
            TxResult.Commit(Unit)
        }

        val closing = (close()(id, Money(35_000)) as CloseCashSessionResult.Closed).closing

        // 5000 + 30000 − 4000 + 4000.
        assertEquals(Money(35_000), closing.expected)
        assertEquals(Money.ZERO, closing.difference)
    }

    @Test
    fun `a session left open since yesterday closes with today's date`() = runTest {
        val id = sessionOfTheRule()
        val tomorrow = FixedClock(Instant.parse("2026-03-16T15:00:00Z"))

        close(tomorrow)(id, Money(39_500))

        val difference = tables().moneyMovements.values.maxBy { it.id }
        assertEquals(BusinessDate.parse("2026-03-16"), difference.time.businessDate)
        assertEquals(
            BusinessDate.parse("2026-03-15"),
            tables().cashSessions.getValue(id).opening.businessDate,
        )
    }

    @Test
    fun `a closed session is never edited`() = runTest {
        val id = sessionOfTheRule()
        close()(id, Money(39_500))
        val before = tables()

        assertEquals(CloseCashSessionResult.AlreadyClosed, close()(id, Money(40_000)))
        assertEquals(before, tables())
    }

    @Test
    fun `after closing the account has no open session and can open again`() = runTest {
        val id = sessionOfTheRule()
        close()(id, Money(39_500))

        val movement = move(world.cashId, MoneyMovementType.OWNER_DRAW, -100, MoneyOrigin.None)

        assertNull(tables().moneyMovements.getValue(movement).draft.cashSessionId)
        val next = (open()(world.cashId) as OpenCashSessionResult.Opened).sessionId
        assertEquals(Money(39_400), tables().cashSessions.getValue(next).opening.openingAmount)
    }

    @Test
    fun `wrong closings are refused and write nothing`() = runTest {
        val id = sessionOfTheRule()
        val before = tables()

        assertEquals(CloseCashSessionResult.CountedOutOfRange, close()(id, Money(-1)))
        assertEquals(
            CloseCashSessionResult.CountedOutOfRange,
            close()(id, Money(1_000_000_000_000_001)),
        )
        assertEquals(CloseCashSessionResult.NoteTooLong, close()(id, Money(1), "x".repeat(201)))
        assertEquals(CloseCashSessionResult.SessionNotFound, close()(99, Money(1)))
        assertEquals(before, tables())
        assertTrue(close()(id, Money(1), "x".repeat(200)) is CloseCashSessionResult.Closed)
    }

    @Test
    fun `a difference needs the business currency`() = runTest {
        val world = MoneyWorld(currencyConfirmed = false)
        val sessions = FakeCashSessionRepository(world.database)
        val id = (
            OpenCashSessionUseCase(
                world.transactions,
                world.settings,
                world.accounts,
                world.ledger,
                sessions,
                world.clock,
            )(world.cashId) as OpenCashSessionResult.Opened
            ).sessionId
        val close = CloseCashSessionUseCase(
            world.transactions,
            world.settings,
            world.accounts,
            world.ledger,
            sessions,
            world.clock,
        )

        assertEquals(CloseCashSessionResult.CurrencyNotConfirmed, close(id, Money(100)))
        assertTrue(close(id, Money.ZERO) is CloseCashSessionResult.Closed)
    }

    @Test
    fun `an unknown time zone cannot date the closing`() = runTest {
        val world = MoneyWorld(zoneId = "Invented/Zone")
        world.database.seed { tables ->
            tables.copy(
                cashSessions = tables.cashSessions + (
                    1L to CashSession(
                        1,
                        NewCashSession(
                            world.cashId,
                            Money.ZERO,
                            MoneyWorld.NOW,
                            checkNotNull(BusinessDate.parse("2026-03-15")),
                        ),
                        null,
                    )
                    ),
            )
        }
        val result = CloseCashSessionUseCase(
            world.transactions,
            world.settings,
            world.accounts,
            world.ledger,
            FakeCashSessionRepository(world.database),
            world.clock,
        )(1, Money.ZERO)

        assertEquals(CloseCashSessionResult.UnknownTimeZone("Invented/Zone"), result)
    }

    @Test
    fun `figures that do not fit are refused`() = runTest {
        val id = (open()(world.cashId) as OpenCashSessionResult.Opened).sessionId
        world.database.seed { tables ->
            val session = tables.cashSessions.getValue(id)
            val damaged = session.copy(
                opening = session.opening.copy(openingAmount = Money(Long.MIN_VALUE)),
            )
            tables.copy(cashSessions = tables.cashSessions + (id to damaged))
        }
        val before = tables()

        assertEquals(CloseCashSessionResult.AmountTooLarge, close()(id, Money(1)))
        assertEquals(before, tables())
    }

    @Test
    fun `a failure at any write leaves the session open`() = runTest {
        val id = sessionOfTheRule()
        val before = tables()
        // La diferencia con el saldo de su cuenta, y el cierre.
        for (failAt in 1..3) {
            world.database.failAtWrite = failAt
            val failed = runCatching { close()(id, Money(39_500)) }
            assertTrue("Escritura $failAt", failed.exceptionOrNull() is InjectedFailure)
            assertEquals(before, tables())
        }
        world.database.failAtWrite = null
        assertTrue(close()(id, Money(39_500)) is CloseCashSessionResult.Closed)
    }

    @Test
    fun `a storage failure comes back as a result`() = runTest {
        val id = sessionOfTheRule()
        world.database.failAtWrite = 1
        world.database.failure = { StorageFailureException(StorageFailure.DISK_FULL) }

        assertEquals(
            CloseCashSessionResult.StorageFailed(StorageFailure.DISK_FULL),
            close()(id, Money(1)),
        )
    }

    @Test
    fun `the rule adds and subtracts exactly`() {
        assertEquals(Money(40_000), CashCount.expected(Money(20_000), Money(20_000)))
        assertNull(CashCount.expected(Money(Long.MAX_VALUE), Money(1)))
        assertEquals(Money(-500), CashCount.difference(Money(39_500), Money(40_000)))
        assertNull(CashCount.difference(Money(Long.MIN_VALUE), Money(1)))
    }

    private fun time() =
        RecordTime(world.clock.now(), checkNotNull(BusinessDate.parse("2026-03-15")))

    private suspend fun move(
        accountId: Long,
        type: MoneyMovementType,
        amount: Long,
        origin: MoneyOrigin,
    ): Long = world.transactions.write {
        val result = world.writer.append(
            MoneyMovementDraft(accountId, type, Money(amount), origin),
            time(),
            world.clock.now(),
        )
        TxResult.Commit((result as MoneyAppendResult.Appended).movementId)
    }

    private suspend fun createAccount(kind: MoneyAccountKind): Long = world.transactions.write {
        TxResult.Commit(
            world.accounts.insert(
                NewMoneyAccount(
                    MoneyAccountDetails("Account $kind", "account ${kind.name.lowercase()}", kind),
                    MoneyWorld.BOOK,
                    world.clock.now(),
                ),
            ),
        )
    }
}
