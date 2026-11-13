package io.github.yesidrobayo.shopledger.domain.usecase.report

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.ProfitTotals
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.report.PeriodTotals
import io.github.yesidrobayo.shopledger.domain.model.report.ReplacementProfit
import io.github.yesidrobayo.shopledger.domain.model.report.ReplacementTotals
import io.github.yesidrobayo.shopledger.domain.model.report.RestockedReturnLine
import io.github.yesidrobayo.shopledger.domain.port.ReplacementReportRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakePeriodReportRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Las dos ganancias de un período, sobre sumas en memoria: la reposición nunca cambia la ganancia
 * en ventas. Montos inventados.
 */
class ObserveReplacementProfitUseCaseTest {
    private val reports = FakePeriodReportRepository()
    private val replacementTotals = MutableStateFlow(emptyMap<ReportPeriod, ReplacementTotals>())
    private val replacement = object : ReplacementReportRepository {
        override fun observeTotals(period: ReportPeriod): Flow<ReplacementTotals> =
            replacementTotals.map { it[period] ?: ReplacementTotals(Money.ZERO, 0, emptyList()) }
    }
    private val observe = ObserveReplacementProfitUseCase(reports, replacement)
    private val november = ReportPeriod.Month(YearMonth(2026, 11))

    private fun sold(net: Long, cost: Long, refund: Long = 0, costReturned: Long = 0) =
        PeriodTotals(
            ProfitTotals(
                salesNetTotal = Money(net),
                returnsNetRefund = Money(refund),
                salesTotalCost = Money(cost),
                returnsCostReturned = Money(costReturned),
                recordedExpenses = Money.ZERO,
                netShrinkage = Money.ZERO,
                paymentFees = Money.ZERO,
                cashRoundingTotal = Money.ZERO,
            ),
            salesCount = 1,
        )

    @Test
    fun `the two profits of the rule sit side by side`() = runTest {
        reports.totals.value = mapOf(november to sold(net = 16_000, cost = 8_000))
        replacementTotals.value =
            mapOf(november to ReplacementTotals(Money(12_000), 0, emptyList()))

        val report = observe(november).first()

        assertEquals(november, report.period)
        assertEquals(Money(8_000), report.grossProfit)
        assertEquals(
            ReplacementProfit(Money(16_000), Money(12_000), Money(4_000), 0),
            report.replacement,
        )
    }

    @Test
    fun `the replacement cost never changes the gross profit`() = runTest {
        reports.totals.value =
            mapOf(
                november to sold(net = 16_000, cost = 8_000, refund = 2_000, costReturned = 1_000),
            )
        val restocked = RestockedReturnLine(
            Money(3_000),
            QuantityMilli(2_000),
            QuantityMilli.ZERO,
            QuantityMilli(1_000),
        )
        val reportsSeen = listOf(
            ReplacementTotals(Money.ZERO, 3, emptyList()),
            ReplacementTotals(Money(12_000), 0, emptyList()),
            ReplacementTotals(Money(30_000), 0, listOf(restocked)),
        ).map { totals ->
            replacementTotals.value = mapOf(november to totals)
            observe(november).first()
        }

        // 14000 vendidos netos menos 7000 de costo: igual con cualquier reposición.
        assertEquals(List(3) { Money(7_000) }, reportsSeen.map { it.grossProfit })
        assertEquals(
            listOf(Money(14_000), Money(2_000), Money(-14_500)),
            reportsSeen.map { it.replacement?.replacementProfit },
        )
    }

    @Test
    fun `absurd sums show no value instead of failing`() = runTest {
        reports.totals.value = mapOf(november to sold(net = Long.MAX_VALUE, cost = -1))

        val report = observe(november).first()

        assertNull(report.grossProfit)
        assertNull(report.replacement)
    }
}
