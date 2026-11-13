package io.github.yesidrobayo.shopledger.domain.usecase.cash

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.cash.CashCountPhase
import io.github.yesidrobayo.shopledger.domain.model.cash.CashCounted
import io.github.yesidrobayo.shopledger.domain.model.cash.DenominationCount
import io.github.yesidrobayo.shopledger.domain.rule.cash.CashCount
import io.github.yesidrobayo.shopledger.domain.testing.FakeCashDenominationRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakeCashSessionRepository
import io.github.yesidrobayo.shopledger.domain.usecase.money.MoneyWorld
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contar por denominación, con billetes y monedas que escribe la persona: la base nace sin
 * ninguno, y el total es la suma de valor por piezas, igual a lo contado que guarda la sesión. El
 * ejemplo de la regla: 2 de 10000, 3 de 5000, 4 de 1000 y 1 de 500 son 39500. Montos en unidad
 * menor; con dos decimales, 10000 son cien unidades, y con cero, diez mil.
 */
class CashDenominationCountTest {
    private val world = MoneyWorld()
    private val sessions = FakeCashSessionRepository(world.database)
    private val denominations = FakeCashDenominationRepository(world.database)

    private val save = SaveCashDenominationUseCase(world.transactions, denominations, world.clock)

    private fun open() = OpenCashSessionUseCase(
        world.transactions,
        world.settings,
        world.accounts,
        world.ledger,
        sessions,
        world.clock,
        denominations,
    )

    private fun close() = CloseCashSessionUseCase(
        world.transactions,
        world.settings,
        world.accounts,
        world.ledger,
        sessions,
        world.clock,
        denominations,
    )

    private suspend fun denomination(value: Long): Long =
        (save(null, Money(value)) as SaveCashDenominationResult.Saved).id

    /** Las cuatro denominaciones del ejemplo, y sus piezas. */
    private suspend fun exampleCount(): List<DenominationCount> = listOf(
        DenominationCount(denomination(10_000), 2),
        DenominationCount(denomination(5_000), 3),
        DenominationCount(denomination(1_000), 4),
        DenominationCount(denomination(500), 1),
    )

    @Test
    fun `a new business has no denominations`() = runTest {
        assertTrue(denominations.observeAll().first().isEmpty())
    }

    @Test
    fun `the example of the rule counts 39500 when closing`() = runTest {
        val id = (open()(world.cashId) as OpenCashSessionResult.Opened).sessionId
        val counts = exampleCount()

        val result = close()(id, CashCounted.ByDenomination(counts))

        val closing = (result as CloseCashSessionResult.Closed).closing
        assertEquals(Money(39_500), closing.counted)
        assertEquals(Money(39_500), closing.difference)
        assertEquals(counts, sessions.findCounts(id, CashCountPhase.CLOSING))
        assertTrue(sessions.findCounts(id, CashCountPhase.OPENING).isEmpty())
    }

    @Test
    fun `counting at opening keeps the pieces and the opening amount`() = runTest {
        val counts = exampleCount()

        val id = (
            open()(world.cashId, CashCounted.ByDenomination(counts)) as OpenCashSessionResult.Opened
            ).sessionId

        assertEquals(
            Money(39_500),
            world.database.tables.value.cashSessions.getValue(id).opening.openingAmount,
        )
        assertEquals(counts, sessions.findCounts(id, CashCountPhase.OPENING))
        assertEquals(
            Money(39_500),
            world.database.tables.value.moneyAccounts.getValue(world.cashId).balance,
        )
    }

    @Test
    fun `pieces in zero are not kept`() = runTest {
        val id = (open()(world.cashId) as OpenCashSessionResult.Opened).sessionId
        val hundred = denomination(10_000)
        val coin = denomination(50)

        close()(
            id,
            CashCounted.ByDenomination(
                listOf(DenominationCount(hundred, 1), DenominationCount(coin, 0)),
            ),
        )

        assertEquals(
            listOf(DenominationCount(hundred, 1)),
            sessions.findCounts(id, CashCountPhase.CLOSING),
        )
    }

    @Test
    fun `the same values work with zero and two decimals`() {
        // Con dos decimales: 2 de 100,00, 3 de 50,00, 4 de 10,00 y 1 de 5,00 son 395,00.
        assertEquals(
            Money(39_500),
            CashCount.countedOf(
                listOf(
                    Money(10_000) to 2L,
                    Money(5_000) to 3L,
                    Money(1_000) to 4L,
                    Money(500) to 1L,
                ),
            ),
        )
        // Sin decimales: 2 de 100, 3 de 50, 4 de 10 y 1 de 5 son 395.
        assertEquals(
            Money(395),
            CashCount.countedOf(
                listOf(
                    Money(100) to 2L,
                    Money(50) to 3L,
                    Money(10) to 4L,
                    Money(5) to 1L,
                ),
            ),
        )
        assertEquals(Money.ZERO, CashCount.countedOf(emptyList()))
    }

    @Test
    fun `a product or a sum that does not fit gives no total`() {
        assertNull(CashCount.countedOf(listOf(Money(Long.MAX_VALUE) to 2L)))
        assertNull(CashCount.countedOf(listOf(Money(Long.MAX_VALUE) to 1L, Money(1) to 1L)))
    }

    @Test
    fun `unknown, inactive or repeated denominations are refused`() = runTest {
        val id = (open()(world.cashId) as OpenCashSessionResult.Opened).sessionId
        val bill = denomination(10_000)
        val old = denomination(200)
        save(old, Money(200), isActive = false)
        val before = world.database.tables.value

        for (counts in listOf(
            listOf(DenominationCount(99, 1)),
            listOf(DenominationCount(old, 1)),
            listOf(DenominationCount(bill, 1), DenominationCount(bill, 2)),
        )) {
            assertEquals(
                CloseCashSessionResult.UnknownDenomination,
                close()(id, CashCounted.ByDenomination(counts)),
            )
        }
        assertEquals(
            OpenCashSessionResult.UnknownDenomination,
            OpenCashSessionUseCase(
                world.transactions,
                world.settings,
                world.accounts,
                world.ledger,
                FakeCashSessionRepository(world.database),
                world.clock,
                denominations,
            )(world.cashId, CashCounted.ByDenomination(listOf(DenominationCount(99, 1)))),
        )
        assertEquals(before, world.database.tables.value)
    }

    @Test
    fun `pieces out of range or a total too large are refused`() = runTest {
        val id = (open()(world.cashId) as OpenCashSessionResult.Opened).sessionId
        val bill = denomination(1_000_000_000_000)

        assertEquals(
            CloseCashSessionResult.CountedOutOfRange,
            close()(id, CashCounted.ByDenomination(listOf(DenominationCount(bill, -1)))),
        )
        assertEquals(
            CloseCashSessionResult.CountedOutOfRange,
            close()(id, CashCounted.ByDenomination(listOf(DenominationCount(bill, 1_000_000_001)))),
        )
        // Un billón por mil millones de piezas pasa del máximo de un documento.
        assertEquals(
            CloseCashSessionResult.CountedOutOfRange,
            close()(id, CashCounted.ByDenomination(listOf(DenominationCount(bill, 1_000_000_000)))),
        )
    }

    @Test
    fun `a denomination keeps a value between one and the unit maximum`() = runTest {
        assertEquals(SaveCashDenominationResult.ValueOutOfRange, save(null, Money.ZERO))
        assertEquals(
            SaveCashDenominationResult.ValueOutOfRange,
            save(null, Money(1_000_000_000_001)),
        )
        assertEquals(SaveCashDenominationResult.NotFound, save(99, Money(100)))
        val id = denomination(1)
        assertEquals(
            SaveCashDenominationResult.Saved(id),
            save(id, Money(2), isActive = false, sortOrder = 3),
        )
        val stored = denominations.observeAll().first().single()
        assertEquals(Money(2), stored.value)
        assertEquals(false, stored.isActive)
        assertEquals(3, stored.sortOrder)
    }
}
