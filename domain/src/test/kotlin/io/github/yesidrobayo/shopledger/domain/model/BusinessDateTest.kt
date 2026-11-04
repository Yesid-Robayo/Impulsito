package io.github.yesidrobayo.shopledger.domain.model

import io.github.yesidrobayo.shopledger.domain.testing.FixedClock
import io.github.yesidrobayo.shopledger.domain.testing.ForeignSystemDefaults
import io.github.yesidrobayo.shopledger.domain.testing.ForeignSystemDefaultsRule
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Fecha de negocio a partir del instante y la zona del negocio. Cada prueba corre con zonas e
 * idiomas por defecto de la JVM elegidos para que, si la regla leyera los del equipo, el resultado
 * cambiara.
 */
@RunWith(Parameterized::class)
class BusinessDateTest(private val defaults: ForeignSystemDefaults) {
    @get:Rule
    val foreignDefaults = ForeignSystemDefaultsRule(defaults)

    @Test
    fun `the test really runs with a foreign default zone`() {
        assertEquals(defaults.zoneId, java.util.TimeZone.getDefault().id)
        assertEquals(defaults.zoneId, kotlinx.datetime.TimeZone.currentSystemDefault().id)
    }

    @Test
    fun `half an hour before midnight in a zone five hours behind is still the previous day`() {
        assertEquals(date("2026-11-01"), businessDate(1_793_593_800_000, "Etc/GMT+5"))
    }

    @Test
    fun `midnight in the business zone starts the next business day`() {
        assertEquals(date("2026-11-02"), businessDate(1_793_595_600_000, "Etc/GMT+5"))
    }

    @Test
    fun `the same instant is already the next day in a zone nine hours ahead`() {
        assertEquals(date("2026-11-02"), businessDate(1_793_593_800_000, "Etc/GMT-9"))
    }

    @Test
    fun `a business date already written keeps its day after the business zone changes`() {
        val occurredAt = 1_793_593_800_000
        val written = businessDate(occurredAt, "Etc/GMT+5")

        // La persona cambia la zona: un registro nuevo en el mismo instante cae en otro día,
        // y el valor ya escrito no se recalcula.
        val writtenAfterChange = businessDate(occurredAt, "Etc/GMT-9")

        assertEquals(date("2026-11-01"), written)
        assertEquals(date("2026-11-02"), writtenAfterChange)
    }

    @Test
    fun `negative, half hour and daylight saving offsets give the expected business dates`() {
        // Zonas de la matriz de configuraciones: datos de prueba, no un supuesto del código.
        val examples =
            listOf(
                Triple(1_793_593_800_000, "America/Los_Angeles", "2026-11-01"),
                Triple(1_793_593_800_000, "America/St_Johns", "2026-11-02"),
                Triple(1_793_593_800_000, "Asia/Kolkata", "2026-11-02"),
                Triple(1_793_557_799_000, "Asia/Kolkata", "2026-11-01"),
                Triple(1_793_557_800_000, "Asia/Kolkata", "2026-11-02"),
                Triple(1_796_095_799_000, "America/St_Johns", "2026-11-30"),
                Triple(1_796_095_800_000, "America/St_Johns", "2026-12-01"),
                Triple(1_798_774_199_000, "America/St_Johns", "2026-12-31"),
                Triple(1_798_741_800_000, "Asia/Kolkata", "2027-01-01"),
                Triple(1_793_530_799_000, "Pacific/Auckland", "2026-11-01"),
                Triple(1_793_530_800_000, "Pacific/Auckland", "2026-11-02"),
            )
        for ((occurredAt, zoneId, expected) in examples) {
            assertEquals(
                "$occurredAt en $zoneId",
                date(expected),
                businessDate(occurredAt, zoneId),
            )
        }
    }

    @Test
    fun `today comes from the injected clock and the business zone`() {
        val clock = FixedClock.atEpochMillis(1_793_593_800_000)

        assertEquals(date("2026-11-01"), BusinessDate.of(clock.now(), zone("Etc/GMT+5")))
        assertEquals(date("2026-11-02"), BusinessDate.of(clock.now(), zone("Etc/GMT-9")))
    }

    @Test
    fun `the stored text is the same in any default language`() {
        assertEquals("2026-11-01", date("2026-11-01").toString())
        assertEquals("0999-01-02", BusinessDate(LocalDate(999, 1, 2)).toString())
        assertEquals("0000-01-01", BusinessDate(LocalDate(0, 1, 1)).toString())
        assertEquals("9999-12-31", BusinessDate(LocalDate(9999, 12, 31)).toString())
    }

    @Test
    fun `parse reads the stored text back`() {
        assertEquals(BusinessDate(LocalDate(2026, 11, 1)), BusinessDate.parse("2026-11-01"))
        assertEquals(BusinessDate(LocalDate(2028, 2, 29)), BusinessDate.parse("2028-02-29"))
        assertEquals(BusinessDate(LocalDate(0, 1, 1)), BusinessDate.parse("0000-01-01"))
        assertEquals(BusinessDate(LocalDate(9999, 12, 31)), BusinessDate.parse("9999-12-31"))
    }

    @Test
    fun `parse rejects anything that is not a real date in the stored form`() {
        val rejected =
            listOf(
                "",
                "2026-02-29",
                "2026-02-30",
                "2026-04-31",
                "2026-13-01",
                "2026-00-10",
                "2026-11-00",
                "2026-11-1",
                "2026-1-01",
                "26-11-01",
                " 2026-11-01",
                "2026-11-01 ",
                "+2026-11-01",
                "+10000-01-01",
                "-0001-12-31",
                "20261101",
                "2026/11/01",
                "2026-11-01T00:00",
                "2026-11",
                "٢٠٢٦-١١-٠١",
                "๒๐๒๖-๑๑-๐๑",
                "２０２６-１１-０１",
            )
        for (text in rejected) {
            assertNull("'$text'", BusinessDate.parse(text))
        }
    }

    @Test
    fun `plusDays moves across months, years and leap days`() {
        assertEquals(date("2028-02-29"), date("2028-02-28").plusDays(1))
        assertEquals(date("2027-03-01"), date("2027-02-28").plusDays(1))
        assertEquals(date("2027-01-01"), date("2026-12-31").plusDays(1))
        assertEquals(date("2026-02-28"), date("2026-03-01").plusDays(-1))
        assertEquals(date("2026-10-26"), date("2026-11-01").plusDays(-6))
        assertEquals(date("2026-11-01"), date("2026-11-01").plusDays(0))
    }

    @Test
    fun `business dates sort in calendar order`() {
        val unsorted = listOf(date("2027-01-01"), date("2026-11-01"), date("2026-12-31"))

        assertEquals(
            listOf(date("2026-11-01"), date("2026-12-31"), date("2027-01-01")),
            unsorted.sorted(),
        )
        assertTrue(date("2026-11-01") < date("2026-11-02"))
        assertEquals(0, date("2026-11-01").compareTo(date("2026-11-01")))
        assertNotEquals(date("2026-11-01"), date("2026-11-02"))
    }

    private fun businessDate(occurredAtMillis: Long, zoneId: String): BusinessDate =
        BusinessDate.of(Instant.fromEpochMilliseconds(occurredAtMillis), zone(zoneId))

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun defaults(): List<ForeignSystemDefaults> = ForeignSystemDefaults.ALL

        /** Fecha escrita a mano, leída con la biblioteca de fechas, no con el código probado. */
        fun date(text: String): BusinessDate = BusinessDate(LocalDate.parse(text))

        fun zone(id: String): BusinessTimeZone = requireNotNull(BusinessTimeZone.of(id)) { id }
    }
}
