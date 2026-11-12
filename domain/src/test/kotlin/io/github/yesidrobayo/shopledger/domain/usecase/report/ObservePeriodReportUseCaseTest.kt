package io.github.yesidrobayo.shopledger.domain.usecase.report

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Margin
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.ProfitTotals
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.report.PeriodTotals
import io.github.yesidrobayo.shopledger.domain.testing.FakePeriodReportRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Las métricas de un período para las estadísticas, sobre sumas en memoria. Datos inventados. */
@OptIn(ExperimentalCoroutinesApi::class)
class ObservePeriodReportUseCaseTest {
    private val reports = FakePeriodReportRepository()
    private val observe = ObservePeriodReportUseCase(reports)
    private val november = ReportPeriod.Month(YearMonth(2026, 11))

    @Test
    fun `an empty month reports zeros and says it had no movements`() = runTest {
        val report = observe(november).first()

        assertEquals(november, report.period)
        val metrics = requireNotNull(report.metrics)
        assertFalse(metrics.hasMovements)
        assertEquals(Money.ZERO, metrics.profit.netSales)
        assertEquals(Money.ZERO, metrics.profit.netProfit)
        assertEquals(Margin.NoData, metrics.profit.grossMargin)
        assertNull(metrics.averageTicket)
        assertEquals(listOf<ReportPeriod>(november), reports.asked)
    }

    @Test
    fun `the month reads only its own sums and computes its profit`() = runTest {
        reports.totals.value = mapOf(
            november to totals(net = 4_250_000, cost = 2_720_000, count = 312, expenses = 640_000),
            ReportPeriod.Month(YearMonth(2026, 10)) to totals(net = 99, count = 1),
        )

        val metrics = requireNotNull(observe(november).first().metrics)

        assertEquals(Money(1_530_000), metrics.profit.grossProfit)
        assertEquals(Margin.Defined(BasisPoints(3_600)), metrics.profit.grossMargin)
        assertEquals(Money(890_000), metrics.profit.netProfit)
        assertEquals(Money(13_622), metrics.averageTicket)
        assertTrue(metrics.hasMovements)
    }

    @Test
    fun `a confirmed expense arrives without asking again`() = runTest {
        val seen = mutableListOf<PeriodReport>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            observe(november).collect { seen += it }
        }

        reports.totals.value = mapOf(november to totals(expenses = 45_000))
        job.cancel()

        assertEquals(2, seen.size)
        assertEquals(Money(-45_000), seen.last().metrics?.profit?.netProfit)
        assertEquals(1, reports.asked.size)
    }

    @Test
    fun `a sum that does not fit shows no value instead of failing`() = runTest {
        reports.totals.value =
            mapOf(november to totals(net = Long.MIN_VALUE, refund = 1, count = 1))

        assertEquals(PeriodReport(november, null), observe(november).first())
    }

    private fun totals(
        net: Long = 0,
        cost: Long = 0,
        count: Long = 0,
        refund: Long = 0,
        expenses: Long = 0,
    ) = PeriodTotals(
        ProfitTotals(
            salesNetTotal = Money(net),
            returnsNetRefund = Money(refund),
            salesTotalCost = Money(cost),
            returnsCostReturned = Money.ZERO,
            recordedExpenses = Money(expenses),
            netShrinkage = Money.ZERO,
            paymentFees = Money.ZERO,
            cashRoundingTotal = Money.ZERO,
        ),
        salesCount = count,
    )
}
