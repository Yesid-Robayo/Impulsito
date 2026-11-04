package io.github.yesidrobayo.shopledger.domain.model.backup

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
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
 * El instante del nombre de una copia y su día de poda, siempre en UTC. Cada prueba corre con una
 * zona y un idioma por defecto de la JVM elegidos para que, si la regla los leyera, el resultado
 * cambiara.
 */
@RunWith(Parameterized::class)
class CopyInstantTest(private val defaults: ForeignSystemDefaults) {
    @get:Rule
    val foreignDefaults = ForeignSystemDefaultsRule(defaults)

    @Test
    fun `the stamp of a copy is written and read back in UTC`() {
        val instant = requireNotNull(CopyInstant.parse("20261101T203012Z"))

        assertEquals("20261101T203012Z", instant.toString())
        assertEquals(Instant.parse("2026-11-01T20:30:12Z").epochSeconds, instant.epochSeconds)
    }

    @Test
    fun `a copy is grouped by its UTC day whatever the business zone`() {
        // Media hora antes de la medianoche UTC. Para un negocio catorce horas adelante ya es el
        // día siguiente, pero la copia sigue en su día UTC: no depende de ninguna zona.
        val instant = Instant.parse("2026-11-01T23:30:00Z")
        val farAhead = requireNotNull(BusinessTimeZone.of("Etc/GMT-14"))

        val day = requireNotNull(CopyInstant.of(instant)).retentionDay

        assertEquals(RetentionDay(LocalDate(2026, 11, 1)), day)
        assertEquals(LocalDate(2026, 11, 2), BusinessDate.of(instant, farAhead).date)
    }

    @Test
    fun `UTC midnight starts the next retention day`() {
        val lastSecond = requireNotNull(CopyInstant.parse("20261101T235959Z"))
        val midnight = requireNotNull(CopyInstant.parse("20261102T000000Z"))

        assertEquals("2026-11-01", lastSecond.retentionDay.toString())
        assertEquals("2026-11-02", midnight.retentionDay.toString())
        assertTrue(lastSecond.retentionDay < midnight.retentionDay)
        assertTrue(lastSecond < midnight)
    }

    @Test
    fun `the fraction of a second is dropped toward the past`() {
        val afterEpoch = requireNotNull(CopyInstant.of(Instant.parse("2026-11-01T20:30:12.999Z")))
        val beforeEpoch = requireNotNull(CopyInstant.of(Instant.parse("1969-12-31T23:59:59.500Z")))

        assertEquals("20261101T203012Z", afterEpoch.toString())
        assertEquals("19691231T235959Z", beforeEpoch.toString())
    }

    @Test
    fun `the first and last instants with a four digit year are padded`() {
        val first = requireNotNull(CopyInstant.of(Instant.parse("0000-01-01T00:00:00Z")))
        val last = requireNotNull(CopyInstant.of(Instant.parse("9999-12-31T23:59:59Z")))

        assertEquals("00000101T000000Z", first.toString())
        assertEquals("99991231T235959Z", last.toString())
        assertEquals(first, CopyInstant.parse(first.toString()))
        assertEquals(last, CopyInstant.parse(last.toString()))
    }

    @Test
    fun `an instant whose year does not fit four digits has no stamp`() {
        // Un segundo antes de 0000-01-01T00:00:00Z, y un segundo después de 9999-12-31T23:59:59Z.
        assertNull(CopyInstant.of(Instant.fromEpochSeconds(-62_167_219_201L)))
        assertNull(CopyInstant.of(Instant.fromEpochSeconds(253_402_300_800L)))
    }

    @Test
    fun `a stamp out of shape is not read`() {
        val rejected =
            listOf(
                "", "20261101T203012", "20261101 203012Z", "20261101T203012z", "2026-11-01T20:30Z",
                "20261101T2030120Z", "2026110AT203012Z", "2026/101T203012Z",
                "٢٠٢٦1101T203012Z", "20261101T20301２Z",
            )

        for (stamp in rejected) assertNull(stamp, CopyInstant.parse(stamp))
    }

    @Test
    fun `an impossible date or time is not read`() {
        val impossible =
            listOf("20260230T000000Z", "20261301T000000Z", "20261101T240000Z", "20261101T235960Z")

        for (stamp in impossible) assertNull(stamp, CopyInstant.parse(stamp))
    }

    @Test
    fun `days compare by date`() {
        val first = RetentionDay(LocalDate(2026, 10, 31))
        val second = RetentionDay(LocalDate(2026, 11, 1))

        assertTrue(first < second)
        assertNotEquals(first, second)
    }

    @Test
    fun `no function of the retention base receives a zone`() {
        // La poda agrupa por UTC: no tiene que existir ninguna forma de pedirle el día en otra zona.
        val types =
            listOf(
                CopyInstant::class.java,
                CopyInstant.Companion::class.java,
                RetentionDay::class.java,
            )
        val zoneParameters =
            types.flatMap { type -> type.declaredMethods.toList() + type.declaredConstructors }
                .flatMap { member -> member.parameterTypes.map { "${member.name}: ${it.name}" } }
                .filter { parameter -> ZONE_TYPES.any { parameter.contains(it) } }

        assertTrue(zoneParameters.toString(), zoneParameters.isEmpty())
    }

    companion object {
        /** Las partes de nombre de los tipos de zona de Kotlin, de Java y del negocio. */
        private val ZONE_TYPES = listOf("TimeZone", "ZoneId", "Offset")

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun parameters(): List<ForeignSystemDefaults> = ForeignSystemDefaults.ALL
    }
}
