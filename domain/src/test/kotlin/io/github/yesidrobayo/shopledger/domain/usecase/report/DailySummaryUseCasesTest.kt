package io.github.yesidrobayo.shopledger.domain.usecase.report

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.report.DailySummary
import io.github.yesidrobayo.shopledger.domain.model.report.DailySummarySchedule
import io.github.yesidrobayo.shopledger.domain.model.report.PeriodTotals
import io.github.yesidrobayo.shopledger.domain.model.settings.AppSettings
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.rule.report.DailySummaryRules
import io.github.yesidrobayo.shopledger.domain.testing.FakeAppSettingsRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakePeriodReportRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakeTransactionRunner
import io.github.yesidrobayo.shopledger.domain.testing.FixedClock
import io.github.yesidrobayo.shopledger.domain.testing.InMemoryDatabase
import io.github.yesidrobayo.shopledger.domain.testing.appSettings
import io.github.yesidrobayo.shopledger.domain.testing.regionalSettings
import kotlin.time.Instant
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * El resumen del día: apagado por defecto, nulo no programa nada, la hora se guarda sin segundos
 * en su transacción, sale al día siguiente si la hora ya pasó, y sus cifras son las de `Números`.
 * Montos inventados; el reloj marca el 2 de noviembre de 2026 a la 01:13 en la zona del negocio.
 */
class DailySummaryUseCasesTest {
    private val database = InMemoryDatabase()
    private val transactions = FakeTransactionRunner(database)
    private val base = appSettings(regionalSettings("Etc/GMT+5", DayOfWeek.MONDAY))
    private val settings = FakeAppSettingsRepository(base)
    private val clock = FixedClock.atEpochMillis(1_793_600_000_000L)
    private val zone = requireNotNull(BusinessTimeZone.of("Etc/GMT+5"))
    private val evening = LocalTime(20, 30)

    private fun AppSettings.at(time: LocalTime?) =
        copy(notices = notices.copy(dailySummaryTime = time))

    @Test
    fun `it starts off and null schedules nothing`() {
        assertNull(base.notices.dailySummaryTime)
        assertNull(DailySummaryRules.scheduleOf(base))
        assertEquals(
            DailySummarySchedule(evening, zone),
            DailySummaryRules.scheduleOf(base.at(evening)),
        )
        // Sin una zona conocida no se sabe qué día es: tampoco se programa.
        val unknown = appSettings(regionalSettings("Nowhere/Unknown", DayOfWeek.MONDAY))
        assertNull(DailySummaryRules.scheduleOf(unknown.at(evening)))
    }

    @Test
    fun `the next run is today if the time has not passed, and tomorrow if it has`() {
        val schedule = DailySummarySchedule(evening, zone)
        // 01:13 en la zona: hoy a las 20:30, que en UTC son las 01:30 del día siguiente.
        assertEquals(
            Instant.parse("2026-11-03T01:30:00Z"),
            DailySummaryRules.nextRun(clock.now(), schedule),
        )
        // Justo a la hora ya pasó: mañana.
        assertEquals(
            Instant.parse("2026-11-04T01:30:00Z"),
            DailySummaryRules.nextRun(Instant.parse("2026-11-03T01:30:00Z"), schedule),
        )
    }

    @Test
    fun `the time is saved without seconds, null turns it off, and the same writes nothing`() =
        runTest {
            val set = SetDailySummaryTimeUseCase(transactions, settings, clock)

            assertEquals(SetDailySummaryTimeResult.Saved, set(LocalTime(20, 30, 45)))
            assertEquals(evening, settings.settings.value.notices.dailySummaryTime)
            assertEquals(SetDailySummaryTimeResult.Saved, set(evening))
            assertEquals(SetDailySummaryTimeResult.Saved, set(null))

            assertNull(settings.settings.value.notices.dailySummaryTime)
            assertEquals(2, transactions.commits)
            assertEquals(clock.now(), settings.settings.value.updatedAt)
        }

    @Test
    fun `a storage failure is returned`() = runTest {
        transactions.restoreInProgress = true

        assertEquals(
            SetDailySummaryTimeResult.Failed(StorageFailure.RESTORE_IN_PROGRESS),
            SetDailySummaryTimeUseCase(transactions, settings, clock)(evening),
        )
    }

    @Test
    fun `the schedule is seen again only when it changes`() = runTest {
        val seen = mutableListOf<DailySummarySchedule?>()
        val job = launch { ObserveDailySummaryScheduleUseCase(settings)().take(2).toList(seen) }
        testScheduler.runCurrent()

        settings.settings.value = base.copy(updatedAt = clock.now())
        testScheduler.runCurrent()
        settings.settings.value = base.at(evening)
        testScheduler.runCurrent()
        job.join()

        assertEquals(listOf(null, DailySummarySchedule(evening, zone)), seen)
    }

    @Test
    fun `the summary of today has the figures of Numbers`() = runTest {
        val reports = FakePeriodReportRepository()
        val today = ReportPeriod.Day(BusinessDate(LocalDate(2026, 11, 2)))
        reports.totals.value =
            mapOf(
                today to
                    PeriodTotals.ZERO.copy(
                        profit =
                            PeriodTotals.ZERO.profit.copy(
                                salesNetTotal = Money(340_000),
                                salesTotalCost = Money(200_000),
                            ),
                        salesCount = 12,
                    ),
            )
        val build = BuildDailySummaryUseCase(settings, reports, clock)

        // Apagado: no hay resumen.
        assertNull(build())

        settings.settings.value = base.at(evening)
        assertEquals(
            DailySummary(today.date, 12, Money(340_000), Money(140_000), discreet = false),
            build(),
        )

        val discreet = base.at(evening)
        settings.settings.value =
            discreet.copy(preferences = discreet.preferences.copy(discreetModeEnabled = true))
        assertEquals(true, build()?.discreet)

        settings.settings.value =
            appSettings(regionalSettings("Nowhere/Unknown", DayOfWeek.MONDAY)).at(evening)
        assertNull(build())
    }

    @Test
    fun `an absurd figure gives no summary`() = runTest {
        val reports = FakePeriodReportRepository()
        val today = ReportPeriod.Day(BusinessDate(LocalDate(2026, 11, 2)))
        reports.totals.value =
            mapOf(
                today to
                    PeriodTotals.ZERO.copy(
                        profit =
                            PeriodTotals.ZERO.profit.copy(
                                salesNetTotal = Money(Long.MAX_VALUE),
                                returnsNetRefund = Money(-1),
                            ),
                    ),
            )
        settings.settings.value = base.at(evening)

        assertNull(BuildDailySummaryUseCase(settings, reports, clock)())
        // Se pidió solo el día de hoy en la zona del negocio.
        assertEquals(listOf<ReportPeriod>(today), reports.asked)
    }
}
