package io.github.yesidrobayo.shopledger.domain.model.export

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Un rango de exportación incluye sus dos extremos, y nunca termina antes de empezar. */
class CsvDateRangeTest {
    private fun day(text: String) = requireNotNull(BusinessDate.parse(text))

    @Test
    fun `a range keeps both ends`() {
        val range = requireNotNull(CsvDateRange.of(day("2026-11-01"), day("2026-11-30")))

        assertEquals(day("2026-11-01"), range.from)
        assertEquals(day("2026-11-30"), range.to)
    }

    @Test
    fun `a single day is a range`() {
        val range = requireNotNull(CsvDateRange.of(day("2026-11-12"), day("2026-11-12")))

        assertEquals(range.from, range.to)
    }

    @Test
    fun `a range that ends before it starts is not one`() {
        assertNull(CsvDateRange.of(day("2026-11-30"), day("2026-11-01")))
    }
}
