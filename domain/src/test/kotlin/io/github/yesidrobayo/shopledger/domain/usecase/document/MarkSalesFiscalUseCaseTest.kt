package io.github.yesidrobayo.shopledger.domain.usecase.document

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.DocumentStatus
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.document.FiscalMarkStatus
import io.github.yesidrobayo.shopledger.domain.model.document.SaleFiscalMark
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.testing.FixedClock
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Marcar ventas en el puente fiscal: una sola transacción, la venta nunca cambia, la fecha de
 * registro solo con `RECORDED`, y con varias, todas o ninguna. Datos inventados.
 */
class MarkSalesFiscalUseCaseTest {
    private val marks = FakeSaleFiscalMarkRepository()
    private val runner = SnapshotTransactionRunner(marks)
    private val mark = MarkSalesFiscalUseCase(runner, marks, FixedClock(NOW))

    init {
        marks.seed(
            FakeSale(1L, DATE, Money(20_900)),
            FakeSale(2L, DATE, Money(12_500)),
            FakeSale(3L, DATE, Money(8_400), status = DocumentStatus.VOIDED),
        )
    }

    @Test
    fun `recording a sale with a reference stores the mark with the date and counts a change`() =
        runTest {
            val result =
                mark(listOf(1L), FiscalMarkStatus.RECORDED, ReferenceChange.Write(" A-1043 "))

            assertEquals(MarkFiscalResult.Marked(1), result)
            assertEquals(
                SaleFiscalMark(1L, FiscalMarkStatus.RECORDED, "A-1043", NOW),
                marks.findMark(1L),
            )
            assertEquals(NOW, marks.tables.value.updatedAt[1L])
            assertEquals(1, runner.commits)
        }

    @Test
    fun `changing a recorded mark keeps its first recording date`() = runTest {
        marks.seedMark(SaleFiscalMark(1L, FiscalMarkStatus.RECORDED, "A-1", EARLIER))

        mark(listOf(1L), FiscalMarkStatus.RECORDED, ReferenceChange.Write("A-2"))

        assertEquals(
            SaleFiscalMark(1L, FiscalMarkStatus.RECORDED, "A-2", EARLIER),
            marks.findMark(1L),
        )
    }

    @Test
    fun `not needed and back to pending drop the recording date`() = runTest {
        marks.seedMark(SaleFiscalMark(1L, FiscalMarkStatus.RECORDED, "A-1", EARLIER))

        mark(listOf(1L), FiscalMarkStatus.NOT_NEEDED, ReferenceChange.Write("A-1"))
        assertEquals(
            SaleFiscalMark(1L, FiscalMarkStatus.NOT_NEEDED, "A-1", null),
            marks.findMark(1L),
        )

        mark(listOf(1L), FiscalMarkStatus.PENDING, ReferenceChange.Write(""))
        assertEquals(SaleFiscalMark(1L, FiscalMarkStatus.PENDING, null, null), marks.findMark(1L))
        assertEquals(2, runner.commits)
    }

    @Test
    fun `marking several keeps each reference and writes all in one transaction`() = runTest {
        marks.seedMark(SaleFiscalMark(2L, FiscalMarkStatus.PENDING, "B-7", null))

        val result = mark(listOf(1L, 2L, 1L), FiscalMarkStatus.RECORDED, ReferenceChange.Keep)

        assertEquals(MarkFiscalResult.Marked(2), result)
        assertEquals(SaleFiscalMark(1L, FiscalMarkStatus.RECORDED, null, NOW), marks.findMark(1L))
        assertEquals(SaleFiscalMark(2L, FiscalMarkStatus.RECORDED, "B-7", NOW), marks.findMark(2L))
        assertEquals(1, runner.commits)
    }

    @Test
    fun `a failure on the second sale leaves no mark at all`() = runTest {
        marks.failAtWrite = 2

        val result = mark(listOf(1L, 2L), FiscalMarkStatus.NOT_NEEDED, ReferenceChange.Keep)

        assertEquals(MarkFiscalResult.Failed(StorageFailure.DISK_FULL), result)
        assertNull(marks.findMark(1L))
        assertNull(marks.findMark(2L))
        assertEquals(1, runner.rollbacks)
    }

    @Test
    fun `a voided or missing sale rejects the whole selection`() = runTest {
        assertEquals(
            MarkFiscalResult.SaleUnavailable,
            mark(listOf(1L, 3L), FiscalMarkStatus.RECORDED, ReferenceChange.Keep),
        )
        assertEquals(
            MarkFiscalResult.SaleUnavailable,
            mark(listOf(99L), FiscalMarkStatus.RECORDED, ReferenceChange.Keep),
        )
        assertNull(marks.findMark(1L))
        assertEquals(0, runner.commits)
    }

    @Test
    fun `a reference over sixty characters is rejected before writing`() = runTest {
        val tooLong = ReferenceChange.Write("R".repeat(61))

        val result = mark(listOf(1L), FiscalMarkStatus.RECORDED, tooLong)

        assertEquals(MarkFiscalResult.ReferenceTooLong, result)
        assertEquals(0, marks.writes)
        assertEquals(0, runner.commits + runner.rollbacks)
    }

    @Test
    fun `an empty selection writes nothing`() = runTest {
        assertEquals(
            MarkFiscalResult.NothingSelected,
            mark(emptyList(), FiscalMarkStatus.RECORDED, ReferenceChange.Keep),
        )
        assertEquals(0, runner.commits + runner.rollbacks)
    }

    @Test
    fun `nothing changes when the mark is the same or a sale without mark goes back to pending`() =
        runTest {
            marks.seedMark(SaleFiscalMark(1L, FiscalMarkStatus.NOT_NEEDED, null, null))

            assertEquals(
                MarkFiscalResult.Marked(1),
                mark(listOf(1L), FiscalMarkStatus.NOT_NEEDED, ReferenceChange.Keep),
            )
            assertEquals(
                MarkFiscalResult.Marked(1),
                mark(listOf(2L), FiscalMarkStatus.PENDING, ReferenceChange.Write(" ")),
            )

            assertEquals(0, marks.writes)
            assertNull(marks.findMark(2L))
            assertEquals(0, runner.commits)
            assertEquals(2, runner.rollbacks)
        }

    @Test
    fun `a pending mark with a reference is stored for a sale without mark`() = runTest {
        mark(listOf(2L), FiscalMarkStatus.PENDING, ReferenceChange.Write("C-3"))

        assertEquals(SaleFiscalMark(2L, FiscalMarkStatus.PENDING, "C-3", null), marks.findMark(2L))
    }

    private companion object {
        val DATE = BusinessDate(LocalDate(2026, 10, 28))
        val NOW: Instant = Instant.fromEpochMilliseconds(1_793_000_000_000)
        val EARLIER: Instant = Instant.fromEpochMilliseconds(1_790_000_000_000)
    }
}
