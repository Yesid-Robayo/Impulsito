package io.github.yesidrobayo.shopledger.domain.rule.region

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import kotlin.time.Instant
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

/** Un registro con fecha pasada se guarda a las 12:00 de ese día en la zona del negocio. */
class BusinessCalendarBackdatedTest {
    private fun calendar(zoneId: String) =
        BusinessCalendar(requireNotNull(BusinessTimeZone.of(zoneId)), DayOfWeek.MONDAY)

    @Test
    fun aPastDateIsStampedAtNoonInTheBusinessZone() {
        val date = BusinessDate(LocalDate(2026, 11, 1))

        val behind = calendar("Etc/GMT+5").stampBackdated(date)
        val ahead = calendar("Etc/GMT-9").stampBackdated(date)

        assertEquals(Instant.parse("2026-11-01T17:00:00Z"), behind.occurredAt)
        assertEquals(Instant.parse("2026-11-01T03:00:00Z"), ahead.occurredAt)
        // La fecha es la elegida, y el instante cae en ella con la misma zona.
        assertEquals(date, behind.businessDate)
        assertEquals(date, ahead.businessDate)
        assertEquals(date, calendar("Etc/GMT+5").businessDateOf(behind.occurredAt))
        assertEquals(date, calendar("Etc/GMT-9").businessDateOf(ahead.occurredAt))
    }
}
