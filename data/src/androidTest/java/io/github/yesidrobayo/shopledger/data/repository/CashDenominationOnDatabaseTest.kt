package io.github.yesidrobayo.shopledger.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.transaction.RestoreLock
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.cash.CashCountPhase
import io.github.yesidrobayo.shopledger.domain.model.cash.CashCounted
import io.github.yesidrobayo.shopledger.domain.model.cash.DenominationCount
import io.github.yesidrobayo.shopledger.domain.model.money.AccountCurrency
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccountDetails
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccountKind
import io.github.yesidrobayo.shopledger.domain.model.money.NewMoneyAccount
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.usecase.cash.CloseCashSessionResult
import io.github.yesidrobayo.shopledger.domain.usecase.cash.CloseCashSessionUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.cash.OpenCashSessionResult
import io.github.yesidrobayo.shopledger.domain.usecase.cash.OpenCashSessionUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.cash.SaveCashDenominationResult
import io.github.yesidrobayo.shopledger.domain.usecase.cash.SaveCashDenominationUseCase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Contar por denominación sobre la base real: la base nace sin billetes ni monedas, la persona los
 * escribe, y el conteo del ejemplo de la regla guarda sus piezas y da 39500 como lo contado de la
 * sesión. Los montos son inventados.
 */
@RunWith(AndroidJUnit4::class)
internal class CashDenominationOnDatabaseTest : RepositoryTest() {
    private val settings by lazy { RoomAppSettingsRepository(database, RestoreLock { false }) }
    private val accounts by lazy { RoomMoneyAccountRepository(database) }
    private val money by lazy { RoomMoneyLedgerRepository(database) }
    private val sessions by lazy { RoomCashSessionRepository(database) }
    private val denominations by lazy { RoomCashDenominationRepository(database) }

    private val save by lazy { SaveCashDenominationUseCase(runner, denominations, Clock { AT }) }

    @Test
    fun aNewDatabaseHasNoDenominations() = runBlocking {
        assertTrue(denominations.observeAll().first().isEmpty())
    }

    @Test
    fun theExampleCountIsKeptWithTheClosing() = runBlocking {
        val account = commit {
            accounts.insert(
                NewMoneyAccount(
                    MoneyAccountDetails("Caja inventada", "caja inventada", MoneyAccountKind.CASH),
                    AccountCurrency.of(settings.get().regional.currency),
                    AT,
                ),
            )
        }
        val id = (
            OpenCashSessionUseCase(
                runner,
                settings,
                accounts,
                money,
                sessions,
                Clock {
                    AT
                },
            )(account)
                as OpenCashSessionResult.Opened
            ).sessionId
        val counts = listOf(
            10_000L to 2L,
            5_000L to 3L,
            1_000L to 4L,
            500L to 1L,
        ).map { (value, count) ->
            DenominationCount(
                (save(null, Money(value)) as SaveCashDenominationResult.Saved).id,
                count,
            )
        }

        val result = CloseCashSessionUseCase(
            runner,
            settings,
            accounts,
            money,
            sessions,
            Clock { LATER_AT },
            denominations,
        )(id, CashCounted.ByDenomination(counts))

        assertEquals(Money(39_500), (result as CloseCashSessionResult.Closed).closing.counted)
        assertEquals(counts, sessions.findCounts(id, CashCountPhase.CLOSING))
        assertEquals(39_500L, count("SELECT counted_amount FROM cash_sessions WHERE id = $id"))
        assertEquals(4L, count("SELECT COUNT(*) FROM cash_session_counts WHERE phase = 'CLOSING'"))
    }
}
