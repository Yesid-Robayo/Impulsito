package io.github.yesidrobayo.shopledger.domain.usecase.home

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.ProfitTotals
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.report.PeriodTotals
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.testing.FakeAppSettingsRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakePeriodReportRepository
import io.github.yesidrobayo.shopledger.domain.testing.appSettings
import io.github.yesidrobayo.shopledger.domain.testing.regionalSettings
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.domain.usecase.report.ObservePeriodReportUseCase
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * La ganancia final de la semana y del mes en Inicio: los mismos rangos y la misma cuenta que las
 * estadísticas, con la semana del día que eligió el negocio. Montos inventados, valores escritos a
 * mano.
 */
class ObserveHomeProfitUseCaseTest {
    private val reports = FakePeriodReportRepository()

    /** Miércoles 2026-11-04 a las 10:00 en una zona cinco horas al oeste de UTC. */
    private var now = Instant.parse("2026-11-04T15:00:00Z")

    private fun observe(zone: String = "Etc/GMT+5", weekStart: DayOfWeek = DayOfWeek.MONDAY) =
        ObserveHomeProfitUseCase(
            BusinessCalendarSource(
                FakeAppSettingsRepository(appSettings(regionalSettings(zone, weekStart))),
            ),
            ObservePeriodReportUseCase(reports),
            Clock { now },
        )

    private fun date(y: Int, m: Int, d: Int) = BusinessDate(LocalDate(y, m, d))

    private fun totals(net: Long, cost: Long, expenses: Long) = PeriodTotals(
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
    fun `the week and the month in progress show their final profit`() = runTest {
        val week = ReportPeriod.Week(date(2026, 11, 2))
        val month = ReportPeriod.Month(YearMonth(2026, 11))
        reports.totals.value = mapOf(
            // 300000 - 180000 - 50000 = 70000.
            week to totals(net = 300_000, cost = 180_000, expenses = 50_000),
            // 4250000 - 2720000 - 640000 = 890000, el mes del ejemplo de las estadísticas.
            month to totals(net = 4_250_000, cost = 2_720_000, expenses = 640_000),
        )

        val profit = requireNotNull(observe()(flowOf(Unit)).first())

        assertEquals(week, profit.week)
        assertEquals(Money(70_000), profit.weekProfit)
        assertEquals(month, profit.month)
        assertEquals(Money(890_000), profit.monthProfit)
    }

    @Test
    fun `expenses without sales give a negative final profit`() = runTest {
        reports.totals.value = mapOf(
            ReportPeriod.Month(
                YearMonth(2026, 11),
            ) to totals(net = 0, cost = 0, expenses = 600_000),
        )

        val profit = requireNotNull(observe()(flowOf(Unit)).first())

        assertEquals(Money(-600_000), profit.monthProfit)
        assertEquals(Money.ZERO, profit.weekProfit)
    }

    @Test
    fun `the week starts on the day the business chose`() = runTest {
        val profit = requireNotNull(observe(weekStart = DayOfWeek.SUNDAY)(flowOf(Unit)).first())

        // El miércoles 4 cae en la semana que empezó el domingo 1.
        assertEquals(ReportPeriod.Week(date(2026, 11, 1)), profit.week)
    }

    @Test
    fun `the month follows the business date, not UTC`() = runTest {
        // Las 23:30 del 30 de noviembre en la zona del negocio ya son 1 de diciembre en UTC.
        now = Instant.parse("2026-12-01T04:30:00Z")
        assertEquals(
            ReportPeriod.Month(YearMonth(2026, 11)),
            observe()(flowOf(Unit)).first()?.month,
        )
        now = Instant.parse("2026-12-01T05:30:00Z")
        assertEquals(
            ReportPeriod.Month(YearMonth(2026, 12)),
            observe()(flowOf(Unit)).first()?.month,
        )
    }

    @Test
    fun `an unknown zone has no week nor month`() = runTest {
        assertNull(observe(zone = "Nowhere/Unknown")(flowOf(Unit)).first())
    }
}
