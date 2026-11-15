package io.github.yesidrobayo.shopledger.domain.usecase.report

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.report.PeriodTotals
import io.github.yesidrobayo.shopledger.domain.model.report.PriceIndexEntry
import io.github.yesidrobayo.shopledger.domain.model.report.PriceIndexView
import io.github.yesidrobayo.shopledger.domain.model.report.RealComparison
import io.github.yesidrobayo.shopledger.domain.model.report.RealValue
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.testing.FakeAppSettingsRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakePeriodReportRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakePriceIndexRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakeTransactionRunner
import io.github.yesidrobayo.shopledger.domain.testing.FixedClock
import io.github.yesidrobayo.shopledger.domain.testing.InMemoryDatabase
import io.github.yesidrobayo.shopledger.domain.testing.appSettings
import io.github.yesidrobayo.shopledger.domain.testing.regionalSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * El índice de precios: se guarda un mes por vez, nunca en el futuro ni con un cambio imposible,
 * se edita y se quita en su transacción, y la comparación real de un mes sale de él sin suponer
 * nada.
 * Valores inventados; el reloj marca el 2 de noviembre de 2026.
 */
class PriceIndexUseCasesTest {
    private val database = InMemoryDatabase()
    private val transactions = FakeTransactionRunner(database)
    private val settings =
        FakeAppSettingsRepository(appSettings(regionalSettings("Etc/GMT+5", DayOfWeek.MONDAY)))
    private val index = FakePriceIndexRepository(database)
    private val clock = FixedClock.atEpochMillis(1_793_600_000_000L)
    private val save = SavePriceIndexMonthUseCase(transactions, settings, index, clock)
    private val delete = DeletePriceIndexMonthUseCase(transactions, index)

    private val october = YearMonth(2026, 10)
    private val november = YearMonth(2026, 11)

    @Test
    fun `a new month is saved with its source in one transaction`() = runTest {
        assertEquals(
            SavePriceIndexResult.Saved,
            save(october, BasisPoints(190), "  Lista propia  ", editing = false),
        )

        assertEquals(
            listOf(PriceIndexEntry(october, BasisPoints(190), "Lista propia")),
            index.observeEntries().first(),
        )
        assertEquals(1, transactions.commits)
    }

    @Test
    fun `adding a month that already has a value is rejected`() = runTest {
        save(october, BasisPoints(190), null, editing = false)

        assertEquals(
            SavePriceIndexResult.MonthTaken,
            save(october, BasisPoints(250), null, editing = false),
        )
        assertEquals(BasisPoints(190), index.observeEntries().first().single().change)
    }

    @Test
    fun `editing changes the value and the source, and the same value writes nothing`() = runTest {
        save(october, BasisPoints(190), "Antes", editing = false)

        assertEquals(
            SavePriceIndexResult.Saved,
            save(october, BasisPoints(-50), " ", editing = true),
        )
        assertEquals(
            PriceIndexEntry(october, BasisPoints(-50), null),
            index.observeEntries().first().single(),
        )
        assertEquals(SavePriceIndexResult.Saved, save(october, BasisPoints(-50), null, true))
        assertEquals(2, transactions.commits)
    }

    @Test
    fun `this month is allowed and a later one is not`() = runTest {
        assertEquals(SavePriceIndexResult.Saved, save(november, BasisPoints(10), null, false))
        assertEquals(
            SavePriceIndexResult.FutureMonth,
            save(YearMonth(2026, 12), BasisPoints(10), null, false),
        )
    }

    @Test
    fun `an impossible change or a long source is rejected before writing`() = runTest {
        assertEquals(
            SavePriceIndexResult.ChangeOutOfRange,
            save(october, BasisPoints(-10_000), null, false),
        )
        assertEquals(
            SavePriceIndexResult.NoteTooLong,
            save(october, BasisPoints(10), "x".repeat(121), false),
        )
        assertEquals(0, transactions.writeCalls)
    }

    @Test
    fun `without a known zone there is no month of today`() = runTest {
        settings.settings.value =
            appSettings(regionalSettings("Nowhere/Unknown", DayOfWeek.MONDAY))

        assertEquals(
            SavePriceIndexResult.UnknownTimeZone,
            save(october, BasisPoints(10), null, false),
        )
    }

    @Test
    fun `a storage failure is returned`() = runTest {
        transactions.restoreInProgress = true

        assertEquals(
            SavePriceIndexResult.Failed(StorageFailure.RESTORE_IN_PROGRESS),
            save(october, BasisPoints(10), null, false),
        )
        assertEquals(
            DeletePriceIndexResult.Failed(StorageFailure.RESTORE_IN_PROGRESS),
            delete(october),
        )
    }

    @Test
    fun `deleting a month removes it, and a month without value writes nothing`() = runTest {
        save(october, BasisPoints(190), null, false)

        assertEquals(DeletePriceIndexResult.Deleted, delete(october))
        assertEquals(DeletePriceIndexResult.Deleted, delete(october))

        assertEquals(emptyList<PriceIndexEntry>(), index.observeEntries().first())
        assertEquals(2, transactions.commits)
        assertEquals(1, transactions.rollbacks)
    }

    @Test
    fun `the screen gets the newest month first and the gaps in between`() = runTest {
        save(YearMonth(2026, 7), BasisPoints(100), null, false)
        save(october, BasisPoints(190), null, false)

        val view = ObservePriceIndexUseCase(index)().first()

        assertEquals(listOf(october, YearMonth(2026, 7)), view.entries.map { it.month })
        assertEquals(listOf(YearMonth(2026, 8), YearMonth(2026, 9)), view.missing)
        assertEquals(PriceIndexView(emptyList(), emptyList()), emptyView())
    }

    @Test
    fun `a closed month is compared in real terms with the previous one`() = runTest {
        val reports = FakePeriodReportRepository()
        val month = ReportPeriod.Month(october)
        val september = ReportPeriod.Month(YearMonth(2026, 9))
        reports.totals.value = mapOf(month to sold(1_100_000), september to sold(1_000_000))
        save(october, BasisPoints(300), null, false)

        val compared =
            ObserveRealComparisonUseCase(reports, index)(month, TODAY).first()

        assertEquals(
            RealComparison(
                period = month,
                comparedTo = september,
                base = Money(1_000_000),
                nominal = Money(1_100_000),
                nominalVariation = BasisPoints(1_000),
                real = RealValue.Estimated(Money(1_067_961), BasisPoints(680)),
            ),
            compared,
        )
    }

    @Test
    fun `only a month is compared in real terms`() = runTest {
        val reports = FakePeriodReportRepository()
        val compare = ObserveRealComparisonUseCase(reports, index)

        assertNull(compare(ReportPeriod.Day(TODAY), TODAY).first())
        assertNull(compare(ReportPeriod.Range(TODAY, TODAY), TODAY).first())
    }

    private suspend fun emptyView(): PriceIndexView =
        ObservePriceIndexUseCase(FakePriceIndexRepository(InMemoryDatabase()))().first()

    private fun sold(net: Long) = PeriodTotals.ZERO.copy(
        profit = PeriodTotals.ZERO.profit.copy(salesNetTotal = Money(net)),
        salesCount = 1,
    )

    private companion object {
        val TODAY = BusinessDate(LocalDate(2026, 11, 2))
    }
}
