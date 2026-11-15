package io.github.yesidrobayo.shopledger.domain.usecase.report

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.ProfitTotals
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.report.GoalAdvance
import io.github.yesidrobayo.shopledger.domain.model.report.MonthGoals
import io.github.yesidrobayo.shopledger.domain.model.report.PeriodTotals
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException
import io.github.yesidrobayo.shopledger.domain.testing.FakeAppSettingsRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakeGoalRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakePeriodReportRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakeTransactionRunner
import io.github.yesidrobayo.shopledger.domain.testing.FixedClock
import io.github.yesidrobayo.shopledger.domain.testing.InMemoryDatabase
import io.github.yesidrobayo.shopledger.domain.testing.appSettings
import io.github.yesidrobayo.shopledger.domain.testing.regionalSettings
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Las metas del mes: su avance con la misma cuenta que las estadísticas, la tarjeta de Inicio y
 * guardarlas en una transacción. Montos inventados y valores escritos a mano.
 */
class GoalUseCasesTest {
    private val goals = FakeGoalRepository()
    private val reports = FakePeriodReportRepository()
    private val observe = ObserveMonthGoalsUseCase(goals, ObservePeriodReportUseCase(reports))
    private val november = YearMonth(2026, 11)
    private val database = InMemoryDatabase()
    private val runner = FakeTransactionRunner(database)
    private val save = SaveMonthGoalsUseCase(runner, goals, FixedClock.atEpochMillis(1_000L))

    private fun totals(net: Long, cost: Long = 0, expenses: Long = 0) = PeriodTotals(
        ProfitTotals(
            salesNetTotal = Money(net),
            returnsNetRefund = Money.ZERO,
            salesTotalCost = Money(cost),
            returnsCostReturned = Money.ZERO,
            recordedExpenses = Money(expenses),
            netShrinkage = Money.ZERO,
            paymentFees = Money.ZERO,
            cashRoundingTotal = Money.ZERO,
        ),
        salesCount = 1,
    )

    @Test
    fun `a month without goals shows nothing`() = runTest {
        val view = observe(november).first()

        assertEquals(MonthGoalView(november, null, null, null), view)
    }

    @Test
    fun `the sales goal of the rule shows 85 percent and the profit goal its own progress`() =
        runTest {
            goals.goals.value =
                mapOf(november to MonthGoals(november, Money(1_000_000), Money(300_000)))
            // Ventas 850000, costo 600000 y gastos 64000: ganancia final 186000.
            reports.totals.value = mapOf(
                ReportPeriod.Month(november) to
                    totals(net = 850_000, cost = 600_000, expenses = 64_000),
            )

            val view = observe(november).first()

            assertEquals(
                GoalAdvance(Money(850_000), Money(1_000_000), BasisPoints(8_500)),
                view.sales,
            )
            assertEquals(
                GoalAdvance(Money(186_000), Money(300_000), BasisPoints(6_200)),
                view.profit,
            )
        }

    @Test
    fun `a month with only a profit goal has no sales progress`() = runTest {
        goals.goals.value = mapOf(november to MonthGoals(november, null, Money(300_000)))

        val view = observe(november).first()

        assertNull(view.sales)
        assertEquals(BasisPoints(0), view.profit?.progress)
    }

    @Test
    fun `a sum that does not fit leaves the goal without progress`() = runTest {
        goals.goals.value = mapOf(november to MonthGoals(november, Money(1_000_000), null))
        reports.totals.value =
            mapOf(ReportPeriod.Month(november) to totals(net = Long.MIN_VALUE, cost = 1))

        val view = observe(november).first()

        assertEquals(GoalAdvance(null, Money(1_000_000), null), view.sales)
    }

    @Test
    fun `home follows the sales goal of the month in progress`() = runTest {
        goals.goals.value = mapOf(november to MonthGoals(november, Money(1_000_000), null))
        reports.totals.value = mapOf(ReportPeriod.Month(november) to totals(net = 850_000))

        val progress = home().invoke(flowOf(Unit)).first()

        assertEquals(BasisPoints(8_500), progress?.progress)
    }

    @Test
    fun `home has no card without a sales goal or without a known zone`() = runTest {
        goals.goals.value = mapOf(november to MonthGoals(november, null, Money(300_000)))

        assertNull(home().invoke(flowOf(Unit)).first())
        assertNull(home(zone = "Nowhere/Unknown").invoke(flowOf(Unit)).first())
    }

    @Test
    fun `saving new goals writes the month in one counted transaction`() = runTest {
        val result = save(november, Money(1_000_000), null)

        assertEquals(SaveGoalsResult.Saved, result)
        assertEquals(MonthGoals(november, Money(1_000_000), null), goals.goals.value[november])
        assertEquals(1, runner.writeCalls)
        assertEquals(1, runner.commits)
    }

    @Test
    fun `saving the same goals again writes nothing`() = runTest {
        goals.goals.value = mapOf(november to MonthGoals(november, Money(1_000_000), null))

        assertEquals(SaveGoalsResult.Saved, save(november, Money(1_000_000), null))
        assertEquals(emptyList<String>(), goals.writes)
        assertEquals(1, runner.rollbacks)
    }

    @Test
    fun `leaving both goals empty removes the month`() = runTest {
        goals.goals.value = mapOf(november to MonthGoals(november, Money(1_000_000), null))

        assertEquals(SaveGoalsResult.Removed, save(november, null, null))
        assertNull(goals.goals.value[november])
        assertEquals(1, runner.commits)
    }

    @Test
    fun `leaving both empty in a month without goals changes nothing`() = runTest {
        assertEquals(SaveGoalsResult.Removed, save(november, null, null))
        assertEquals(emptyList<String>(), goals.writes)
        assertEquals(0, runner.commits)
    }

    @Test
    fun `a goal of zero or above the ceiling is rejected without opening a transaction`() =
        runTest {
            assertEquals(
                SaveGoalsResult.Invalid(sales = true, profit = false),
                save(november, Money.ZERO, Money(1)),
            )
            assertEquals(
                SaveGoalsResult.Invalid(sales = false, profit = true),
                save(november, null, Money(9_000_000_000_000_001L)),
            )
            assertEquals(0, runner.writeCalls)
        }

    @Test
    fun `a storage failure is returned and nothing stays written`() = runTest {
        goals.failNextWrite = StorageFailureException(StorageFailure.DISK_FULL)

        assertEquals(
            SaveGoalsResult.Failed(StorageFailure.DISK_FULL),
            save(november, Money(1_000_000), Money(300_000)),
        )
        assertNull(goals.goals.value[november])
        assertEquals(1, runner.rollbacks)
    }

    private fun home(zone: String = "Etc/GMT+5") = ObserveCurrentMonthGoalUseCase(
        BusinessCalendarSource(
            FakeAppSettingsRepository(appSettings(regionalSettings(zone, DayOfWeek.MONDAY))),
        ),
        observe,
        Clock { Instant.parse("2026-11-04T15:00:00Z") },
    )
}
