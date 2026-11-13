package io.github.yesidrobayo.shopledger.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.backup.InvariantQueries
import io.github.yesidrobayo.shopledger.data.transaction.RestoreLock
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.cash.CashSessionStatus
import io.github.yesidrobayo.shopledger.domain.model.money.AccountCurrency
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccountDetails
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccountKind
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyMovementDraft
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyMovementType
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyOrigin
import io.github.yesidrobayo.shopledger.domain.model.money.NewMoneyAccount
import io.github.yesidrobayo.shopledger.domain.model.region.RecordTime
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.usecase.cash.CloseCashSessionResult
import io.github.yesidrobayo.shopledger.domain.usecase.cash.CloseCashSessionUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.cash.OpenCashSessionResult
import io.github.yesidrobayo.shopledger.domain.usecase.cash.OpenCashSessionUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.money.MoneyLedgerWriter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cerrar caja sobre la base real: lo esperado suma lo que movió la cuenta en la sesión sin las
 * diferencias de caja, la diferencia lleva la cuenta a lo contado, el cierre pasa la guarda de la
 * tabla, y una sesión cerrada no se vuelve a cerrar. Los nombres y los montos son inventados.
 */
@RunWith(AndroidJUnit4::class)
internal class CashCloseOnDatabaseTest : RepositoryTest() {
    private val settings by lazy { RoomAppSettingsRepository(database, RestoreLock { false }) }
    private val accounts by lazy { RoomMoneyAccountRepository(database) }
    private val money by lazy { RoomMoneyLedgerRepository(database) }
    private val sessions by lazy { RoomCashSessionRepository(database) }

    private val open by lazy {
        OpenCashSessionUseCase(runner, settings, accounts, money, sessions, Clock { AT })
    }

    private val close by lazy {
        CloseCashSessionUseCase(runner, settings, accounts, money, sessions, Clock { LATER_AT })
    }

    @Test
    fun closingComparesExpectedWithCountedAsOneChange() = runBlocking {
        val account = commit {
            accounts.insert(
                NewMoneyAccount(
                    MoneyAccountDetails("Caja inventada", "caja inventada", MoneyAccountKind.CASH),
                    AccountCurrency.of(settings.get().regional.currency),
                    AT,
                ),
            )
        }
        // Al abrir se cuentan 20000: la diferencia lleva la cuenta ahí y no cuenta en lo esperado.
        val id = (open(account, Money(20_000)) as OpenCashSessionResult.Opened).sessionId
        commit {
            MoneyLedgerWriter(accounts, money).append(
                MoneyMovementDraft(
                    account,
                    MoneyMovementType.OWNER_DRAW,
                    Money(-10_000),
                    MoneyOrigin.None,
                ),
                RecordTime(AT, DATE),
                AT,
            )
        }
        val before = changeSeq()

        val closing = (close(id, Money(9_500)) as CloseCashSessionResult.Closed).closing

        assertEquals(before + 1, changeSeq())
        assertEquals(Money(10_000), closing.expected)
        assertEquals(Money(-500), closing.difference)
        assertEquals(Money(9_500), requireNotNull(accounts.findById(account)).balance)
        val stored = requireNotNull(sessions.findById(id))
        assertEquals(CashSessionStatus.CLOSED, stored.status)
        assertEquals(closing, stored.closing)
        assertEquals(null, accounts.openCashSessionId(account))
        assertEquals(CloseCashSessionResult.AlreadyClosed, close(id, Money(9_500)))
        for (query in InvariantQueries.MODEL) {
            val rows = query.sql.trimEnd().removeSuffix(";")
            assertEquals(query.label, 0L, count("SELECT COUNT(*) FROM ($rows)"))
        }
    }
}
