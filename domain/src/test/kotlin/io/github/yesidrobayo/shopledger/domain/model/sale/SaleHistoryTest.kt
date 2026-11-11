package io.github.yesidrobayo.shopledger.domain.model.sale

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Los filtros del historial y el cursor de cada fila. */
class SaleHistoryTest {
    private val date = BusinessDate(LocalDate(2026, 11, 2))

    @Test
    fun `any filter counts as filtered, and none does not`() {
        assertFalse(SaleHistoryFilter().isFiltered)
        assertTrue(SaleHistoryFilter(from = date).isFiltered)
        assertTrue(SaleHistoryFilter(to = date).isFiltered)
        assertTrue(SaleHistoryFilter(paymentMethodId = 1L).isFiltered)
        assertTrue(SaleHistoryFilter(onlyVoided = true).isFiltered)
    }

    @Test
    fun `a row continues the history from its time and id`() {
        val at = Instant.fromEpochMilliseconds(1_000L)
        val row = SaleHistoryRow(7L, at, date, Money(5L), false, "Arroz", 1, "Efectivo", 1)

        assertEquals(SaleHistoryCursor(at, 7L), row.cursor)
    }
}
