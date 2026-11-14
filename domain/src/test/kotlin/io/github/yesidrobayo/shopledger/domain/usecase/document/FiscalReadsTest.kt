package io.github.yesidrobayo.shopledger.domain.usecase.document

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.DocumentStatus
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.document.FiscalMarkStatus
import io.github.yesidrobayo.shopledger.domain.model.document.FiscalSale
import io.github.yesidrobayo.shopledger.domain.model.document.SaleFiscalMark
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La lista del mes por marca: una venta sin marca está pendiente, la anulada no aparece, y el
 * resumen cuenta y suma lo que se ve. Datos inventados.
 */
class FiscalReadsTest {
    private val marks = FakeSaleFiscalMarkRepository()
    private val observe = ObserveFiscalMonthUseCase(marks)

    init {
        marks.seed(
            FakeSale(1204L, OCT_28, Money(20_900), customerName = "Cliente de prueba"),
            FakeSale(1198L, OCT_27, Money(12_500), hasReturn = true),
            FakeSale(1187L, OCT_25, Money(8_400)),
            FakeSale(1150L, OCT_25, Money(5_000), status = DocumentStatus.VOIDED),
            FakeSale(1300L, BusinessDate(LocalDate(2026, 11, 1)), Money(1_000)),
        )
    }

    @Test
    fun `pending lists the sales without mark or in pending, newest first, without voided ones`() =
        runTest {
            marks.seedMark(SaleFiscalMark(1187L, FiscalMarkStatus.PENDING, "X-1", null))

            val month = observe(OCTOBER, FiscalMarkStatus.PENDING).first()

            assertEquals(listOf(1204L, 1198L, 1187L), month.sales.map { it.saleId })
            assertEquals(Money(41_800), month.total)
            assertTrue(month.hasMarks)
            assertEquals(
                FiscalSale(
                    1198L,
                    OCT_27,
                    Money(12_500),
                    null,
                    FiscalMarkStatus.PENDING,
                    null,
                    true,
                ),
                month.sales[1],
            )
        }

    @Test
    fun `each mark has its own list, and the first use has no marks`() = runTest {
        assertFalse(observe(OCTOBER, FiscalMarkStatus.PENDING).first().hasMarks)

        marks.seedMark(SaleFiscalMark(1204L, FiscalMarkStatus.RECORDED, "A-1043", AT))

        val recorded = observe(OCTOBER, FiscalMarkStatus.RECORDED).first()
        assertEquals(listOf(1204L), recorded.sales.map { it.saleId })
        assertEquals("A-1043", recorded.sales.single().externalReference)
        val notNeeded = observe(OCTOBER, FiscalMarkStatus.NOT_NEEDED).first()
        assertEquals(emptyList<FiscalSale>(), notNeeded.sales)
    }

    @Test
    fun `a total that does not fit is shown without value`() = runTest {
        marks.seed(FakeSale(1L, OCT_25, Money(Long.MAX_VALUE)))

        assertNull(observe(OCTOBER, FiscalMarkStatus.PENDING).first().total)
    }

    private companion object {
        val OCTOBER = YearMonth(2026, 10)
        val OCT_28 = BusinessDate(LocalDate(2026, 10, 28))
        val OCT_27 = BusinessDate(LocalDate(2026, 10, 27))
        val OCT_25 = BusinessDate(LocalDate(2026, 10, 25))
        val AT: Instant = Instant.fromEpochMilliseconds(1_793_000_000_000)
    }
}
