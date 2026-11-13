package io.github.yesidrobayo.shopledger.domain.rule.report

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.report.DayCollection
import io.github.yesidrobayo.shopledger.domain.model.report.DayPoint
import io.github.yesidrobayo.shopledger.domain.model.report.SoldAndCollected
import io.github.yesidrobayo.shopledger.domain.port.CollectionReportRepository
import io.github.yesidrobayo.shopledger.domain.usecase.report.ObserveSoldAndCollectedUseCase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Vendido y cobrado, con los ejemplos de la regla escritos a mano: nunca se suman. */
class SoldAndCollectedRulesTest {
    @Test
    fun `the rule example keeps november sold and december collected apart`() {
        // Venta de 10000 en noviembre: 3000 en efectivo y 7000 a crédito. Abono de 4000 en diciembre.
        val november = listOf(day("2026-11-14", sold = 10_000, atSale = 3_000))
        val december = listOf(day("2026-12-03", onAccount = 4_000))

        assertEquals(
            SoldAndCollected(Money(10_000), Money(3_000), Money.ZERO),
            SoldAndCollectedRules.total(november),
        )
        assertEquals(Money(3_000), SoldAndCollectedRules.total(november)?.collected)
        val inDecember = requireNotNull(SoldAndCollectedRules.total(december))
        assertEquals(Money.ZERO, inDecember.sold)
        assertEquals(Money(4_000), inDecember.collected)
        // Quedan 7000 − 4000 = 3000 por cobrar: lo vendido menos todo lo cobrado.
        assertEquals(
            Money(3_000),
            Money(10_000) - Money(3_000) - inDecember.collectedOnAccount,
        )
    }

    @Test
    fun `a period sums its days and an empty one is zero`() {
        val days = listOf(
            day("2026-11-01", sold = 5_000, atSale = 5_000),
            day("2026-11-02", sold = 2_000, atSale = 500, onAccount = 1_000),
        )
        assertEquals(
            SoldAndCollected(Money(7_000), Money(5_500), Money(1_000)),
            SoldAndCollectedRules.total(days),
        )
        assertEquals(SoldAndCollected.ZERO, SoldAndCollectedRules.total(emptyList()))
    }

    @Test
    fun `an absurd sum gives nothing instead of crashing`() {
        val huge = day("2026-11-01", sold = Long.MAX_VALUE)
        assertNull(SoldAndCollectedRules.total(listOf(huge, huge)))
        // Cada parte cabe, pero lo cobrado junto no.
        val split = day("2026-11-01", atSale = Long.MAX_VALUE, onAccount = 1)
        assertNull(SoldAndCollectedRules.total(listOf(split)))
        assertNull(SoldAndCollectedRules.withCollected(listOf(point("2026-11-01")), listOf(split)))
    }

    @Test
    fun `each chart day gets what was collected that day and zero otherwise`() {
        val points = listOf(point("2026-11-01"), point("2026-11-02"))
        val days = listOf(day("2026-11-02", sold = 9_000, atSale = 2_000, onAccount = 1_500))

        assertEquals(
            listOf(Money.ZERO, Money(3_500)),
            SoldAndCollectedRules.withCollected(points, days)?.map { it.collected },
        )
    }

    @Test
    fun `without credit sales there is nothing apart and the chart is untouched`() = runBlocking {
        val repository = FakeCollections(onCredit = false)
        val useCase = ObserveSoldAndCollectedUseCase(repository, flowOf(Money(3_000)))
        val points = listOf(point("2026-11-02"))

        assertNull(useCase(NOVEMBER).first())
        assertEquals(points, useCase.series(NOVEMBER, flowOf(points)).first())
        assertNull(useCase.series(NOVEMBER, flowOf(null)).first())

        repository.onCredit.value = true
        val view = requireNotNull(useCase(NOVEMBER).first())
        assertEquals(Money(3_500), view.totals?.collected)
        assertEquals(Money(3_000), view.receivable)
        assertEquals(
            listOf(Money(3_500)),
            useCase.series(NOVEMBER, flowOf(points)).first()?.map { it.collected },
        )
    }

    private class FakeCollections(onCredit: Boolean) : CollectionReportRepository {
        val onCredit = MutableStateFlow(onCredit)

        override fun observeDaily(period: ReportPeriod): Flow<List<DayCollection>> =
            flowOf(listOf(day("2026-11-02", sold = 9_000, atSale = 2_000, onAccount = 1_500)))

        override fun observeOnCredit(): Flow<Boolean> = onCredit
    }

    private companion object {
        val NOVEMBER = ReportPeriod.Month(YearMonth(2026, 11))

        fun date(text: String) = requireNotNull(BusinessDate.parse(text))

        fun day(text: String, sold: Long = 0, atSale: Long = 0, onAccount: Long = 0) =
            DayCollection(date(text), Money(sold), Money(atSale), Money(onAccount))

        fun point(text: String) = DayPoint(date(text), Money.ZERO, Money.ZERO)
    }
}
