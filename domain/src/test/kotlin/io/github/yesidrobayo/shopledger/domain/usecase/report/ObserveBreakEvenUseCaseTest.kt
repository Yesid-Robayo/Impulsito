package io.github.yesidrobayo.shopledger.domain.usecase.report

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.ProfitTotals
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.report.BreakEven
import io.github.yesidrobayo.shopledger.domain.model.report.PeriodTotals
import io.github.yesidrobayo.shopledger.domain.testing.FakePeriodReportRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakePlanningReportRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * El punto de equilibrio de un período con el margen de la misma cascada de las estadísticas.
 * Montos inventados y valores escritos a mano.
 */
class ObserveBreakEvenUseCaseTest {
    private val planning = FakePlanningReportRepository()
    private val reports = FakePeriodReportRepository()
    private val observe = ObserveBreakEvenUseCase(planning, ObservePeriodReportUseCase(reports))
    private val november = ReportPeriod.Month(YearMonth(2026, 11))

    @Test
    fun `the month of the rule needs 1777778 in sales`() = runTest {
        // Ventas 1000000 y costo 640000: margen de 3600 bp. Gastos fijos 640000.
        reports.totals.value = mapOf(
            november to PeriodTotals(
                ProfitTotals(
                    salesNetTotal = Money(1_000_000),
                    returnsNetRefund = Money.ZERO,
                    salesTotalCost = Money(640_000),
                    returnsCostReturned = Money.ZERO,
                    recordedExpenses = Money(640_000),
                    netShrinkage = Money.ZERO,
                    paymentFees = Money.ZERO,
                    cashRoundingTotal = Money.ZERO,
                ),
                salesCount = 10,
            ),
        )
        planning.fixedExpenses.value = mapOf(november to Money(640_000))

        val result = observe(november).first() as BreakEven.Calculated

        assertEquals(BasisPoints(3_600), result.margin)
        assertEquals(Money(1_777_778), result.breakEvenSales)
        assertEquals(Money(777_778), result.remaining)
    }

    @Test
    fun `fixed expenses without sales show no value and a notice`() = runTest {
        planning.fixedExpenses.value = mapOf(november to Money(640_000))

        assertEquals(BreakEven.NoMargin(Money(640_000)), observe(november).first())
    }

    @Test
    fun `a month without sales or fixed expenses hides the tool`() = runTest {
        assertNull(observe(november).first())
    }
}
