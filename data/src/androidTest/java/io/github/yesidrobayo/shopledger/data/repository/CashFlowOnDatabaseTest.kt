package io.github.yesidrobayo.shopledger.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.transaction.RestoreLock
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.money.AccountCurrency
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccountDraft
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccountKind
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyMovementDraft
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyMovementType
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyOrigin
import io.github.yesidrobayo.shopledger.domain.model.region.RecordTime
import io.github.yesidrobayo.shopledger.domain.model.report.CashFlowSums
import io.github.yesidrobayo.shopledger.domain.model.report.CashFlowWindow
import io.github.yesidrobayo.shopledger.domain.model.settings.CurrencyCode
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.usecase.money.MoneyAppendResult
import io.github.yesidrobayo.shopledger.domain.usecase.money.MoneyLedgerWriter
import io.github.yesidrobayo.shopledger.domain.usecase.money.SaveMoneyAccountResult
import io.github.yesidrobayo.shopledger.domain.usecase.money.SaveMoneyAccountUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.money.VoidMoneyMovementResult
import io.github.yesidrobayo.shopledger.domain.usecase.money.VoidMoneyMovementUseCase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * La consulta del flujo de caja proyectado sobre la base real, con el saldo y lo que se acredita
 * del ejemplo de la regla: 100000 en las cuentas del negocio, la de efectivo que nace con la base
 * y una creada, de los que 15000 se acreditan en 2 días. Un movimiento anulado no cuenta, ni una
 * cuenta en otra moneda. Lo que vence por cobrar y por pagar, sin cargos a crédito, suma cero.
 * Nombres y montos inventados.
 */
@RunWith(AndroidJUnit4::class)
internal class CashFlowOnDatabaseTest : RepositoryTest() {
    private val accounts by lazy { RoomMoneyAccountRepository(database) }
    private val ledger by lazy { RoomMoneyLedgerRepository(database) }
    private val settings by lazy { RoomAppSettingsRepository(database, RestoreLock { false }) }
    private val writer by lazy { MoneyLedgerWriter(accounts, ledger) }
    private val clock = Clock { LATER_AT }
    private val save by lazy { SaveMoneyAccountUseCase(runner, settings, accounts, writer, clock) }
    private val void by lazy { VoidMoneyMovementUseCase(runner, settings, ledger, writer, clock) }
    private val source by lazy { RoomCashFlowSource(database) }
    private val time = RecordTime(AT, DATE)

    private suspend fun created(
        name: String,
        opening: Long,
        currency: AccountCurrency? = null,
    ): Long = (
        save(
            null,
            MoneyAccountDraft(name, MoneyAccountKind.BANK, currency, Money(opening)),
        ) as SaveMoneyAccountResult.Saved
        ).accountId

    /** Entra [amount] en [account], disponible en [afterDays] días desde la fecha de prueba. */
    private suspend fun later(account: Long, amount: Long, afterDays: Int) {
        val draft = MoneyMovementDraft(
            accountId = account,
            type = MoneyMovementType.OWNER_CONTRIBUTION,
            amount = Money(amount),
            origin = MoneyOrigin.None,
            availableDate = DATE.plusDays(afterDays),
        )
        runner.write {
            when (val result = writer.append(draft, time, AT)) {
                is MoneyAppendResult.Appended -> TxResult.Commit(result)
                else -> TxResult.Rollback(result)
            }
        }
    }

    @Test
    fun theBalanceAndWhatSettlesLaterComeFromTheBookAccounts() = runBlocking {
        val bank = created("Banco inventado", 85_000)
        later(bank, 15_000, afterDays = 2)
        later(bank, 5_000, afterDays = 3)
        val voided = ledger.findByAccount(bank).last()
        assertTrue(void(voided.id, null) is VoidMoneyMovementResult.Voided)
        val foreign = created(
            "Billetera inventada",
            40_000,
            AccountCurrency(requireNotNull(CurrencyCode.of("XBA")), 0),
        )
        later(foreign, 7_000, afterDays = 2)

        val sums = source.observe(DATE, DATE.plusDays(7), DATE.plusDays(30)).first()

        assertEquals(
            CashFlowSums(
                accountCount = 2,
                balance = Money(100_000),
                notYetAvailable = Money(15_000),
                short = CashFlowWindow(Money(15_000), Money.ZERO, Money.ZERO),
                long = CashFlowWindow(Money(15_000), Money.ZERO, Money.ZERO),
            ),
            sums,
        )
    }

    @Test
    fun aNewBaseOnlyHasTheEmptyCashAccount() = runBlocking {
        val sums = source.observe(DATE, DATE.plusDays(7), DATE.plusDays(30)).first()

        // La cuenta de efectivo nace con la base, sin saldo: el caso de uso pide además cuentas
        // en uso para mostrar el flujo.
        assertEquals(1, sums.accountCount)
        assertEquals(Money.ZERO, sums.balance)
    }

    @Test
    fun aSettlementAfterTheShortHorizonOnlyCountsInTheLongOne() = runBlocking {
        val bank = created("Banco inventado", 10_000)
        later(bank, 4_000, afterDays = 12)

        val sums = source.observe(DATE, DATE.plusDays(7), DATE.plusDays(30)).first()

        assertEquals(Money(4_000), sums.notYetAvailable)
        assertEquals(Money.ZERO, sums.short.settlements)
        assertEquals(Money(4_000), sums.long.settlements)
    }
}
