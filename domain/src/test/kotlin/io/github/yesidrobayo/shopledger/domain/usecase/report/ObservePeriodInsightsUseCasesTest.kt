package io.github.yesidrobayo.shopledger.domain.usecase.report

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.ProfitTotals
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodKind
import io.github.yesidrobayo.shopledger.domain.model.report.ExpenseCategoryTotal
import io.github.yesidrobayo.shopledger.domain.model.report.PaymentMethodTotal
import io.github.yesidrobayo.shopledger.domain.model.report.PeriodNoticeCounts
import io.github.yesidrobayo.shopledger.domain.model.report.PeriodTotals
import io.github.yesidrobayo.shopledger.domain.model.report.ProductSales
import io.github.yesidrobayo.shopledger.domain.port.PeriodBreakdownRepository
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendar
import io.github.yesidrobayo.shopledger.domain.rule.report.ExpenseRow
import io.github.yesidrobayo.shopledger.domain.testing.FakePeriodReportRepository
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** La comparación, los destacados, los desgloses y los avisos, sobre sumas en memoria. */
class ObservePeriodInsightsUseCasesTest {
    private val reports = FakePeriodReportRepository()
    private val zone = requireNotNull(BusinessTimeZone.of("Etc/GMT+5"))
    private val calendar = BusinessCalendar(zone, DayOfWeek.MONDAY)
    private val today = BusinessDate(LocalDate(2026, 11, 14))
    private val november = ReportPeriod.Month(YearMonth(2026, 11))
    private val firstOctoberDays =
        ReportPeriod.Range(
            BusinessDate(LocalDate(2026, 10, 1)),
            BusinessDate(LocalDate(2026, 10, 14)),
        )

    @Test
    fun `the current month is compared with the same days of the month before`() = runTest {
        reports.totals.value =
            mapOf(november to totals(net = 4_250_000), firstOctoberDays to totals(net = 4_000_000))

        val compared =
            requireNotNull(ObservePeriodComparisonUseCase(reports)(november, today).first())

        assertEquals(firstOctoberDays, compared.comparedTo)
        assertEquals(BasisPoints(625), compared.variation.netSales)
    }

    @Test
    fun `an empty period before has no variation`() = runTest {
        reports.totals.value = mapOf(november to totals(net = 4_250_000))

        val compared =
            requireNotNull(ObservePeriodComparisonUseCase(reports)(november, today).first())

        assertNull(compared.variation.netSales)
    }

    @Test
    fun `a range of its own is not compared`() = runTest {
        assertNull(ObservePeriodComparisonUseCase(reports)(firstOctoberDays, today).first())
    }

    @Test
    fun `a sum that does not fit leaves no comparison`() = runTest {
        reports.totals.value = mapOf(november to totals(net = Long.MIN_VALUE, refund = 1))

        assertNull(ObservePeriodComparisonUseCase(reports)(november, today).first())
    }

    @Test
    fun `the breakdown ranks, adds the extra expense rows and asks notices up to the end`() =
        runTest {
            reports.totals.value =
                mapOf(november to totals(net = 10_000, expenses = 3_000, fees = 300))
            val breakdowns = FakeBreakdowns()

            val breakdown = ObservePeriodBreakdownUseCase(
                reports,
                breakdowns,
            )(november, calendar).first()

            assertEquals(listOf(5L), breakdown.top?.bySales?.map { it.productId })
            assertEquals(breakdowns.methods, breakdown.methods)
            assertEquals(
                listOf(
                    ExpenseRow.Category(breakdowns.categories.single()),
                    ExpenseRow.Fees(Money(300)),
                ),
                breakdown.expenses,
            )
            assertEquals(PeriodNoticeCounts(1, 2, 3, 4, 5), breakdown.notices)
            // Noviembre termina el 1 de diciembre a las 00:00 en la zona del negocio, 05:00 en UTC.
            assertEquals(Instant.parse("2026-12-01T05:00:00Z"), breakdowns.askedEnd)
        }

    @Test
    fun `a sum that does not fit hides the expense rows instead of failing`() = runTest {
        reports.totals.value = mapOf(november to totals(net = Long.MIN_VALUE, refund = 1))

        val breakdown = ObservePeriodBreakdownUseCase(
            reports,
            FakeBreakdowns(),
        )(november, calendar).first()

        assertNull(breakdown.expenses)
    }

    private class FakeBreakdowns : PeriodBreakdownRepository {
        val methods =
            listOf(
                PaymentMethodTotal(
                    "Efectivo inventado",
                    PaymentMethodKind.CASH,
                    1,
                    Money(11_900),
                    Money.ZERO,
                ),
            )
        val categories = listOf(ExpenseCategoryTotal(1L, null, "RENT", Money(3_000)))
        var askedEnd: Instant? = null

        override fun observeProductSales(period: ReportPeriod): Flow<List<ProductSales>> = flowOf(
            listOf(
                ProductSales(
                    5L,
                    "Café inventado",
                    1L,
                    QuantityMilli(2_000),
                    Money(10_000),
                    Money(6_000),
                ),
            ),
        )

        override fun observePaymentMethods(period: ReportPeriod): Flow<List<PaymentMethodTotal>> =
            flowOf(methods)

        override fun observeExpenseCategories(
            period: ReportPeriod,
        ): Flow<List<ExpenseCategoryTotal>> = flowOf(categories)

        override fun observeNoticeCounts(
            period: ReportPeriod,
            periodEnd: Instant,
        ): Flow<PeriodNoticeCounts> {
            askedEnd = periodEnd
            return flowOf(PeriodNoticeCounts(1, 2, 3, 4, 5))
        }
    }

    private fun totals(net: Long = 0, refund: Long = 0, expenses: Long = 0, fees: Long = 0) =
        PeriodTotals(
            ProfitTotals(
                salesNetTotal = Money(net),
                returnsNetRefund = Money(refund),
                salesTotalCost = Money.ZERO,
                returnsCostReturned = Money.ZERO,
                recordedExpenses = Money(expenses),
                netShrinkage = Money.ZERO,
                paymentFees = Money(fees),
                cashRoundingTotal = Money.ZERO,
            ),
            salesCount = 1,
        )
}
