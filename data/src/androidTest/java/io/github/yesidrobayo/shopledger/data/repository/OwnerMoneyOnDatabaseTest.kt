package io.github.yesidrobayo.shopledger.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.transaction.RestoreLock
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Checked
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.expense.ExpenseRequest
import io.github.yesidrobayo.shopledger.domain.model.money.ManualMoneyKind
import io.github.yesidrobayo.shopledger.domain.model.money.ManualMoneyRequest
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccountDraft
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccountKind
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.rule.MarginCalculator
import io.github.yesidrobayo.shopledger.domain.usecase.expense.RegisterExpenseResult
import io.github.yesidrobayo.shopledger.domain.usecase.expense.RegisterExpenseUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.money.MoneyLedgerWriter
import io.github.yesidrobayo.shopledger.domain.usecase.money.RecordManualMoneyResult
import io.github.yesidrobayo.shopledger.domain.usecase.money.RecordManualMoneyUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.money.SaveMoneyAccountResult
import io.github.yesidrobayo.shopledger.domain.usecase.money.SaveMoneyAccountUseCase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Retiros y aportes sobre la base real: bajan o suben la cuenta, y la ganancia del período, que se
 * suma en SQL desde ventas, gastos y stock, no cambia. Montos inventados, con valores a mano.
 */
@RunWith(AndroidJUnit4::class)
internal class OwnerMoneyOnDatabaseTest : RepositoryTest() {
    private val accounts by lazy { RoomMoneyAccountRepository(database) }
    private val ledger by lazy { RoomMoneyLedgerRepository(database) }
    private val settings by lazy { RoomAppSettingsRepository(database, RestoreLock { false }) }
    private val writer by lazy { MoneyLedgerWriter(accounts, ledger) }
    private val clock = Clock { LATER_AT }
    private val reports by lazy { RoomPeriodReportRepository(database) }

    private suspend fun netProfit(date: BusinessDate): Money {
        val totals = reports.observeTotals(ReportPeriod.Day(date)).first()
        return (MarginCalculator.profit(totals.profit) as Checked.Value).value.netProfit
    }

    @Test
    fun aDrawAndAContributionMoveTheAccountAndNeverTheProfit() = runBlocking {
        val bank = (
            SaveMoneyAccountUseCase(runner, settings, accounts, writer, clock)(
                null,
                MoneyAccountDraft("Banco inventado", MoneyAccountKind.BANK, null, Money(805_000)),
            ) as SaveMoneyAccountResult.Saved
            ).accountId
        val category = count("SELECT id FROM expense_categories ORDER BY id LIMIT 1")
        val expense = RegisterExpenseUseCase(
            runner,
            settings,
            RoomExpenseRepository(database),
            RoomExpenseCategoryRepository(database),
            clock,
        )(ExpenseRequest(Money(195_000), category)) as RegisterExpenseResult.Registered
        val date = requireNotNull(
            RoomExpenseRepository(database).findById(expense.expenseId),
        ).content.businessDate
        val before = netProfit(date)
        assertEquals(Money(-195_000), before)

        val record = RecordManualMoneyUseCase(runner, settings, accounts, writer, clock)
        val draw = record(ManualMoneyRequest(bank, ManualMoneyKind.OWNER_DRAW, Money(300_000)))
        val contribution =
            record(ManualMoneyRequest(bank, ManualMoneyKind.OWNER_CONTRIBUTION, Money(50_000)))

        assertEquals(Money(505_000), (draw as RecordManualMoneyResult.Recorded).balanceAfter)
        assertEquals(
            Money(555_000),
            (contribution as RecordManualMoneyResult.Recorded).balanceAfter,
        )
        assertEquals(before, netProfit(date))
        assertEquals(Money(555_000), accounts.findById(bank)?.balance)
    }
}
