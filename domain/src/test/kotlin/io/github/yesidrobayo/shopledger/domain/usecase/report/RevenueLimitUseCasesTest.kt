package io.github.yesidrobayo.shopledger.domain.usecase.report

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.ProfitTotals
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.report.LimitLevel
import io.github.yesidrobayo.shopledger.domain.model.report.LimitNoticeMark
import io.github.yesidrobayo.shopledger.domain.model.report.PeriodTotals
import io.github.yesidrobayo.shopledger.domain.model.settings.RevenueLimit
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.LimitNoticeStore
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException
import io.github.yesidrobayo.shopledger.domain.testing.FakeAppSettingsRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakeGoalRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakePeriodReportRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakePlanningReportRepository
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
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El límite de ingresos: lo acumulado en su ventana con la misma cuenta que las estadísticas, el
 * nivel de aviso, guardarlo en una transacción y el aviso que sale una vez por nivel. Montos
 * inventados y valores escritos a mano.
 */
class RevenueLimitUseCasesTest {
    private val settings =
        FakeAppSettingsRepository(appSettings(regionalSettings("Etc/GMT+5", DayOfWeek.MONDAY)))
    private val goals = FakeGoalRepository(settings)
    private val reports = FakePeriodReportRepository()
    private val planning = FakePlanningReportRepository()
    private val observe =
        ObserveRevenueLimitUseCase(settings, planning, ObservePeriodReportUseCase(reports))
    private val runner = FakeTransactionRunner(InMemoryDatabase())
    private val save =
        SaveRevenueLimitUseCase(runner, goals, settings, FixedClock.atEpochMillis(1_000L))
    private val november = YearMonth(2026, 11)
    private val window = ReportPeriod.Range(date(2025, 12, 1), date(2026, 11, 30))

    private fun date(y: Int, m: Int, d: Int) = BusinessDate(LocalDate(y, m, d))

    private fun limit(amount: Long, months: Int) {
        val current = settings.settings.value
        settings.settings.value = current.copy(
            notices = current.notices.copy(revenueLimit = RevenueLimit(Money(amount), months)),
        )
    }

    private fun sales(net: Long, refund: Long = 0) = PeriodTotals(
        ProfitTotals(
            salesNetTotal = Money(net),
            returnsNetRefund = Money(refund),
            salesTotalCost = Money.ZERO,
            returnsCostReturned = Money.ZERO,
            recordedExpenses = Money.ZERO,
            netShrinkage = Money.ZERO,
            paymentFees = Money.ZERO,
            cashRoundingTotal = Money.ZERO,
        ),
        salesCount = 1,
    )

    @Test
    fun `without a limit there is nothing to show`() = runTest {
        assertNull(observe(november).first())
    }

    @Test
    fun `the example of the rule reaches the first notice over twelve months`() = runTest {
        limit(10_000_000, 12)
        // Lo devuelto se resta: 8300000 vendidos y 100000 devueltos son 8200000.
        reports.totals.value = mapOf(window to sales(net = 8_300_000, refund = 100_000))
        planning.firstSaleDate.value = date(2024, 3, 2)

        val view = requireNotNull(observe(november).first())

        assertEquals(window, view.window)
        assertEquals(Money(8_200_000), view.accumulated)
        assertEquals(BasisPoints(8_200), view.used)
        assertEquals(LimitLevel.FIRST_NOTICE, view.level)
        assertNull(view.since)
    }

    @Test
    fun `a shorter history says since when it adds up`() = runTest {
        limit(10_000_000, 12)
        planning.firstSaleDate.value = date(2026, 3, 9)

        val view = requireNotNull(observe(november).first())

        assertEquals(date(2026, 3, 9), view.since)
        assertEquals(LimitLevel.BELOW, view.level)
    }

    @Test
    fun `a sum that does not fit has no value and no notice`() = runTest {
        limit(10_000_000, 12)
        reports.totals.value = mapOf(window to sales(net = Long.MIN_VALUE, refund = 1))

        val view = requireNotNull(observe(november).first())

        assertNull(view.accumulated)
        assertNull(view.used)
        assertEquals(LimitLevel.BELOW, view.level)
    }

    @Test
    fun `the month in progress follows the business calendar`() = runTest {
        limit(10_000_000, 1)
        val current = ObserveCurrentRevenueLimitUseCase(
            BusinessCalendarSource(settings),
            observe,
            Clock { Instant.parse("2026-11-04T15:00:00Z") },
        )

        val view = requireNotNull(current(flowOf(Unit)).first())

        assertEquals(ReportPeriod.Range(date(2026, 11, 1), date(2026, 11, 30)), view.window)
    }

    @Test
    fun `without a known zone there is no month and no limit`() = runTest {
        limit(10_000_000, 12)
        val unknown = FakeAppSettingsRepository(
            appSettings(regionalSettings("Nowhere/Unknown", DayOfWeek.MONDAY)),
        )
        val current = ObserveCurrentRevenueLimitUseCase(
            BusinessCalendarSource(unknown),
            observe,
            Clock { Instant.parse("2026-11-04T15:00:00Z") },
        )

        assertNull(current(flowOf(Unit)).first())
    }

    @Test
    fun `saving a limit writes amount and months in one counted transaction`() = runTest {
        val result = save(RevenueLimit(Money(10_000_000), 12))

        assertEquals(SaveLimitResult.Saved, result)
        assertEquals(
            RevenueLimit(Money(10_000_000), 12),
            settings.settings.value.notices.revenueLimit,
        )
        assertEquals(1, runner.commits)
    }

    @Test
    fun `saving the same limit writes nothing and removing it clears both values`() = runTest {
        limit(10_000_000, 12)

        assertEquals(SaveLimitResult.Saved, save(RevenueLimit(Money(10_000_000), 12)))
        assertEquals(0, runner.commits)
        assertEquals(SaveLimitResult.Removed, save(null))
        assertNull(settings.settings.value.notices.revenueLimit)
        assertEquals(1, runner.commits)
    }

    @Test
    fun `removing a limit that does not exist changes nothing`() = runTest {
        assertEquals(SaveLimitResult.Removed, save(null))
        assertEquals(emptyList<String>(), goals.writes)
    }

    @Test
    fun `a zero amount or months out of range are rejected before writing`() = runTest {
        assertEquals(
            SaveLimitResult.Invalid(amount = true, months = false),
            save(RevenueLimit(Money.ZERO, 12)),
        )
        assertEquals(
            SaveLimitResult.Invalid(amount = false, months = true),
            save(RevenueLimit(Money(1), 61)),
        )
        assertEquals(0, runner.writeCalls)
    }

    @Test
    fun `a storage failure is returned`() = runTest {
        goals.failNextWrite = StorageFailureException(StorageFailure.DISK_FULL)

        assertEquals(
            SaveLimitResult.Failed(StorageFailure.DISK_FULL),
            save(RevenueLimit(Money(10_000_000), 12)),
        )
        assertNull(settings.settings.value.notices.revenueLimit)
    }

    @Test
    fun `each level is notified once, and only noted when it went out`() = runTest {
        val store = MemoryStore()
        val shown = mutableListOf<LimitLevel>()
        var allowed = false
        val deliver = DeliverRevenueLimitNoticeUseCase({ level, _ ->
            if (allowed) shown += level
            allowed
        }, store)
        limit(10_000_000, 12)
        reports.totals.value = mapOf(window to sales(net = 8_200_000))
        val first = observe(november).first()

        // Sin permiso no sale ni se anota: se intenta con el siguiente cambio.
        assertFalse(deliver(first))
        assertNull(store.mark)

        allowed = true
        assertTrue(deliver(first))
        assertFalse(deliver(first))
        reports.totals.value = mapOf(window to sales(net = 9_600_000))
        assertTrue(deliver(observe(november).first()))

        assertEquals(listOf(LimitLevel.FIRST_NOTICE, LimitLevel.SECOND_NOTICE), shown)
        assertEquals(
            LimitNoticeMark(window.start, Money(10_000_000), 12, LimitLevel.SECOND_NOTICE),
            store.mark,
        )
    }

    @Test
    fun `without a limit or below the first notice nothing goes out`() = runTest {
        val deliver =
            DeliverRevenueLimitNoticeUseCase({ _, _ -> error("No debía avisar") }, MemoryStore())

        assertFalse(deliver(null))
        limit(10_000_000, 12)
        assertFalse(deliver(observe(november).first()))
    }

    private class MemoryStore : LimitNoticeStore {
        var mark: LimitNoticeMark? = null

        override suspend fun read(): LimitNoticeMark? = mark

        override suspend fun write(mark: LimitNoticeMark) {
            this.mark = mark
        }
    }
}
